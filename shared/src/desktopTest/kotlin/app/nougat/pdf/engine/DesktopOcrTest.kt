package app.nougat.pdf.engine

import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import java.awt.image.BufferedImage
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * L'OCR de bout en bout, sur un faux scan realiste (papier jauni, penche, bruite, page 2 a l'envers) :
 * il faut que le texte devienne cherchable, que la page a l'envers soit remise droite, qu'on puisse
 * recommencer sans doublon, s'arreter en route, et modifier une ligne scannee.
 */
class DesktopOcrTest {
    private val dir = createTempDirectory("nougat-ocr").toFile()
    private val engine = DesktopPdfEngine()
    private val french = OcrOptions(listOf("fra", "eng"))

    private fun scan(name: String = "scan.pdf") = makeSampleScan(File(dir, name))

    private fun recognize(src: String, dst: String, options: OcrOptions = french, progress: (Int, Int) -> Boolean = { _, _ -> true }): OcrSummary {
        var summary: OcrSummary? = null
        runBlocking { engine.edit(src, null, dst) { summary = it.recognizeText(options, progress) } }
        return summary!!
    }

    private fun text(path: String, page: Int? = null): String = Loader.loadPDF(File(path)).use { doc ->
        PDFTextStripper().apply { if (page != null) { startPage = page + 1; endPage = page + 1 } }.getText(doc)
    }

    /** Ecart moyen (0..255) entre deux rendus, dans une zone donnee en fractions de la page. */
    private fun difference(a: BufferedImage, b: BufferedImage, r: NormRect): Double {
        val x0 = (r.left * a.width).toInt().coerceIn(0, a.width - 1)
        val x1 = (r.right * a.width).toInt().coerceIn(x0 + 1, a.width)
        val y0 = (r.top * a.height).toInt().coerceIn(0, a.height - 1)
        val y1 = (r.bottom * a.height).toInt().coerceIn(y0 + 1, a.height)
        var sum = 0.0
        for (y in y0 until y1) for (x in x0 until x1) sum += abs((a.getRGB(x, y) and 0xFF) - (b.getRGB(x, y) and 0xFF))
        return sum / ((x1 - x0) * (y1 - y0))
    }

    private fun render(path: String): BufferedImage = Loader.loadPDF(File(path)).use { PDFRenderer(it).renderImageWithDPI(0, 100f, ImageType.GRAY) }

    @Test
    fun theModelsAreThere() {
        val langs = engine.ocrLanguages
        for (code in OCR_LANGUAGES.map { it.first }) assertTrue(code in langs, "modele $code absent : $langs")
        assertEquals(listOf("fra", "eng"), defaultOcrLanguages("fr"))
        assertEquals(listOf("deu", "eng"), defaultOcrLanguages("gsw"))
        assertEquals(listOf("eng"), defaultOcrLanguages("en"))
    }

    @Test
    fun recognizesAScanAndStraightensIt() {
        val src = scan()
        runBlocking { engine.open(src).use { assertEquals(2, it.pagesNeedingOcr()) } }
        assertEquals("", text(src).trim())

        val dst = File(dir, "lu.pdf").absolutePath
        val summary = recognize(src, dst)
        assertEquals(2, summary.recognized)
        assertEquals(1, summary.rotated)
        assertFalse(summary.cancelled)
        assertTrue(summary.words > 60, "trop peu de mots : ${summary.words}")

        // Le texte, accents compris, est la et dans l'ordre de lecture.
        val page1 = text(dst, 0)
        for (line in SCAN_PAGE_1) assertTrue(line.text in page1, "« ${line.text} » absent de :\n$page1")
        val page2 = text(dst, 1)
        for (line in SCAN_PAGE_2) assertTrue(line.text in page2, "« ${line.text} » absent de :\n$page2")
        assertTrue(page1.indexOf("Atelier") < page1.indexOf("Lausanne") && page1.indexOf("Lausanne") < page1.indexOf("salutations"))

        // La page 2 etait a l'envers : elle est remise droite (rotation d'affichage, l'image ne change pas).
        Loader.loadPDF(File(dst)).use { doc ->
            assertEquals(0, doc.getPage(0).rotation)
            assertEquals(180, doc.getPage(1).rotation)
        }

        runBlocking {
            engine.open(dst).use { doc ->
                assertEquals(0, doc.pagesNeedingOcr())
                // La recherche trouve le mot a sa place sur l'image (ligne 2, a gauche).
                val hit = TextSearch.find(0, doc.pageText(0), "Lausanne").single().rects.first()
                val expectedTop = 1f - (735f + 12f * 0.75f) / 842f
                assertTrue(abs(hit.top - expectedTop) < 0.025f, "Lausanne trop loin : $hit")
                assertTrue(hit.left > 0.25f && hit.left < 0.5f, "Lausanne trop loin : $hit")
                // Chaque ligne du scan forme un seul bloc, comme une vraie ligne de texte.
                val blocks = doc.textBlocks(0)
                assertTrue(blocks.all { it.scanned })
                assertEquals("Devis numéro 2026-117", blocks.single { it.text.startsWith("Devis") }.text)
                assertEquals(16f, blocks.single { it.text.startsWith("Devis") }.fontSize, 2.5f)
            }
        }
        // A l'oeil, la page n'a pas change : le texte est invisible.
        assertTrue(difference(render(src), render(dst), NormRect(0f, 0f, 1f, 1f)) < 0.5)
    }

