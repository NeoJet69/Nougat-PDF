package app.nougat.pdf.engine

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.io.File
import kotlin.concurrent.thread

/** Moteur PDF de la version ordinateur, base sur Apache PDFBox. */
class DesktopPdfEngine : PdfEngine {

    override val ocrLanguages: List<String> by lazy { TessData.languages() }

    override suspend fun open(path: String, password: String?): PdfDocument = withContext(Dispatchers.IO) {
        val doc = try {
            Loader.loadPDF(File(path), password ?: "")
        } catch (e: InvalidPasswordException) {
            throw PasswordRequiredException(wrongPassword = password != null)
        }
        DesktopPdfDocument(doc)
    }

    override suspend fun edit(src: String, password: String?, dst: String, block: (EditableDoc) -> Unit) =
        withContext(Dispatchers.IO) {
            try {
                editWithPdfBox(src, password, dst, block)
            } catch (e: InvalidPasswordException) {
                throw PasswordRequiredException(wrongPassword = password != null)
            }
        }

    override suspend fun create(dst: String, block: (EditableDoc) -> Unit) =
        withContext(Dispatchers.IO) { createWithPdfBox(dst, block) }
}

private class DesktopPdfDocument(private val doc: PDDocument) : PdfDocument {
    // PDFBox n'aime pas etre utilise par plusieurs fils a la fois.
    private val mutex = Mutex()
    private val renderer = PDFRenderer(doc)

    override val pageCount: Int = doc.numberOfPages

    override val pageSizes: List<PageSize> = List(pageCount) { i -> rotatedSize(i) }

    private fun rotatedSize(index: Int): PageSize {
        val page = doc.getPage(index)
        val box = page.cropBox
        return if (page.rotation % 180 != 0) PageSize(box.height, box.width) else PageSize(box.width, box.height)
    }

    override suspend fun renderPage(index: Int, widthPx: Int): ImageBitmap = withContext(Dispatchers.IO) {
        mutex.withLock {
            val scale = widthPx / pageSizes[index].width
            renderer.renderImage(index, scale, ImageType.RGB).toComposeImageBitmap()
        }
    }

    override suspend fun pageText(index: Int): PageText = withContext(Dispatchers.IO) {
        mutex.withLock {
            val size = pageSizes[index]
            val stripper = GlyphStripper(size.width, size.height)
            stripper.sortByPosition = true
            stripper.startPage = index + 1
            stripper.endPage = index + 1
            stripper.getText(doc)
            stripper.builder.build()
        }
    }

    override suspend fun renderPageJpeg(index: Int, widthPx: Int): ByteArray = withContext(Dispatchers.IO) {
        mutex.withLock {
            val scale = widthPx / pageSizes[index].width
            val image = renderer.renderImage(index, scale, ImageType.RGB)
            java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "jpg", it) }.toByteArray()
        }
    }

    override suspend fun textBlocks(index: Int): List<TextBlock> = withContext(Dispatchers.IO) {
        mutex.withLock { pageTextBlocks(doc, index) }
    }

    override suspend fun formFields(index: Int): List<FormField> = withContext(Dispatchers.IO) {
        mutex.withLock { runCatching { pageFormFields(doc, index) }.getOrDefault(emptyList()) }
    }

    override suspend fun pagesNeedingOcr(): Int = withContext(Dispatchers.IO) {
        mutex.withLock { runCatching { countPagesNeedingOcr(doc) }.getOrDefault(0) }
    }

    override suspend fun hasForm(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock { runCatching { documentHasForm(doc) }.getOrDefault(false) }
    }

    override fun close() {
        // On attend la fin d'un rendu en cours, sans bloquer l'ecran.
        thread(name = "pdf-close", isDaemon = true) {
            runBlocking { mutex.withLock { doc.close() } }
        }
    }
}

/** Recupere chaque lettre avec sa position sur la page. */
private class GlyphStripper(private val pageWidth: Float, private val pageHeight: Float) : PDFTextStripper() {
    val builder = PageTextBuilder()

    override fun writeString(text: String, textPositions: List<TextPosition>) {
        for (tp in textPositions) {
            val x = tp.xDirAdj
            val y = tp.yDirAdj // ligne de base, depuis le haut
            val h = tp.heightDir.takeIf { it > 0f } ?: tp.fontSizeInPt
            builder.addGlyph(
                tp.unicode ?: continue,
                NormRect(
                    left = x / pageWidth,
                    top = (y - h * 1.1f) / pageHeight,
                    right = (x + tp.widthDirAdj) / pageWidth,
                    bottom = (y + h * 0.3f) / pageHeight,
                ),
            )
        }
    }

    override fun writeWordSeparator() = builder.addSpace()
    override fun writeLineSeparator() = builder.addSpace()
}
