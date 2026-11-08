package app.nougat.pdf.engine

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeasureTest {
    private val a4 = PageSize(595.28f, 841.89f)

    private fun close(expected: Double, actual: Double, tolerance: Double = 1e-3) =
        assertTrue(abs(expected - actual) < tolerance, "attendu $expected, obtenu $actual")

    @Test
    fun lengthAtScale() {
        // 10 cm sur le papier a 1:100 = 10 m.
        val tenCm = 0.1 / Measures.POINT_METERS
        val m = Measurement(0, MeasureKind.Length, listOf(NPoint(0.1f, 0.5f), NPoint(0.1f + (tenCm / a4.width).toFloat(), 0.5f)))
        close(10.0, Measures.meters(m, a4, Measures.metersPerPoint(100.0)))
        assertEquals(100L, Measures.ratioOf(Measures.metersPerPoint(100.0)))
    }

    @Test
    fun polylineAndArea() {
        // Carre de 100 x 100 points, a 1 point = 1 cm : 4 m de tour, 1 m².
        val w = a4.width
        val h = a4.height
        val pts = listOf(NPoint(100f / w, 100f / h), NPoint(200f / w, 100f / h), NPoint(200f / w, 200f / h), NPoint(100f / w, 200f / h))
        val square = Measurement(0, MeasureKind.Area, pts)
        close(4.0, Measures.meters(square, a4, 0.01))
        close(1.0, Measures.squareMeters(square, a4, 0.01))
        val open = Measurement(0, MeasureKind.Length, pts)
        close(3.0, Measures.meters(open, a4, 0.01))
        assertTrue(square.complete)
        assertFalse(Measurement(0, MeasureKind.Area, pts.take(2)).complete)
    }

    @Test
    fun findsTheScaleWrittenOnThePlan() {
        assertEquals(50, Measures.findScale("Plan rez-de-chaussée  Échelle 1:50  Date 12.03.2026"))
        assertEquals(100, Measures.findScale("Grundriss EG  M 1:100"))
        assertEquals(200, Measures.findScale("scala 1/200"))
        assertEquals(100, Measures.findScale("Façade sud 1 : 100"))
        assertNull(Measures.findScale("Rendez-vous à 10:30, facture 2026-114"))
    }

    @Test
    fun formats() {
        assertEquals("3,45 m", Measures.formatLength(3.4512, comma = true))
        assertEquals("85.0 cm", Measures.formatLength(0.85, comma = false))
        assertEquals("12,30 m²", Measures.formatArea(12.3, comma = true))
        assertEquals(5.2, Measures.parseNumber("5,20"))
        assertNull(Measures.parseNumber("abc"))
        assertNull(Measures.parseNumber("0"))
    }
}
