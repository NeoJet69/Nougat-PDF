package app.nougat.pdf.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * La relecture par l'IA de l'appareil (Gemini Nano sur Android), avec un faux modele : il faut
 * garder les bonnes corrections, refuser les inventions, et laisser chaque mot a sa place.
 */
class OcrProofreadingTest {
    private fun word(text: String, conf: Float, left: Int, top: Int = 100, height: Int = 24) =
        OcrWord(text, conf, left, top, left + text.length * 12, top + height)

    private fun line(vararg words: OcrWord) = fixBaseline(OcrLine(words.toList(), 0f, 0f, 0f, 0f))

    private val blank = GrayImage(1200, 400, ByteArray(1200 * 400) { -1 })

    @Test
    fun baselineIsFoundWithoutTheEngine() {
        // Mots le long d'une ligne qui descend de 2 % (page un peu penchee), sans ligne de base fournie.
        val words = (0 until 6).map { i -> word("mot$i", 90f, 100 + i * 150, top = 200 + i * 3) }
        val l = fixBaseline(OcrLine(words, 0f, 0f, 0f, 0f))
        val slope = (l.y2 - l.y1) / (l.x2 - l.x1)
        assertEquals(0.02f, slope, 0.002f)
        assertEquals(100f, l.x1)
        // Les jambages (g, p, y...) ne faussent pas la ligne de base.
        val mixed = line(word("Bonjour", 90f, 100), word("gypsy", 90f, 200, height = 30), word("Martin", 90f, 300))
        assertEquals(124f, mixed.y1, 0.5f)
    }

    @Test
    fun onlyDoubtfulLinesAreRead() {
        val good = line(word("Avenue", 96f, 100), word("de", 95f, 190), word("la", 97f, 230))
        val bad = line(word("Madarne", 41f, 100), word("Eiise", 38f, 200), word("Martin", 90f, 280))
        val asked = ArrayList<String>()
        val (result, changed) = OcrProofreading.proofread(blank, OcrResult(1200, 400, listOf(good, bad))) { image, current ->
            asked += current
            assertTrue(image.height in 30..60, "image de la ligne : ${image.width} x ${image.height}")
            "Madame Élise Martin"
        }
        assertEquals(listOf("Madarne Eiise Martin"), asked)
        assertEquals(1, changed)
        assertEquals("Avenue de la\nMadame Élise Martin", result.text)
        // Meme nombre de mots : chacun garde sa place.
        assertEquals(bad.words.map { it.left }, result.lines[1].words.map { it.left })
    }

    @Test
    fun inventionsAndRefusalsAreRejected() {
        val conf = 80f
        assertNull(OcrProofreading.accept("Le paiement est dû à trente jours.", "Le chat mange une croquette au thon.", conf, 30f))
        assertNull(OcrProofreading.accept("Total : 4 850 francs", "Sorry, I can't read the text in this image.", conf, 20f))
        assertNull(OcrProofreading.accept("Total : 4 850 francs", "Je suis désolé, je ne peux pas lire cette image.", 20f, 20f))
        assertNull(OcrProofreading.accept("Total : 4 850 francs", "Total : 4 850 francs", conf, 20f))
        // Correction plausible, entre guillemets et avec un prefixe : nettoyee et gardee.
        assertEquals("Total : 4 850 francs", OcrProofreading.accept("Tota1 : 4 85O francs", "Text: « Total : 4 850 francs »", conf, 20f))
    }

    @Test
    fun handwritingNeedsTheRightLength() {
        // Tesseract ne lit presque rien (ecriture manuscrite) : on accepte une lecture de la bonne longueur.
        assertEquals("Merci pour tout, à bientôt !", OcrProofreading.accept("~ .,  ''", "Merci pour tout, à bientôt !", 12f, 26f))
        assertNull(OcrProofreading.accept("~ .,", "Merci", 12f, 40f))
        assertNull(OcrProofreading.accept("~ .,", "Ceci est un tres long texte qui ne tient pas du tout sur cette petite ligne de l'image", 12f, 10f))
    }

    @Test
    fun newWordsAreSpreadAlongTheLine() {
        val garbled = line(word("~~~~~~~~", 10f, 100), word("..", 12f, 300))
        val fixed = OcrProofreading.applyText(garbled, "Merci pour tout")
        assertEquals(listOf("Merci", "pour", "tout"), fixed.words.map { it.text })
        assertEquals(garbled.left, fixed.words.first().left)
        assertEquals(garbled.right, fixed.words.last().right)
        assertTrue(fixed.words.zipWithNext().all { (a, b) -> a.right <= b.left })
        assertEquals(garbled.y1, fixed.y1)
    }

    @Test
    fun aSilentModelChangesNothing() {
        val bad = line(word("Madarne", 41f, 100))
        val ocr = OcrResult(1200, 400, listOf(bad))
        val (result, changed) = OcrProofreading.proofread(blank, ocr) { _, _ -> throw IllegalStateException("quota") }
        assertEquals(0, changed)
        assertEquals("Madarne", result.text)
    }
}

/** Android lit la sortie hOCR de Tesseract : elle doit donner les memes mots et lignes de base. */
class HocrTest {
    @Test
    fun hocrGivesTheSameLinesAsTheIterator() {
        val file = java.io.File(kotlin.io.path.createTempDirectory("nougat-hocr").toFile(), "scan.pdf")
        makeSampleScan(file)
        val image = org.apache.pdfbox.Loader.loadPDF(file).use {
            org.apache.pdfbox.rendering.PDFRenderer(it).renderImageWithDPI(0, 300f, org.apache.pdfbox.rendering.ImageType.GRAY)
        }.toGrayImage().evenLighting()
        Tesseract(TessData.dir!!, "fra+eng").use { tess ->
            val direct = tess.recognize(image, 300)
            val parsed = parseHocr(tess.hocr(image, 300), image.width, image.height)
            assertEquals(direct.text, parsed.text)
            assertEquals(direct.lines.size, parsed.lines.size)
            for ((a, b) in direct.lines.zip(parsed.lines)) {
                assertEquals(a.y1, b.y1, 2f)
                assertEquals(a.y2, b.y2, 2f)
                assertEquals(a.words.map { it.left }, b.words.map { it.left })
            }
            assertTrue("Élise" in parsed.text && "&" in parsed.text, parsed.text)
        }
    }

    @Test
    fun entitiesAndMissingBaselines() {
        val hocr = """<div class='ocr_page' title='bbox 0 0 1000 500'>
            <span class='ocr_line' id='line_1_1' title="bbox 100 100 600 140; x_size 30">
            <span class='ocrx_word' id='word_1_1' title='bbox 100 100 260 140; x_wconf 91'>Nougat</span>
            <span class='ocrx_word' id='word_1_2' title='bbox 280 100 330 140; x_wconf 88'>&amp;</span>
            <span class='ocrx_word' id='word_1_3' title='bbox 350 100 600 138; x_wconf 93'><strong>Fils</strong></span>
            </span></div>"""
        val r = parseHocr(hocr, 1000, 500)
        assertEquals("Nougat & Fils", r.text)
        assertEquals(listOf(91f, 88f, 93f), r.words.map { it.conf })
        assertTrue(r.lines[0].x2 > r.lines[0].x1)
    }
}
