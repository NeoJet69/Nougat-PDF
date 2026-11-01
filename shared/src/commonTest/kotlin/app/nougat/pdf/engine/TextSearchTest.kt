package app.nougat.pdf.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextSearchTest {

    private fun page(vararg lines: String): PageText {
        val b = PageTextBuilder()
        lines.forEachIndexed { line, text ->
            text.forEachIndexed { i, c ->
                b.addGlyph(c.toString(), NormRect(i * 0.01f, line * 0.1f, (i + 1) * 0.01f, line * 0.1f + 0.05f))
            }
            b.addSpace()
        }
        return b.build()
    }

    @Test
    fun ignoresCaseAndAccents() {
        val hits = TextSearch.find(0, page("L'École de Nougat"), "ecole")
        assertEquals(1, hits.size)
        assertEquals(0.02f, hits[0].rects.single().left, 0.0001f)
    }

    @Test
    fun findsEveryOccurrence() {
        assertEquals(3, TextSearch.find(2, page("chat chat", "un chat"), "CHAT").size)
    }

    @Test
    fun matchAcrossTwoLinesGivesTwoRects() {
        val hits = TextSearch.find(0, page("le gros", "chat"), "gros chat")
        assertEquals(2, hits.single().rects.size)
    }

    @Test
    fun blankQueryFindsNothing() {
        assertTrue(TextSearch.find(0, page("abc"), "   ").isEmpty())
    }

    @Test
    fun builderNeverDoublesSpaces() {
        val b = PageTextBuilder()
        b.addSpace()
        b.addGlyph("a", NormRect(0f, 0f, 1f, 1f))
        b.addSpace(); b.addSpace()
        b.addGlyph(" ", NormRect(0f, 0f, 1f, 1f))
        b.addGlyph("b", NormRect(0f, 0f, 1f, 1f))
        assertEquals("a b", b.build().text)
    }
}
