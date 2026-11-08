package app.nougat.pdf.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import java.io.ByteArrayOutputStream
import java.io.File

/** Ouvre, modifie et enregistre un PDF avec PDFBox (version Android, PdfBox-Android). */
internal fun editWithPdfBoxAndroid(src: String, password: String?, dst: String, models: AndroidOcrModels?, block: (EditableDoc) -> Unit) {
    PDDocument.load(File(src), password ?: "").use { doc ->
        val editable = AndroidEditableDoc(doc, password, File(src), models)
        try {
            block(editable)
            editable.applySecurity()
            doc.save(File(dst))
        } finally {
            editable.closeSources()
        }
    }
}

/** Cree un nouveau PDF avec PdfBox-Android. */
internal fun createWithPdfBoxAndroid(dst: String, block: (EditableDoc) -> Unit) {
    PDDocument().use { doc ->
        val editable = AndroidEditableDoc(doc, null, null, null)
        try {
            block(editable)
            if (doc.numberOfPages == 0) doc.addPage(PDPage(PDRectangle(A4_WIDTH, A4_HEIGHT)))
            editable.applySecurity()
            doc.save(File(dst))
        } finally {
            editable.closeSources()
        }
    }
}

/**
 * Lit une image en la reduisant (au plus [maxSide] pixels de cote : environ 250 dpi sur une page
 * A4, net et leger) et en la remettant droite : les photos du telephone sont souvent
 * enregistrees couchees (EXIF).
 */
private fun loadUprightBitmap(path: String, maxSide: Int = 2400): Pair<Bitmap, Boolean>? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    var bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val longest = maxOf(bitmap.width, bitmap.height)
    if (longest > maxSide) {
        val k = maxSide.toFloat() / longest
        val scaled = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * k).toInt().coerceAtLeast(1),
            (bitmap.height * k).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== bitmap) bitmap.recycle()
        bitmap = scaled
    }
    val lossless = bounds.outMimeType?.let { it.contains("png") || it.contains("webp") || it.contains("gif") || it.contains("bmp") } ?: false
    val degrees = runCatching {
        when (ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)
    if (degrees == 0) return bitmap to lossless
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, android.graphics.Matrix().apply { postRotate(degrees.toFloat()) }, true)
    if (rotated !== bitmap) bitmap.recycle()
    return rotated to lossless
}

