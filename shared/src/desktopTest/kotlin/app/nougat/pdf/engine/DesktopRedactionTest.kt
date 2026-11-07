package app.nougat.pdf.engine

import kotlinx.coroutines.test.runTest
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Cacher pour de bon : le rectangle « Masquer » et « Finaliser pour envoyer ». */
class DesktopRedactionTest {
    private val dir = createTempDirectory("nougat-redaction").toFile()
    private val engine = DesktopPdfEngine()
    private val helvetica = PDType1Font(Standard14Fonts.FontName.HELVETICA)

    /** Deux pages : un nom et un prix sur la meme ligne, puis un texte. */
    private fun sample(): String {
        val file = File(dir, "devis.pdf")
        PDDocument().use { doc ->
            repeat(2) { i ->
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(helvetica, 12f)
                    cs.newLineAtOffset(72f, 700f)
                    cs.showText(if (i == 0) "Client Jean Dupont CHF 1250.00" else "Conditions generales")
                    cs.endText()
                }
            }
            doc.documentInformation.author = "Jean Dupont"
            doc.save(file)
        }
        return file.path
    }

    private fun text(path: String) = Loader.loadPDF(File(path)).use { PDFTextStripper().getText(it) }

    @Test
    fun whiteoutBoxRemovesTheTextUnderIt() = runTest {
        val path = sample()
        // Le rectangle couvre seulement le prix, a droite de la ligne.
        val price = engine.open(path).use { doc -> TextSearch.find(0, doc.pageText(0), "CHF 1250.00").single().rects.single() }
        val box = BoxMark(0, NormRect(price.left - 0.005f, price.top - 0.005f, price.right + 0.005f, price.bottom + 0.005f), 0xFFFFFFFF.toInt())
        val out = File(dir, "masque.pdf").path
        engine.edit(path, null, out) { it.burn(listOf(box)) }
        val result = text(out)
        assertFalse("1250" in result, result)
        assertFalse("CHF" in result, result)
        assertTrue("Client Jean Dupont" in result, result)
    }

    @Test
    fun finalizedCopyHasNoTextLeft() = runTest {
        val path = sample()
        val out = File(dir, "finalise.pdf").path
        engine.edit(path, null, out) { it.flatten(110f) }
        Loader.loadPDF(File(out)).use { doc ->
            assertEquals(2, doc.numberOfPages)
            assertEquals("", PDFTextStripper().getText(doc).trim())
            assertEquals(null, doc.documentInformation.author)
            // La page garde sa taille (A4) ; chaque page est une image differente.
            assertEquals(595f, doc.getPage(0).mediaBox.width, 1.5f)
            assertEquals(842f, doc.getPage(0).mediaBox.height, 1.5f)
        }
        val images = engine.open(out).use { doc -> (0 until 2).map { doc.renderPage(it, 200) } }
        assertTrue(images.size == 2)
    }
}
