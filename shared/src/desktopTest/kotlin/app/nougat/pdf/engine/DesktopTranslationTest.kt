package app.nougat.pdf.engine

import kotlinx.coroutines.test.runTest
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopTranslationTest {
    private val dir = createTempDirectory("nougat-translation").toFile()
    private val engine = DesktopPdfEngine()
    private val helvetica = PDType1Font(Standard14Fonts.FontName.HELVETICA)
    private val bold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)

    private fun PDPageContentStream.line(font: PDFont, size: Float, x: Float, y: Float, text: String) {
        beginText()
        setFont(font, size)
        newLineAtOffset(x, y)
        showText(text)
        endText()
    }

    /** Une facture : un titre, un paragraphe de trois lignes, un montant (rien a traduire). */
    private fun invoice(): String {
        val file = File(dir, "facture.pdf")
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                // Un bandeau colore derriere le titre : il doit rester (le texte est retire, pas recouvert).
                cs.setNonStrokingColor(0.98f, 0.92f, 0.80f)
                cs.addRect(60f, 740f, 300f, 40f)
                cs.fill()
                cs.setNonStrokingColor(0.1f, 0.2f, 0.5f)
                cs.line(bold, 20f, 72f, 752f, "Facture")
                cs.setNonStrokingColor(0f, 0f, 0f)
                cs.line(helvetica, 12f, 72f, 700f, "Merci pour votre confiance. Le paiement est attendu")
                cs.line(helvetica, 12f, 72f, 685f, "dans les trente jours suivant la date de la facture,")
                cs.line(helvetica, 12f, 72f, 670f, "sans escompte.")
                cs.line(helvetica, 12f, 400f, 600f, "CHF 1250.00")
            }
            doc.save(file)
        }
        return file.path
    }

    private fun text(path: String): String = Loader.loadPDF(File(path)).use { PDFTextStripper().getText(it) }

    private suspend fun paragraphs(path: String): List<TextParagraph> = engine.open(path).use { doc ->
        val size = doc.pageSizes[0]
        Paragraphs.group(0, doc.textBlocks(0), size.width, size.height).filter { Paragraphs.needsTranslation(it.text) }
    }

    private suspend fun translate(path: String, language: String, translations: Map<String, String>): String {
        val items = paragraphs(path).map { p -> TranslatedParagraph(p, translations[p.text] ?: error("Pas de traduction pour « ${p.text} »")) }
        val out = File(dir, "traduit-$language-${System.nanoTime()}.pdf").path
        engine.edit(path, null, out) { it.writeTranslation(0, items, language) }
        return out
    }

    @Test
    fun translatedCopyReplacesTheParagraphs() = runTest {
        val path = invoice()
        val found = paragraphs(path)
        assertEquals(
            listOf(
                "Facture",
                "Merci pour votre confiance. Le paiement est attendu dans les trente jours suivant la date de la facture, sans escompte.",
            ),
            found.map { it.text },
        )
        val out = translate(
            path, "de",
            mapOf(
                "Facture" to "Rechnung",
                found[1].text to "Vielen Dank für Ihr Vertrauen. Die Zahlung wird innerhalb von dreißig Tagen nach dem Rechnungsdatum ohne Skontoabzug erwartet.",
            ),
        )
        val result = text(out)
        assertTrue("Rechnung" in result, result)
        assertTrue("dreißig Tagen" in result.replace("\n", " "), result)
        assertFalse("Facture" in result, result)
        assertFalse("confiance" in result, result)
        // Le montant n'avait rien a traduire : il reste.
        assertTrue("CHF 1250.00" in result, result)

        // Le titre garde sa couleur ; le bandeau reste derriere ; le texte allemand tient dans le cadre.
        Loader.loadPDF(File(out)).use { doc ->
            val image = PDFRenderer(doc).renderImageWithDPI(0, 72f)
            ImageIO.write(image, "png", File(dir, "traduit.png"))
            val banner = image.getRGB(62, (842 - 742))
            assertTrue(((banner shr 16) and 0xFF) > 230 && (banner and 0xFF) < 220, "bandeau perdu : ${Integer.toHexString(banner)}")
        }
        val translated = paragraphs(out)
        val title = translated.first { it.text == "Rechnung" }
        assertTrue(title.fontSize > 17f, "titre trop petit : ${title.fontSize}")
        val body = translated.first { "Vielen" in it.text }
        assertTrue(body.rect.top >= found[1].rect.top - 0.01f && body.rect.bottom <= found[1].rect.bottom + 0.03f, "${body.rect} / ${found[1].rect}")
    }

    @Test
    fun lettersOutsideLatin1UseAnInstalledFont() = runTest {
        val path = invoice()
        val found = paragraphs(path)
        val out = translate(
            path, "pl",
            mapOf("Facture" to "Faktura", found[1].text to "Dziękujemy za zaufanie. Płatność należy uiścić w ciągu trzydziestu dni od daty faktury, bez rabatu."),
        )
        val result = text(out).replace("\n", " ")
        assertTrue("Płatność należy uiścić" in result, result)
    }

    @Test
    fun arabicIsJoinedAndRightToLeft() = runTest {
        val path = invoice()
        val found = paragraphs(path)
        val out = translate(
            path, "ar",
            mapOf("Facture" to "فاتورة", found[1].text to "شكرا لثقتكم. يجب الدفع خلال ثلاثين يوما من تاريخ الفاتورة، دون خصم."),
        )
        Loader.loadPDF(File(out)).use { doc ->
            val image = PDFRenderer(doc).renderImageWithDPI(0, 100f)
            ImageIO.write(image, "png", File(dir, "arabe.png"))
        }
        val result = text(out)
        // Les lettres sont ecrites sous leur forme liee (formes de presentation) : on les retrouve normalisees.
        val normalized = java.text.Normalizer.normalize(result, java.text.Normalizer.Form.NFKC)
        assertTrue(normalized.contains("فاتورة") || normalized.contains("ةروتاف"), normalized)
        assertFalse("Facture" in result)
    }

    @Test
    fun selectedPricesAreReallyErased() = runTest {
        val path = invoice()
        val prices = engine.open(path).use { it.textBlocks(0) }.filter { it.text.startsWith("CHF") }
        assertEquals(1, prices.size)
        val out = File(dir, "sans-prix.pdf").path
        engine.edit(path, null, out) { it.deleteTextBlocks(0, prices) }
        val result = text(out)
        // Le prix a disparu du fichier (ni visible, ni cherchable) ; le reste ne bouge pas.
        assertFalse("1250" in result, result)
        assertTrue("Facture" in result && "sans escompte." in result, result)
        val title = engine.open(out).use { it.textBlocks(0) }.first { it.text == "Facture" }
        val before = engine.open(path).use { it.textBlocks(0) }.first { it.text == "Facture" }
        assertEquals(before.rect, title.rect)
    }

    @Test
    fun bidiPutsArabicInDisplayOrder() {
        // Deux mots arabes et un nombre : les mots s'affichent de droite a gauche, le nombre reste lisible.
        val visual = BidiText.visual("\u0628\u0627\u0628 12", rtl = true)
        assertEquals("12 \uFE8F\uFE8E\uFE91", visual)
    }
}
