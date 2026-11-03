package app.nougat.pdf.ui

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FreeColorTest {
    private fun close(a: Int, b: Int, tolerance: Int = 4): Boolean =
        abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) <= tolerance &&
            abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) <= tolerance &&
            abs((a and 0xFF) - (b and 0xFF)) <= tolerance

    @Test
    fun hexCodes() {
        assertEquals(0xFFCC542C.toInt(), FreeColor.parse("#CC542C"))
        assertEquals(0xFFCC542C.toInt(), FreeColor.parse("cc542c"))
        assertNull(FreeColor.parse("#CC54"))
        assertNull(FreeColor.parse("#GG0000"))
        assertEquals("#CC542C", FreeColor.hex(0xFFCC542C.toInt()))
        assertEquals("#00000A", FreeColor.hex(0xFF00000A.toInt()))
    }

    @Test
    fun slidersReachVividAndDeepColors() {
        // Au milieu des curseurs : l'orange d'ArtisanGo (a un cheveu pres).
        assertTrue(close(FreeColor.fromSliders(18f, 45f), 0xFFEB7846.toInt()), FreeColor.hex(FreeColor.fromSliders(18f, 45f)))
        // Tout au bout : un bleu marine, pas un bleu fluo.
        val navy = FreeColor.fromSliders(220f, 100f)
        assertTrue(FreeColor.hsv(navy)[2] < 0.35f)
        // Les curseurs se replacent d'apres la couleur.
        for (d in listOf(0f, 20f, 45f, 70f, 100f)) assertEquals(d, FreeColor.depthOf(FreeColor.fromSliders(200f, d)), 1.5f)
        assertEquals(200f, FreeColor.hsv(FreeColor.fromSliders(200f, 60f))[0], 1.5f)
    }
}
