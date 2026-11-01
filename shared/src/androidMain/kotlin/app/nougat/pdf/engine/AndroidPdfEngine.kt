package app.nougat.pdf.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * Moteur PDF Android : l'affichage passe par PdfRenderer (integre a Android, rapide),
 * le texte et les mots de passe par PdfBox-Android.
 */
class AndroidPdfEngine(context: Context) : PdfEngine {
    private val appContext = context.applicationContext

    init {
        PDFBoxResourceLoader.init(appContext)
    }

    override suspend fun open(path: String, password: String?): PdfDocument = withContext(Dispatchers.IO) {
        val file = File(path)
        try {
            AndroidPdfDocument(file, textFile = file, temporary = null)
        } catch (_: SecurityException) {
            // PdfRenderer ne sait pas lire les PDF chiffres : PdfBox-Android en fait une copie dechiffree.
            openEncrypted(file, password)
        }
    }

    override suspend fun edit(src: String, password: String?, dst: String, block: (EditableDoc) -> Unit) =
        withContext(Dispatchers.IO) {
            try {
                editWithPdfBoxAndroid(src, password, dst, block)
            } catch (_: InvalidPasswordException) {
                throw PasswordRequiredException(wrongPassword = password != null)
            }
        }

    override suspend fun create(dst: String, block: (EditableDoc) -> Unit) =
        withContext(Dispatchers.IO) { createWithPdfBoxAndroid(dst, block) }

    private fun openEncrypted(file: File, password: String?): PdfDocument {
        val decrypted = File(appContext.cacheDir, "dechiffre-${System.nanoTime()}.pdf")
        try {
            PDDocument.load(file, password ?: "").use { doc ->
                doc.isAllSecurityToBeRemoved = true
                doc.save(decrypted)
            }
        } catch (_: InvalidPasswordException) {
            throw PasswordRequiredException(wrongPassword = password != null)
        }
        return try {
            AndroidPdfDocument(decrypted, textFile = decrypted, temporary = decrypted)
        } catch (e: Throwable) {
            decrypted.delete()
            throw e
        }
    }
}

private class AndroidPdfDocument(
    renderFile: File,
    private val textFile: File,
    /** Copie dechiffree a effacer a la fermeture. */
    private val temporary: File?,
) : PdfDocument {
    private val renderMutex = Mutex()
    private val textMutex = Mutex()
    private val renderer: PdfRenderer
    private var textDoc: PDDocument? = null

    override val pageCount: Int
    override val pageSizes: List<PageSize>

    init {
        val fd = ParcelFileDescriptor.open(renderFile, ParcelFileDescriptor.MODE_READ_ONLY)
        renderer = try {
            PdfRenderer(fd)
        } catch (e: Throwable) {
            fd.close()
            throw e
        }
        pageCount = renderer.pageCount
        pageSizes = List(pageCount) { i ->
            renderer.openPage(i).use { PageSize(it.width.toFloat(), it.height.toFloat()) }
        }
    }

    override suspend fun renderPage(index: Int, widthPx: Int): ImageBitmap = withContext(Dispatchers.IO) {
        renderMutex.withLock {
            renderer.openPage(index).use { page ->
                val height = (widthPx * page.height / page.width.toFloat()).roundToInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap.asImageBitmap()
            }
        }
    }

    override suspend fun renderPageJpeg(index: Int, widthPx: Int): ByteArray = withContext(Dispatchers.IO) {
        renderMutex.withLock {
            renderer.openPage(index).use { page ->
                val height = (widthPx * page.height / page.width.toFloat()).roundToInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
                    .also { bitmap.recycle() }
            }
        }
    }

    override suspend fun pageText(index: Int): PageText = withContext(Dispatchers.IO) {
        textMutex.withLock {
            val doc = textDoc ?: PDDocument.load(textFile).also { textDoc = it }
            val page = doc.getPage(index)
            val box = page.cropBox
            val (w, h) = if (page.rotation % 180 != 0) box.height to box.width else box.width to box.height
            val stripper = GlyphStripper(w, h)
            stripper.sortByPosition = true
            stripper.startPage = index + 1
            stripper.endPage = index + 1
            stripper.getText(doc)
            stripper.builder.build()
        }
    }

    override suspend fun textBlocks(index: Int): List<TextBlock> = withContext(Dispatchers.IO) {
        textMutex.withLock {
            val doc = textDoc ?: PDDocument.load(textFile).also { textDoc = it }
            pageTextBlocksAndroid(doc, index)
        }
    }

    override suspend fun formFields(index: Int): List<FormField> = withContext(Dispatchers.IO) {
        textMutex.withLock {
            runCatching {
                val doc = textDoc ?: PDDocument.load(textFile).also { textDoc = it }
                pageFormFieldsAndroid(doc, index)
            }.getOrDefault(emptyList())
        }
    }

    override suspend fun hasForm(): Boolean = withContext(Dispatchers.IO) {
        textMutex.withLock {
            runCatching {
                val doc = textDoc ?: PDDocument.load(textFile).also { textDoc = it }
                documentHasFormAndroid(doc)
            }.getOrDefault(false)
        }
    }

    override fun close() {
        thread(name = "pdf-close", isDaemon = true) {
            runBlocking {
                renderMutex.withLock { runCatching { renderer.close() } }
                textMutex.withLock { runCatching { textDoc?.close() } }
            }
            temporary?.delete()
        }
    }
}

/** Recupere chaque lettre avec sa position sur la page (meme logique que sur ordinateur). */
private class GlyphStripper(private val pageWidth: Float, private val pageHeight: Float) : PDFTextStripper() {
    val builder = PageTextBuilder()

    override fun writeString(text: String, textPositions: List<TextPosition>) {
        for (tp in textPositions) {
            val x = tp.xDirAdj
            val y = tp.yDirAdj
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
