package app.nougat.pdf.engine

import app.nougat.pdf.viewer.moveInOrder
import kotlin.test.Test
import kotlin.test.assertEquals

class PageOrderTest {
    @Test
    fun movesABlockEarlier() = assertEquals(listOf(0, 2, 3, 1, 4), moveInOrder(5, setOf(2, 3), -1))

    @Test
    fun movesABlockLater() = assertEquals(listOf(0, 3, 1, 2, 4), moveInOrder(5, setOf(1, 2), +1))

    @Test
    fun firstPageCannotGoEarlier() = assertEquals(listOf(0, 1, 2), moveInOrder(3, setOf(0), -1))

    @Test
    fun displayMatrixMatchesRotation() {
        val g = PageGeometry(0f, 0f, 100f, 200f, 90)
        assertEquals(200f, g.displayWidth)
        // Le coin bas-gauche de la page affichee correspond au coin (100, 0) du fichier.
        val m = g.displayToPdfMatrix()
        assertEquals(100f, m[4]); assertEquals(0f, m[5])
    }
}
