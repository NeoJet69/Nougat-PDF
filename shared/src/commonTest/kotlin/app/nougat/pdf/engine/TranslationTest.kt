package app.nougat.pdf.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranslationTest {
    private val w = 600f
    private val h = 800f

    /** Une ligne de texte de taille [size] dont la ligne de base est a [baseline] points du haut. */
    private fun line(text: String, left: Float, baseline: Float, size: Float = 12f, width: Float = text.length * size * 0.5f, font: String = "Helvetica") =
        TextBlock(
            page = 0, text = text,
            rect = NormRect(left / w, (baseline - size) / h, (left + width) / w, (baseline + size * 0.25f) / h),
            fontSize = size, editable = true, glyphs = emptyList(), fontName = font,
        )

    @Test
    fun linesBecomeParagraphs() {
        val blocks = listOf(
            line("Le traducteur garde la mise en page du document", 72f, 100f, width = 450f),
            line("original et pose chaque paragraphe traduit a sa", 72f, 114f, width = 450f),
            line("place.", 72f, 128f, width = 40f),
            // Nouveau paragraphe, apres un espace.
            line("Deuxieme paragraphe, sur deux lignes, avec un mot coupe en fin de tra-", 72f, 160f, width = 450f),
            line("duction.", 72f, 174f, width = 60f),
            // Une liste.
            line("• premier point", 72f, 200f),
            line("• second point", 72f, 214f),
        )
        val paragraphs = Paragraphs.group(0, blocks, w, h)
        assertEquals(4, paragraphs.size, paragraphs.joinToString(" / ") { it.text })
        assertEquals("Le traducteur garde la mise en page du document original et pose chaque paragraphe traduit a sa place.", paragraphs[0].text)
        assertTrue(paragraphs[1].text.endsWith("fin de traduction."), paragraphs[1].text)
        assertEquals(3, paragraphs[0].lines.size)
        assertEquals(14f / 12f, paragraphs[0].lineSpacing, 0.01f)
        assertEquals("• premier point", paragraphs[2].text)
    }

    @Test
    fun addressLinesStaySeparate() {
        val blocks = listOf(
            line("Client : Jean Dupont", 50f, 100f, width = 110f),
            line("Adresse : 12 rue des Lilas, Lausanne", 50f, 118f, width = 200f),
            line("Date : 15 mars 2023", 50f, 136f, width = 105f),
            line("Marie Nougat", 50f, 180f, width = 70f),
            line("Rue du Lac 4", 50f, 194f, width = 68f),
            line("1003 Lausanne", 50f, 208f, width = 78f),
        )
        val paragraphs = Paragraphs.group(0, blocks, w, h)
        assertEquals(
            listOf("Client : Jean Dupont", "Adresse : 12 rue des Lilas, Lausanne", "Date : 15 mars 2023", "Marie Nougat Rue du Lac 4 1003 Lausanne"),
            paragraphs.map { it.text },
        )
    }

    @Test
    fun boldWordStaysInItsLine() {
        val blocks = listOf(
            line("Le total est de", 72f, 100f, width = 90f),
            line("120 francs", 164f, 100f, width = 60f, font = "Helvetica-Bold"),
            line("payables a 30 jours.", 226f, 100f, width = 110f),
        )
        val paragraphs = Paragraphs.group(0, blocks, w, h)
        assertEquals(1, paragraphs.size)
        assertEquals("Le total est de 120 francs payables a 30 jours.", paragraphs[0].text)
        assertEquals(3, paragraphs[0].blocks.size)
    }

    @Test
    fun columnsAreNotMixed() {
        val blocks = listOf(
            line("Colonne de gauche, ligne une", 40f, 100f, width = 220f),
            line("Colonne de droite, ligne une", 320f, 100f, width = 220f),
            line("Colonne de gauche, ligne deux", 40f, 114f, width = 220f),
            line("Colonne de droite, ligne deux", 320f, 114f, width = 220f),
        )
        val paragraphs = Paragraphs.group(0, blocks, w, h)
        assertTrue(paragraphs.none { "gauche" in it.text && "droite" in it.text }, paragraphs.joinToString(" / ") { it.text })
    }

    @Test
    fun onlyRealTextIsTranslated() {
        assertTrue(Paragraphs.needsTranslation("Facture"))
        assertFalse(Paragraphs.needsTranslation("12.03.2026"))
        assertFalse(Paragraphs.needsTranslation("CHF 1'250.00 -"))
        assertFalse(Paragraphs.needsTranslation("www.artisango.ch"))
        assertFalse(Paragraphs.needsTranslation("info@artisango.ch"))
    }

    private val measure: (String, Float) -> Float = { s, size -> s.length * size * 0.5f }

    @Test
    fun longerTranslationShrinksToFit() {
        val text = "Dieser Satz ist auf Deutsch deutlich laenger als das franzoesische Original, das hier stand."
        // Deux lignes de 12 points a l'origine : 250 points de large, 31 de haut.
        val fitted = TextFitter.fit(text, 250f, 31f, 12f, 4f, 1.2f, measure)
        assertTrue(fitted.size < 12f)
        for (l in fitted.lines) assertTrue(measure(l, fitted.size) <= 250f, l)
        assertTrue(fitted.size * (1f + (fitted.lines.size - 1) * 1.2f) <= 31f * 1.02f)
        assertEquals(text, fitted.lines.joinToString(" "))
    }

    @Test
    fun shortTranslationKeepsItsSize() {
        val fitted = TextFitter.fit("Invoice", 200f, 20f, 14f, 4f, 1.2f, measure)
        assertEquals(14f, fitted.size)
        assertEquals(listOf("Invoice"), fitted.lines)
    }

    @Test
    fun chineseWrapsBetweenCharacters() {
        val lines = TextFitter.wrap("这是一个很长的中文句子，没有空格。", 60f) { it.length * 10f }
        assertTrue(lines.size >= 3)
        assertTrue(lines.none { it.startsWith("，") || it.startsWith("。") }, lines.toString())
        assertEquals("这是一个很长的中文句子，没有空格。", lines.joinToString(""))
    }

    @Test
    fun arabicLettersAreJoined() {
        // sin (initiale), lam-alef (finale), mim (isolee : alef ne se lie pas a la lettre suivante).
        assertEquals("ﺳﻼﻡ", ArabicShaping.shape("سلام"))
        // kaf (initiale), ta (mediane), ba (finale).
        assertEquals("ﻛﺘﺐ", ArabicShaping.shape("كتب"))
        // lam + alef : une seule forme, isolee puis finale.
        assertEquals("ﻻ", ArabicShaping.shape("لا"))
        assertEquals("ﺑﻼ", ArabicShaping.shape("بلا"))
        assertEquals("Total 12", ArabicShaping.shape("Total 12"))
    }

    @Test
    fun paperAndInkColors() {
        val width = 40
        val height = 20
        val paper = 0xFFF5EBDC.toInt()
        val pixels = IntArray(width * height) { paper }
        // Des « lettres » bleu marine dans le rectangle 10..30 x 5..15.
        for (y in 7 until 13) for (x in 12 until 28 step 3) pixels[y * width + x] = 0xFF1A2A5A.toInt()
        val (bg, ink) = PageColors.sample(pixels, width, height, 10, 5, 30, 15)
        assertEquals(paper and 0xFFFFFF, bg)
        assertEquals(0x1A2A5A, ink)
    }
}
