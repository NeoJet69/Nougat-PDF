package app.nougat.pdf.engine

import kotlinx.coroutines.test.runTest
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopPdfEngineTest {
    private val dir = createTempDirectory("nougat").toFile()

    /** Un PDF de deux pages A4 : « Bonjour Nougat » puis « Le chat dort ». */
    private fun samplePdf(password: String? = null): File {
        val file = File(dir, "exemple.pdf")
        PDDocument().use { doc ->
            for (line in listOf("Bonjour Nougat", "Le chat dort")) {
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 24f)
                    cs.newLineAtOffset(72f, 700f)
                    cs.showText(line)
                    cs.endText()
                }
            }
            if (password != null) {
                doc.protect(StandardProtectionPolicy(password, password, AccessPermission()).apply { encryptionKeyLength = 128 })
            }
            doc.save(file)
        }
        return file
    }

    @Test
    fun opensAndMeasuresPages() = runTest {
        DesktopPdfEngine().open(samplePdf().path).use { doc ->
            assertEquals(2, doc.pageCount)
            assertEquals(PDRectangle.A4.width, doc.pageSizes[0].width, 0.5f)
            assertEquals(PDRectangle.A4.height, doc.pageSizes[0].height, 0.5f)
        }
    }

    @Test
    fun rendersAtRequestedWidth() = runTest {
        DesktopPdfEngine().open(samplePdf().path).use { doc ->
            val image = doc.renderPage(0, 600)
            // A un pixel pres (arrondis de PDFBox) ; A4 : hauteur = largeur x 1,414.
            assertTrue(image.width in 599..600, "largeur : ${image.width}")
            assertTrue(image.height in 847..849, "hauteur : ${image.height}")
        }
    }

    @Test
    fun findsTextWithItsPosition() = runTest {
        DesktopPdfEngine().open(samplePdf().path).use { doc ->
            val hits = TextSearch.find(1, doc.pageText(1), "chat")
            val rect = hits.single().rects.single()
            // Le texte commence a 72 pt du bord gauche, et sa ligne de base est a 700 pt du bas.
            assertTrue(rect.left > 72f / 595f && rect.left < 0.3f, "gauche : ${rect.left}")
            val baselineFromTop = (842f - 700f) / 842f
            assertTrue(rect.top < baselineFromTop && rect.bottom > baselineFromTop, "verticale : $rect")
            assertTrue(TextSearch.find(0, doc.pageText(0), "nougat").isNotEmpty())
        }
    }

    @Test
    fun asksForPassword() = runTest {
        val file = samplePdf(password = "miaou")
        val engine = DesktopPdfEngine()
        assertEquals(false, assertFailsWith<PasswordRequiredException> { engine.open(file.path) }.wrongPassword)
        assertEquals(true, assertFailsWith<PasswordRequiredException> { engine.open(file.path, "wouf") }.wrongPassword)
        engine.open(file.path, "miaou").use { assertEquals(2, it.pageCount) }
    }
}