private class AndroidEditableDoc(
    private val doc: PDDocument,
    private var password: String?,
    /** Le fichier d'origine (rendu des pages pour l'OCR) ; null pour un nouveau document. */
    private val src: File?,
    private val ocrModels: AndroidOcrModels?,
) : EditableDoc {
    private val sources = ArrayList<PDDocument>()
    private val font by lazy { PDType1Font.HELVETICA }

    override val pageCount: Int get() = doc.numberOfPages

    override fun geometry(index: Int): PageGeometry {
        val page = doc.getPage(index)
        val box = page.cropBox
        return PageGeometry(box.lowerLeftX, box.lowerLeftY, box.width, box.height, normalizeRotation(page.rotation))
    }

    override fun setRotation(index: Int, degrees: Int) {
        doc.getPage(index).rotation = normalizeRotation(degrees)
    }

    override fun reorder(order: List<Int>) {
        require(order.distinct().size == order.size) { "Page en double" }
        val pages = (0 until doc.numberOfPages).map { doc.getPage(it) }
        val tree = doc.pages
        pages.forEach { tree.remove(it) }
        order.forEach { tree.add(pages[it]) }
    }

    override fun insertBlankPage(at: Int, width: Float, height: Float) {
        val page = PDPage(PDRectangle(width, height))
        if (at >= doc.numberOfPages) doc.addPage(page) else doc.pages.insertBefore(page, doc.getPage(at))
    }

    override fun appendPdf(path: String, password: String?) {
        var other = PDDocument.load(File(path), password ?: "")
        if (other.isEncrypted) {
            // PDFBox refuse de fusionner un PDF chiffre : on passe par une copie en clair.
            other.isAllSecurityToBeRemoved = true
            val bytes = ByteArrayOutputStream().also { other.save(it) }.toByteArray()
            other.close()
            other = PDDocument.load(bytes)
        }
        sources.add(other)
        PDFMergerUtility().appendDocument(doc, other)
    }

    override fun appendImages(paths: List<String>) {
        for (path in paths) {
            val (bitmap, lossless) = loadUprightBitmap(path) ?: continue
            try {
                // Captures d'ecran : sans perte (texte net) ; photos : JPEG (fichier leger).
                val image = if (lossless) LosslessFactory.createFromImage(doc, bitmap) else JPEGFactory.createFromImage(doc, bitmap, 0.88f)
                val l = imagePageLayout(bitmap.width, bitmap.height)
                val page = PDPage(PDRectangle(l[0], l[1]))
                doc.addPage(page)
                PDPageContentStream(doc, page).use { it.drawImage(image, l[2], l[3], l[4], l[5]) }
            } finally {
                bitmap.recycle()
            }
        }
    }

    override fun draw(index: Int, block: (PageCanvas) -> Unit) {
        val page = doc.getPage(index)
        PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            val m = geometry(index).displayToPdfMatrix()
            cs.transform(Matrix(m[0], m[1], m[2], m[3], m[4], m[5]))
            block(Canvas(cs))
        }
    }

    override fun setPassword(password: String?) {
        this.password = password
    }

    override fun replaceText(block: TextBlock, newText: String): TextEditResult =
        replaceTextWithPdfBoxAndroid(doc, src, block, newText)

    override fun setFieldValue(name: String, value: String) = setFormFieldValueAndroid(doc, name, value)

    override fun recognizeText(options: OcrOptions, progress: (Int, Int) -> Boolean): OcrSummary {
        val models = ocrModels ?: throw UnsupportedOperationException("OCR indisponible")
        val reader = if (options.aiProofread) GeminiNano.lineReader(options.languages) else null
        return recognizeDocumentAndroid(doc, src, models, options, reader, ::geometry, ::setRotation, progress)
    }

    override fun writeTranslation(index: Int, paragraphs: List<TranslatedParagraph>, language: String, eraseImages: Boolean) {
        writeTranslationAndroid(doc, src, index, paragraphs, language, eraseImages)?.let(sources::add)
    }

    override fun eraseUnder(index: Int, areas: List<Pair<NormRect, Int>>) = eraseUnderAndroid(doc, index, areas)

    override fun flatten(dpi: Float) = flattenAndroid(doc, src, dpi)

    override fun cropMargins(pages: List<Int>): Int = cropMarginsAndroid(doc, src, pages)

    override fun straighten(pages: List<Int>): Int = straightenAndroid(doc, src, pages)

    override fun compressImages(level: CompressLevel): Int = compressImagesAndroid(doc, level)

    fun applySecurity() {
        val pwd = password
        if (pwd.isNullOrEmpty()) {
            if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
        } else {
            doc.protect(StandardProtectionPolicy(pwd, pwd, AccessPermission()).apply { encryptionKeyLength = 256 })
        }
    }

    fun closeSources() {
        sources.forEach { runCatching { it.close() } }
    }

    private inner class Canvas(private val cs: PDPageContentStream) : PageCanvas {

        private fun withOpacity(opacity: Float, body: () -> Unit) {
            cs.saveGraphicsState()
            if (opacity < 1f) {
                cs.setGraphicsStateParameters(PDExtendedGraphicsState().apply {
                    strokingAlphaConstant = opacity
                    nonStrokingAlphaConstant = opacity
                })
            }
            body()
            cs.restoreGraphicsState()
        }

        override fun stroke(points: FloatArray, argb: Int, width: Float, opacity: Float) {
            if (points.size < 2) return
            withOpacity(opacity) {
                cs.setStrokingColor(red(argb), green(argb), blue(argb))
                cs.setLineWidth(width)
                cs.setLineCapStyle(1)
                cs.setLineJoinStyle(1)
                cs.moveTo(points[0], points[1])
                if (points.size == 2) cs.lineTo(points[0] + 0.01f, points[1])
                var i = 2
                while (i + 1 < points.size) {
                    cs.lineTo(points[i], points[i + 1]); i += 2
                }
                cs.stroke()
            }
        }

        override fun fillRect(x: Float, y: Float, width: Float, height: Float, argb: Int, opacity: Float) {
            withOpacity(opacity) {
                cs.setNonStrokingColor(red(argb), green(argb), blue(argb))
                cs.addRect(x, y, width, height)
                cs.fill()
            }
        }

        private fun fontOf(kind: FontKind): PDType1Font = when (kind) {
            FontKind.Regular -> PDType1Font.HELVETICA
            FontKind.Bold -> PDType1Font.HELVETICA_BOLD
            FontKind.Italic -> PDType1Font.HELVETICA_OBLIQUE
            FontKind.BoldItalic -> PDType1Font.HELVETICA_BOLD_OBLIQUE
            FontKind.Mono -> PDType1Font.COURIER
        }

        private fun safe(f: PDType1Font, text: String) = buildString {
            for (c in text) append(if (runCatching { f.encode(c.toString()) }.isSuccess) c else '?')
        }

        override fun styledWidth(text: String, size: Float, font: FontKind): Float {
            val f = fontOf(font)
            return runCatching { f.getStringWidth(safe(f, text)) / 1000f * size }.getOrDefault(text.length * size * 0.5f)
        }

        override fun styledText(x: Float, baseline: Float, text: String, size: Float, argb: Int, font: FontKind) {
            val f = fontOf(font)
            cs.beginText()
            cs.setFont(f, size)
            cs.setNonStrokingColor(red(argb), green(argb), blue(argb))
            cs.newLineAtOffset(x, baseline)
            cs.showText(safe(f, text))
            cs.endText()
        }

        override fun textWidth(text: String, size: Float): Float = runCatching {
            val safe = buildString { for (c in text) append(if (runCatching { font.encode(c.toString()) }.isSuccess) c else '?') }
            font.getStringWidth(safe) / 1000f * size
        }.getOrDefault(text.length * size * 0.5f)

        override fun text(x: Float, baseline: Float, text: String, size: Float, argb: Int) {
            val safe = buildString {
                for (c in text) append(if (runCatching { font.encode(c.toString()) }.isSuccess) c else '?')
            }
            cs.beginText()
            cs.setFont(font, size)
            cs.setNonStrokingColor(red(argb), green(argb), blue(argb))
            cs.newLineAtOffset(x, baseline)
            cs.showText(safe)
            cs.endText()
        }
    }
}

private fun red(argb: Int) = ((argb shr 16) and 0xFF) / 255f
private fun green(argb: Int) = ((argb shr 8) and 0xFF) / 255f
private fun blue(argb: Int) = (argb and 0xFF) / 255f
