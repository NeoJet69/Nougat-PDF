package app.nougat.pdf.engine

import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.util.Matrix
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

/*
 * Outils de pages, version ordinateur (PDFBox) : rogner les marges, redresser un scan penche,
 * compresser les images. L'analyse de l'image de la page est commune (PageTools.kt).
 */

private const val ANALYSIS_DPI = 72f

private fun geometryOf(doc: PDDocument, index: Int): PageGeometry {
    val page = doc.getPage(index)
    val box = page.cropBox
    return PageGeometry(box.lowerLeftX, box.lowerLeftY, box.width, box.height, normalizeRotation(page.rotation))
}

/** Rogne les marges blanches des pages [pages]. Renvoie le nombre de pages rognees. */
internal fun cropMarginsWithPdfBox(doc: PDDocument, pages: List<Int>): Int {
    val renderer = PDFRenderer(doc)
    var count = 0
    for (index in pages) {
        val image = renderer.renderImageWithDPI(index, ANALYSIS_DPI, ImageType.GRAY)
        val bounds = PageTools.contentBounds(image.toGrayImage()) ?: continue
        val g = geometryOf(doc, index)
        val frame = PageTools.cropFrame(bounds, g.displayWidth, g.displayHeight) ?: continue
        doc.getPage(index).cropBox = displayFrameToPdf(g, frame).let { PDRectangle(it[0], it[1], it[2] - it[0], it[3] - it[1]) }
        count++
    }
    return count
}

/** Le cadre [frame] (fractions de la page affichee) dans le repere du fichier : x0, y0, x1, y1. */
internal fun displayFrameToPdf(g: PageGeometry, frame: NormRect): FloatArray {
    val m = g.displayToPdfMatrix()
    val w = g.displayWidth
    val h = g.displayHeight
    val xs = ArrayList<Float>()
    val ys = ArrayList<Float>()
    for (x in floatArrayOf(frame.left * w, frame.right * w)) for (y in floatArrayOf(h - frame.bottom * h, h - frame.top * h)) {
        xs.add(m[0] * x + m[2] * y + m[4])
        ys.add(m[1] * x + m[3] * y + m[5])
    }
    return floatArrayOf(xs.min(), ys.min(), xs.max(), ys.max())
}

/** Redresse les pages [pages] un peu penchees. Renvoie le nombre de pages redressees. */
internal fun straightenWithPdfBox(doc: PDDocument, pages: List<Int>): Int {
    val renderer = PDFRenderer(doc)
    var count = 0
    for (index in pages) {
        val image = renderer.renderImageWithDPI(index, 100f, ImageType.GRAY)
        val degrees = PageTools.skewDegrees(image.toGrayImage())
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

/** Recompresse les images trop fines ou trop lourdes. Renvoie le nombre d'images remplacees. */
internal fun compressImagesWithPdfBox(doc: PDDocument, level: CompressLevel): Int {
    var count = 0
    val done = HashSet<org.apache.pdfbox.cos.COSBase>()
    for (page in doc.pages) {
        val finder = PageImageFinder()
        finder.processPage(page)
        val resources = page.resources ?: continue
        for ((name, ctm) in finder.found.distinctBy { it.first }) {
            val image = resources.getXObject(name) as? PDImageXObject ?: continue
            if (!done.add(image.cosObject)) continue
            val dict = image.cosObject
            // Images a ne pas toucher : masques, transparence, noir et blanc (deja tres legeres).
            if (image.isStencil || image.bitsPerComponent <= 1 || dict.containsKey(COSName.SMASK) || dict.containsKey(COSName.MASK)) continue
            val shownW = hypot(ctm[0], ctm[1]) / 72f * level.dpi
            val shownH = hypot(ctm[2], ctm[3]) / 72f * level.dpi
            val scale = max(shownW / image.width, shownH / image.height).coerceAtMost(1f)
            val original = runCatching { image.image }.getOrNull() ?: continue
            val gray = original.type == BufferedImage.TYPE_BYTE_GRAY
            val w = max(1, ceil(image.width * scale).toInt())
            val h = max(1, ceil(image.height * scale).toInt())
            val resized = BufferedImage(w, h, if (gray) BufferedImage.TYPE_BYTE_GRAY else BufferedImage.TYPE_INT_RGB)
            val g = resized.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(original, 0, 0, w, h, null)
            g.dispose()
            val jpeg = JPEGFactory.createFromImage(doc, resized, level.quality)
            // Seulement si c'est vraiment plus leger.
            if (jpeg.cosObject.length < dict.length * 0.9) {
                resources.put(name, jpeg)
                count++
            }
        }
    }
    return count
}
