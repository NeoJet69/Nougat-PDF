package app.nougat.pdf.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextEditPlannerTest {
    private fun bytes(s: String) = s.encodeToByteArray()

    /** Operateur [(ABC) -100 (DE)] TJ, police de taille 10, chaque lettre avance de 10. */
    private val op = AnalyzedTextOp(
        index = 0, name = "TJ", fontResource = "F1", fontSize = 10f, hScale = 1f,
        charSpacing = 0f, wordSpacing = 0f, quoteSpacing = null,
        elements = listOf(
            TJElement.Str(0, bytes("ABC"), List(3) { SegmentGlyph(it, it + 1, 10f) }),
            TJElement.Num(-100f),
            TJElement.Str(1, bytes("DE"), List(2) { SegmentGlyph(it, it + 1, 10f) }),
        ),
    )
    private val analysis = PageTextAnalysis(emptyList(), mapOf(0 to op))

    private fun block(vararg keys: GlyphKey) =
        TextBlock(0, "x", NormRect(0f, 0f, 1f, 1f), 10f, true, keys.toList())

    private fun render(ops: List<ContentOp>): List<Any> = ops.flatMap { op ->
        when (op) {
            is ContentOp.ShowArray -> op.elements.map { if (it is ByteArray) it.decodeToString() else it }
            else -> listOf(op.toString())
        }
    }

    @Test
    fun replacesInPlaceAndKeepsTheRestStill() {
        // On remplace B et C par X : D doit rester a sa place (x = 31).
        val plan = TextEditPlanner.plan(
            analysis, block(GlyphKey(0, 0, 1), GlyphKey(0, 0, 2)),
            Replacement.SameFont(listOf(bytes("X")), width = 10f),
        )
        assertEquals(listOf<Any>("A", "X", -1100f, "DE"), render(plan.getValue(0)))
    }

    @Test
    fun deletingTheEndKeepsTheCurrentPosition() {
        // Supprimer D et E : un decalage final garde la position pour la suite du flux.
        val plan = TextEditPlanner.plan(
            analysis, block(GlyphKey(0, 1, 0), GlyphKey(0, 1, 1)),
            Replacement.SameFont(emptyList(), width = 0f),
        )
        assertEquals(listOf<Any>("ABC", -100f, -2000f), render(plan.getValue(0)))
    }

    @Test
    fun otherFontSwitchesAndRestoresTheFont() {
        val plan = TextEditPlanner.plan(
            analysis, block(GlyphKey(0, 0, 0)),
            Replacement.OtherFont("NF", bytes("Z"), width = 5f),
        )
        val ops = plan.getValue(0)
        assertEquals(ContentOp.SetFont("NF", 10f), ops[0])
        assertEquals(ContentOp.SetFont("F1", 10f), ops[2])
        // Z (5) a remplace A (10) : un decalage de 5 garde B a sa place.
        assertEquals(listOf<Any>(-500f, "BC", -100f, "DE"), render(listOf(ops[3])))
    }

    @Test
    fun picksASimilarStandardFont() {
        assertEquals(StandardFont.HelveticaBold, closestStandardFont("ABCDEF+Arial-BoldMT"))
        assertEquals(StandardFont.TimesItalic, closestStandardFont("TimesNewRomanPS-ItalicMT"))
        assertEquals(StandardFont.Courier, closestStandardFont("CourierNewPSMT"))
        assertTrue(closestStandardFont(null) == StandardFont.Helvetica)
    }
}
