package app.nougat.pdf.engine

import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopCreateTest {
    private val dir = createTempDirectory("nougat-create").toFile()
    private val engine = DesktopPdfEngine()

    private fun image(name: String, w: Int, h: Int, format: String): String {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color.WHITE
        g.fillRect(0, 0, w, h)
        g.color = Color(0xCC, 0x54, 0x2C)
        g.fillRect(w / 10, h / 10, w / 2, h / 4)
        g.dispose()
        val f = File(dir, "$name.$format")
        ImageIO.write(img, format, f)
        return f.absolutePath
    }

    @Test
    fun onePagePerImageInTheRightOrientation() {
        val png = image("portrait", 600, 900, "png")
        val jpg = image("paysage", 1200, 800, "jpg")
        val dst = File(dir, "images.pdf").absolutePath
        runBlocking { engine.create(dst) { it.appendImages(listOf(png, jpg)) } }
        Loader.loadPDF(File(dst)).use { doc ->
            assertEquals(2, doc.numberOfPages)
            val first = doc.getPage(0).mediaBox
            val second = doc.getPage(1).mediaBox
            assertEquals(A4_WIDTH, first.width, 0.5f)
            assertTrue(first.height > first.width)
            assertTrue(second.width > second.height)
        }
        // Le PDF cree s'ouvre et s'affiche
        runBlocking { engine.open(dst) }.use { assertEquals(2, it.pageCount) }
        // La photo n'a pas ete touchee
        assertTrue(File(png).isFile && File(jpg).isFile)
    }

    @Test
    fun blankDocumentAndImagesAddedLater() {
        val blank = File(dir, "vierge.pdf").absolutePath
        runBlocking { engine.create(blank) { it.insertBlankPage(0, A4_WIDTH, A4_HEIGHT) } }
        Loader.loadPDF(File(blank)).use { assertEquals(1, it.numberOfPages) }

        val more = File(dir, "plus.pdf").absolutePath
        runBlocking { engine.edit(blank, null, more) { it.appendImages(listOf(image("photo", 800, 600, "jpg"))) } }
        Loader.loadPDF(File(more)).use { assertEquals(2, it.numberOfPages) }
    }

    @Test
    fun imageFitsAndIsCentered() {
        val l = imagePageLayout(1200, 800)
        assertEquals(A4_HEIGHT, l[0], 0.01f) // paysage
        assertEquals(A4_WIDTH, l[1], 0.01f)
        assertTrue(l[4] <= l[0] + 0.01f && l[5] <= l[1] + 0.01f)
        assertEquals((l[0] - l[4]) / 2f, l[2], 0.01f)
        assertEquals((l[1] - l[5]) / 2f, l[3], 0.01f)
    }
}
