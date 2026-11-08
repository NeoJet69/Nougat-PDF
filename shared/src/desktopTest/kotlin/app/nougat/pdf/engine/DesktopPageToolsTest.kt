package app.nougat.pdf.engine

import kotlinx.coroutines.test.runTest
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.util.Matrix
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Rogner, redresser, compresser, numeroter. */
class DesktopPageToolsTest {
    private val dir = createTempDirectory("nougat-pages").toFile()
    private val engine = DesktopPdfEngine()
    private val helvetica = PDType1Font(Standard14Fonts.FontName.HELVETICA)

    /** Une page de texte, penchee de [degrees] (les lignes descendent vers la droite). */
    private fun textPage(name: String, degrees: Float = 0f, pages: Int = 1): String {
        val file = File(dir, name)
        PDDocument().use { doc ->
            repeat(pages) {
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.transform(Matrix.getRotateInstance(Math.toRadians(-degrees.toDouble()), 297f, 421f))
                    cs.transform(Matrix.getTranslateInstance(-297f, -421f))
                    var y = 600f
                    repeat(18) {
                        cs.beginText(); cs.setFont(helvetica, 12f); cs.newLineAtOffset(150f, y)
                        cs.showText("Ligne de texte numero $it, assez longue pour le profil de projection.")
                        cs.endText()
                        y -= 18f
                    }
                }
            }
            doc.save(file)
        }
        return file.path
    }

    private fun skew(path: String): Float = Loader.loadPDF(File(path)).use { doc ->
        PageTools.skewDegrees(PDFRenderer(doc).renderImageWithDPI(0, 100f, ImageType.GRAY).toGrayImage())
    }

    @Test
    fun aTiltedPageIsStraightened() = runTest {
        val path = textPage("penchee.pdf", degrees = 2f)
        val before = skew(path)
        assertTrue(abs(before - 2f) < 0.4f, "inclinaison mesuree : $before")
        val out = File(dir, "droite.pdf").path
        var count = 0
        engine.edit(path, null, out) { count = it.straighten(listOf(0)) }
        assertEquals(1, count)
        val after = skew(out)
        assertTrue(abs(after) < 0.3f, "toujours penchee : $after")
    }

    @Test
    fun marginsAreCropped() = runTest {
        val path = textPage("marges.pdf")
        val out = File(dir, "rognee.pdf").path
        engine.edit(path, null, out) { assertEquals(1, it.cropMargins(listOf(0))) }
        Loader.loadPDF(File(out)).use { doc ->
            val box = doc.getPage(0).cropBox
            assertTrue(box.width < 595f * 0.9f && box.height < 842f * 0.6f, "$box")
            // Le texte est toujours la, en entier.
            assertTrue("numero 17" in PDFTextStripper().getText(doc))
        }
    }

    @Test
    fun heavyImagesAreCompressed() = runTest {
        val file = File(dir, "photo.pdf")
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            // Une « photo » de 2400 x 3400 pixels (bruit colore : lourde en sans perte).
            val img = BufferedImage(2400, 3400, BufferedImage.TYPE_INT_RGB)
            val rnd = java.util.Random(1)
            for (y in 0 until img.height step 4) for (x in 0 until img.width step 4) {
                val c = Color(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)).rgb
                for (dy in 0 until 4) for (dx in 0 until 4) img.setRGB(x + dx, y + dy, c)
            }
            val image = LosslessFactory.createFromImage(doc, img)
            PDPageContentStream(doc, page).use { it.drawImage(image, 0f, 0f, 595f, 842f) }
            doc.save(file)
        }
        val out = File(dir, "photo-legere.pdf")
        engine.edit(file.path, null, out.path) { assertEquals(1, it.compressImages(CompressLevel.Medium)) }
        // Du bruit aleatoire est le pire cas pour le JPEG : on attend tout de meme moitie moins lourd.
        assertTrue(out.length() < file.length() * 0.6, "${file.length()} -> ${out.length()}")
    }

    @Test
    fun pageNumbersAndDate() = runTest {
        val path = textPage("numeros.pdf", pages = 3)
        val out = File(dir, "numerotee.pdf").path
        engine.edit(path, null, out) { it.stampPages(PageStamp(format = NumberFormat.PageOf, skipFirstPage = true, date = "08.11.2026")) }
        Loader.loadPDF(File(out)).use { doc ->
            val stripper = PDFTextStripper()
            stripper.startPage = 2; stripper.endPage = 2
            val second = stripper.getText(doc)
            assertTrue("Page 1 sur 2" in second, second)
            assertTrue("08.11.2026" in second, second)
            stripper.startPage = 1; stripper.endPage = 1
            assertTrue("Page" !in stripper.getText(doc).substringAfter("projection.").replace("projection", ""))
        }
    }
}
