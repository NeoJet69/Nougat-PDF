package app.nougat.pdf.engine

import kotlinx.coroutines.test.runTest
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Deplacer et agrandir des lignes choisies en mode Editer. */
class DesktopBlockMoveTest {
    private val dir = createTempDirectory("nougat-move").toFile()
    private val engine = DesktopPdfEngine()

    private fun sample(): String {
        val file = File(dir, "lettre.pdf")
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                cs.newLineAtOffset(72f, 700f)
                cs.showText("Montant total CHF 450.00")
                cs.newLineAtOffset(0f, -40f)
                cs.showText("Merci pour votre confiance")
                cs.endText()
            }
            doc.save(file)
        }
        return file.path
    }

    @Test
    fun movedLineIsAtItsNewPlaceAndStillSearchable() = runTest {
        val src = sample()
        val before = engine.open(src).use { it.textBlocks(0) }
        val line = before.first { "Montant" in it.text }
        val out = File(dir, "deplace.pdf").path
        engine.edit(src, null, out) { it.moveTextBlocks(0, listOf(line), 0.2f, 0.1f, 1.5f) }

        val after = engine.open(out).use { it.textBlocks(0) }
        val moved = after.filter { "Montant" in it.text }
        assertEquals(1, moved.size, "la ligne n'est plus qu'a un seul endroit : $after")
        val r = moved.single().rect
        assertTrue(r.left > line.rect.left + 0.15f && r.top > line.rect.top + 0.05f, "nouvelle place : $r (avant ${line.rect})")
        assertTrue(r.right - r.left > (line.rect.right - line.rect.left) * 1.3f, "plus grande : $r")
        assertTrue(after.any { "Merci" in it.text && it.rect == before.first { b -> "Merci" in b.text }.rect }, "l'autre ligne ne bouge pas")
    }
}