    @Test
    fun readsAPhotoWithAShadow() {
        // Sans la correction de l'eclairage, Tesseract n'y lit rien du tout.
        val dst = File(dir, "photo-lue.pdf").absolutePath
        assertEquals(1, recognize(makeSamplePhoto(File(dir, "photo.pdf")), dst).recognized)
        val found = text(dst).split(Regex("\\s+")).toSet()
        val words = SCAN_PAGE_1.flatMap { it.text.split(" ") }
        val read = words.count { it in found }
        assertTrue(read >= words.size * 0.9, "$read mots lus sur ${words.size} :\n${text(dst)}")
    }

    @Test
    fun againDoesNothingAndRedoReplaces() {
        val once = File(dir, "une-fois.pdf").absolutePath
        recognize(scan(), once)
        val twice = File(dir, "deux-fois.pdf").absolutePath
        val again = recognize(once, twice)
        assertEquals(0, again.recognized)
        assertEquals(2, again.skipped)

        // « Refaire » remplace la couche de texte de Nougat au lieu d'en ajouter une deuxieme.
        val redone = File(dir, "refait.pdf").absolutePath
        val redo = recognize(once, redone, french.copy(redo = true, pages = listOf(0)))
        assertEquals(1, redo.recognized)
        assertEquals(1, Regex("Lausanne").findAll(text(redone, 0)).count())
        assertTrue("Lausanne" in text(redone, 0))
    }

    @Test
    fun onlyTheChosenPageAndStop() {
        val src = scan()
        val one = File(dir, "page2.pdf").absolutePath
        val summary = recognize(src, one, french.copy(pages = listOf(1)))
        assertEquals(1, summary.recognized)
        assertEquals("", text(one, 0).trim())
        assertTrue("Conditions" in text(one, 1))

        // Arreter apres la premiere page : elle est gardee, l'autre reste a faire.
        val stopped = File(dir, "arrete.pdf").absolutePath
        val partial = recognize(src, stopped) { _, _ -> false }
        assertTrue(partial.cancelled)
        assertEquals(1, partial.recognized)
        runBlocking { engine.open(stopped).use { assertEquals(1, it.pagesNeedingOcr()) } }
    }

    @Test
    fun pagesWithRealTextAreLeftAlone() {
        val src = makeSampleForm(File(dir, "form.pdf"))
        val before = text(src)
        val dst = File(dir, "form-ocr.pdf").absolutePath
        val summary = recognize(src, dst)
        assertEquals(0, summary.recognized)
        assertEquals(1, summary.skipped)
        assertEquals(before, text(dst))
    }

    @Test
    fun editsAScannedLine() {
        val read = File(dir, "lu.pdf").absolutePath
        recognize(scan(), read)
        val block = runBlocking { engine.open(read).use { doc -> doc.textBlocks(0).single { it.text.startsWith("Madame") } } }
        assertTrue(block.scanned)
        assertTrue(block.editable)

        val dst = File(dir, "modifie.pdf").absolutePath
        var result: TextEditResult? = null
        runBlocking { engine.edit(read, null, dst) { result = it.replaceText(block, "Monsieur Nougat Lechat") } }
        assertEquals(TextEditResult.ScanText, result)

        val page = text(dst, 0)
        assertTrue("Monsieur Nougat Lechat" in page, page)
        assertFalse("Madame" in page, page)
        // Le reste du texte reconnu est intact.
        assertTrue("Avenue de la Gare 15" in page && "Lausanne" in page, page)

        // L'ancienne ligne est effacee et reecrite ; les lignes voisines n'ont pas bouge d'un pixel.
        val before = render(read)
        val after = render(dst)
        assertTrue(difference(before, after, block.rect) > 8.0, "la ligne n'a pas change")
        val neighbour = runBlocking { engine.open(read).use { doc -> doc.textBlocks(0).single { it.text.startsWith("Avenue") } } }
        assertTrue(difference(before, after, neighbour.rect) < 1.0, "la ligne voisine a change")

        // La nouvelle ligne est elle-meme modifiable (texte normal cette fois).
        val again = runBlocking { engine.open(dst).use { doc -> doc.textBlocks(0).single { it.text.startsWith("Monsieur") } } }
        assertFalse(again.scanned)
        assertEquals(12f, again.fontSize, 2f)
    }
}
