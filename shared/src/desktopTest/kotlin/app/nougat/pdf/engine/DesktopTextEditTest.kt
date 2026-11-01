package app.nougat.pdf.engine

import kotlinx.coroutines.test.runTest
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopTextEditTest {
    private val dir = createTempDirectory("nougat-text").toFile()
    private val engine = DesktopPdfEngine()
    private val helvetica = PDType1Font(Standard14Fonts.FontName.HELVETICA)
    private val helveticaBold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)

    private fun pdf(name: String, rotation: Int = 0, draw: (PDDocument, PDPageContentStream) -> Unit): String {
        val file = File(dir, name)
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            page.rotation = rotation
            doc.addPage(page)
            PDPageContentStream(doc, page).use { draw(doc, it) }
            doc.save(file)
        }
        return file.path
    }

    private fun PDPageContentStream.line(font: PDFont, size: Float, x: Float, y: Float, text: String) {
        beginText()
        setFont(font, size)
        newLineAtOffset(x, y)
        showText(text)
        endText()
    }

    private fun text(path: String): String = Loader.loadPDF(File(path)).use { PDFTextStripper().getText(it) }

    private suspend fun blocks(path: String) = engine.open(path).use { it.textBlocks(0) }

    private suspend fun hitRect(path: String, word: String): NormRect =
        engine.open(path).use { doc -> TextSearch.find(0, doc.pageText(0), word).first().rects.first() }

    /** Remplace la ligne qui contient [old] par [new] ; renvoie le fichier modifie et le mode utilise. */
    private suspend fun replace(path: String, old: String, new: String): Pair<String, TextEditResult> {
        val block = blocks(path).first { old in it.text }
        val out = File(dir, "edit-${System.nanoTime()}.pdf").path
        var result: TextEditResult? = null
        engine.edit(path, null, out) { result = it.replaceText(block, new) }
        return out to result!!
    }

    @Test
    fun findsOneBlockPerLine() = runTest {
        val path = pdf("lignes.pdf") { _, cs ->
            cs.line(helvetica, 14f, 72f, 700f, "Bonjour Nougat")
            cs.line(helvetica, 14f, 72f, 670f, "Le chat dort")
        }
        val found = blocks(path)
        assertEquals(listOf("Bonjour Nougat", "Le chat dort"), found.map { it.text })
        assertTrue(found.all { it.editable })
    }

    @Test
    fun replacesALineWithTheSameFont() = runTest {
        val path = pdf("simple.pdf") { _, cs ->
            cs.line(helvetica, 14f, 72f, 700f, "Bonjour Nougat")
            cs.line(helvetica, 14f, 72f, 670f, "Le chat dort")
        }
        val before = hitRect(path, "chat")
        val (out, mode) = replace(path, "Bonjour", "Bonsoir Caramel")
        assertEquals(TextEditResult.SameFont, mode)
        val result = text(out)
        assertTrue("Bonsoir Caramel" in result, result)
        assertFalse("Nougat" in result, result)
        // L'autre ligne n'a pas bouge.
        assertEquals(before, hitRect(out, "chat"))
        // Le nouveau texte commence la ou commencait l'ancien.
        assertEquals(hitRect(path, "Bonjour").left, hitRect(out, "Bonsoir").left, 0.002f)
    }

    @Test
    fun keepsTheRestOfALineInPlaceWithKerning() = runTest {
        // Un seul tableau TJ avec des decalages (crenage), suivi d'un autre texte sur la meme ligne.
        val path = pdf("crenage.pdf") { _, cs ->
            cs.beginText()
            cs.setFont(helvetica, 14f)
            cs.newLineAtOffset(72f, 700f)
            cs.showTextWithPositioning(arrayOf("Fac", -40f, "ture", 25f, " 2023"))
            cs.endText()
            cs.line(helveticaBold, 14f, 300f, 700f, "Total : 120")
        }
        val totalBefore = hitRect(path, "Total")
        val (out, _) = replace(path, "Facture", "Facture 2024")
        val result = text(out)
        assertTrue("Facture 2024" in result, result)
        assertFalse("2023" in result, result)
        assertEquals(totalBefore, hitRect(out, "Total"))
    }

    @Test
    fun editsOnlyTheRunWithTheSameStyle() = runTest {
        val path = pdf("styles.pdf") { _, cs ->
            cs.line(helvetica, 12f, 72f, 700f, "Montant :")
            cs.line(helveticaBold, 12f, 135f, 700f, "120 EUR")
        }
        val found = blocks(path)
        assertEquals(listOf("Montant :", "120 EUR"), found.map { it.text })
        val (out, mode) = replace(path, "120", "135 EUR")
        assertEquals(TextEditResult.SameFont, mode)
        val result = text(out)
        assertTrue("Montant :" in result && "135 EUR" in result && "120" !in result, result)
    }

    @Test
    fun deletesALine() = runTest {
        val path = pdf("supprimer.pdf") { _, cs ->
            cs.line(helvetica, 14f, 72f, 700f, "A effacer")
            cs.line(helvetica, 14f, 72f, 670f, "A garder")
        }
        val (out, _) = replace(path, "effacer", "")
        val result = text(out)
        assertFalse("effacer" in result, result)
        assertTrue("A garder" in result, result)
    }

    @Test
    fun worksOnRotatedPages() = runTest {
        val path = pdf("pivote.pdf", rotation = 90) { _, cs ->
            cs.line(helvetica, 14f, 72f, 700f, "Bonjour Nougat")
        }
        val (out, _) = replace(path, "Nougat", "Bonjour Caramel")
        assertTrue("Bonjour Caramel" in text(out))
    }

    @Test
    fun usesASimilarFontWhenTheEmbeddedSubsetLacksLetters() = runTest {
        val ttf = listOf(
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "C:/Windows/Fonts/arial.ttf",
            "/Library/Fonts/Arial.ttf",
        ).map(::File).firstOrNull { it.isFile } ?: return@runTest // pas de police TrueType sur cette machine
        val path = pdf("sous-ensemble.pdf") { doc, cs ->
            val font = PDType0Font.load(doc, ttf) // embarquee partiellement : seulement les lettres utilisees
            cs.line(font, 14f, 72f, 700f, "Prix 120")
        }
        // Les chiffres 1, 2 et 0 existent dans la police embarquee : meme police.
        val (same, sameMode) = replace(path, "Prix", "Prix 210")
        assertEquals(TextEditResult.SameFont, sameMode)
        assertTrue("Prix 210" in text(same), text(same))
        // Le 9 n'y est pas : police standard proche.
        val (other, otherMode) = replace(path, "Prix", "Prix 999")
        assertEquals(TextEditResult.SimilarFont, otherMode)
        assertTrue("Prix 999" in text(other), text(other))
    }

    @Test
    fun refusesTextInsideAForm() = runTest {
        val file = File(dir, "formulaire.pdf")
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            val form = PDFormXObject(doc).apply {
                bBox = PDRectangle.A4
                resources = PDResources()
            }
            PDPageContentStream(doc, form, form.stream.createOutputStream()).use { cs ->
                cs.line(helvetica, 14f, 72f, 700f, "Dans un formulaire")
            }
            PDPageContentStream(doc, page).use { it.drawForm(form) }
            doc.save(file)
        }
        val block = assertNotNull(blocks(file.path).firstOrNull())
        assertFalse(block.editable)
        assertFailsWith<TextEditException> {
            engine.edit(file.path, null, File(dir, "x.pdf").path) { it.replaceText(block, "Nouveau") }
        }
    }

    @Test
    fun editsTwiceInARow() = runTest {
        val path = pdf("deux-fois.pdf") { _, cs ->
            cs.line(helvetica, 14f, 72f, 700f, "Version 1")
        }
        val (first, _) = replace(path, "Version", "Version 2")
        val (second, _) = replace(first, "Version", "Version 3")
        assertTrue("Version 3" in text(second), text(second))
    }
}
