package app.nougat.pdf.engine

import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import java.io.ByteArrayOutputStream
import java.io.File

/** Ouvre, modifie et enregistre un PDF avec PDFBox (version Android, PdfBox-Android). */
internal fun editWithPdfBoxAndroid(src: String, password: String?, dst: String, block: (EditableDoc) -> Unit) {
    PDDocument.load(File(src), password ?: "").use { doc ->
        val editable = AndroidEditableDoc(doc, password)
        try {
            block(editable)
            editable.applySecurity()
            doc.save(File(dst))
        } finally {
            editable.closeSources()
        }
    }
}

private class AndroidEditableDoc(private val doc: PDDocument, private var password: String?) : EditableDoc {
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
        replaceTextWithPdfBoxAndroid(doc, block, newText)

    override fun setFieldValue(name: String, value: String) = setFormFieldValueAndroid(doc, name, value)

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
