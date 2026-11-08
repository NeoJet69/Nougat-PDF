package app.nougat.pdf.engine

import android.graphics.Bitmap
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

/*
 * Outils de pages, version Android : rogner les marges, redresser un scan penche, compresser les
 * images. L'analyse de l'image de la page est commune (PageTools.kt).
 */

private fun geometryOf(doc: PDDocument, index: Int): PageGeometry {
    val page = doc.getPage(index)
    val box = page.cropBox
    return PageGeometry(box.lowerLeftX, box.lowerLeftY, box.width, box.height, normalizeRotation(page.rotation))
}

private fun grayPage(doc: PDDocument, src: File?, index: Int, dpi: Float): GrayImage {
    val bitmap = renderPageForScanEdit(doc, src, index, dpi)
    try {
        return bitmap.toGrayImage()
    } finally {
        bitmap.recycle()
    }
}

internal fun cropMarginsAndroid(doc: PDDocument, src: File?, pages: List<Int>): Int {
    var count = 0
    for (index in pages) {
        val bounds = PageTools.contentBounds(grayPage(doc, src, index, 72f)) ?: continue
        val g = geometryOf(doc, index)
        val frame = PageTools.cropFrame(bounds, g.displayWidth, g.displayHeight) ?: continue
        val m = g.displayToPdfMatrix()
        val w = g.displayWidth
        val h = g.displayHeight
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        for (x in floatArrayOf(frame.left * w, frame.right * w)) for (y in floatArrayOf(h - frame.bottom * h, h - frame.top * h)) {
            xs.add(m[0] * x + m[2] * y + m[4])
            ys.add(m[1] * x + m[3] * y + m[5])
        }
        doc.getPage(index).cropBox = PDRectangle(xs.min(), ys.min(), xs.max() - xs.min(), ys.max() - ys.min())
        count++
    }
    return count
}

internal fun straightenAndroid(doc: PDDocument, src: File?, pages: List<Int>): Int {
    var count = 0
    for (index in pages) {
        val degrees = PageTools.skewDegrees(grayPage(doc, src, index, 100f))
        if (!PageTools.worthStraightening(degrees)) continue
        val page = doc.getPage(index)
        val m = PageTools.straightenMatrix(geometryOf(doc, index), degrees)
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.PREPEND, true).use { cs ->
            cs.saveGraphicsState()
            cs.transform(Matrix(m[0], m[1], m[2], m[3], m[4], m[5]))
        }
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true).use { it.restoreGraphicsState() }
        count++
    }
    return count
}

internal fun compressImagesAndroid(doc: PDDocument, level: CompressLevel): Int {
    var count = 0
    val done = HashSet<COSBase>()
    for (page in doc.pages) {
        val finder = PageImageFinder()
        finder.processPage(page)
        val resources = page.resources ?: continue
        for ((name, ctm) in finder.found.distinctBy { it.first }) {
            val image = resources.getXObject(name) as? PDImageXObject ?: continue
            if (!done.add(image.cosObject)) continue
            val dict = image.cosObject
            if (image.isStencil || image.bitsPerComponent <= 1 || dict.containsKey(COSName.SMASK) || dict.containsKey(COSName.MASK)) continue
            val shownW = hypot(ctm[0], ctm[1]) / 72f * level.dpi
            val shownH = hypot(ctm[2], ctm[3]) / 72f * level.dpi
            val scale = max(shownW / image.width, shownH / image.height).coerceAtMost(1f)
            val original = runCatching { image.image }.getOrNull() ?: continue
            val w = max(1, ceil(image.width * scale).toInt())
            val h = max(1, ceil(image.height * scale).toInt())
            val resized = if (w == original.width && h == original.height) original else Bitmap.createScaledBitmap(original, w, h, true)
            try {
                val jpeg = JPEGFactory.createFromImage(doc, resized, level.quality)
                if (jpeg.cosObject.length < dict.length * 0.9) {
                    resources.put(name, jpeg)
                    count++
                }
            } finally {
                if (resized !== original) resized.recycle()
            }
        }
    }
    return count
}
