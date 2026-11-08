package app.nougat.pdf.engine

/*
 * Numeros de page et date, ecrits sur chaque page (dans la marge), en Helvetica gris fonce.
 */

enum class StampPosition { BottomCenter, BottomRight, BottomLeft, TopCenter, TopRight, TopLeft }

enum class NumberFormat { Plain, Page, PageOf, Slash }

/** Ce qu'il faut ecrire et ou. [pageWord] et [ofWord] : « Page » et « sur » dans la langue choisie. */
data class PageStamp(
    val numbers: Boolean = true,
    val format: NumberFormat = NumberFormat.PageOf,
    val position: StampPosition = StampPosition.BottomCenter,
    val firstNumber: Int = 1,
    val skipFirstPage: Boolean = false,
    /** Texte de la date, ou null pour ne pas en mettre. */
    val date: String? = null,
    val datePosition: StampPosition = StampPosition.TopRight,
    val size: Float = 10f,
    val pageWord: String = "Page",
    val ofWord: String = "sur",
) {
    /** Le texte du numero de la page [index] (0 = premiere) sur [count] pages. */
    fun numberText(index: Int, count: Int): String {
        val skipped = if (skipFirstPage) 1 else 0
        val n = firstNumber + index - skipped
        val total = firstNumber + count - 1 - skipped
        return when (format) {
            NumberFormat.Plain -> "$n"
            NumberFormat.Page -> "$pageWord $n"
            NumberFormat.PageOf -> "$pageWord $n $ofWord $total"
            NumberFormat.Slash -> "$n / $total"
        }
    }
}

/** Ecrit les numeros de page et la date de [stamp] sur toutes les pages. */
fun EditableDoc.stampPages(stamp: PageStamp) {
    val count = pageCount
    val color = 0xFF333333.toInt()
    val margin = 26f
    for (index in 0 until count) {
        val g = geometry(index)
        val w = g.displayWidth
        val h = g.displayHeight
        draw(index) { canvas ->
            fun write(text: String, position: StampPosition) {
                val tw = canvas.textWidth(text, stamp.size)
                val x = when (position) {
                    StampPosition.BottomCenter, StampPosition.TopCenter -> (w - tw) / 2f
                    StampPosition.BottomRight, StampPosition.TopRight -> w - margin - tw
                    StampPosition.BottomLeft, StampPosition.TopLeft -> margin
                }
                val top = position == StampPosition.TopCenter || position == StampPosition.TopRight || position == StampPosition.TopLeft
                val baseline = if (top) h - margin - stamp.size * 0.8f else margin
                canvas.text(x, baseline, text, stamp.size, color)
            }
            if (stamp.numbers && !(stamp.skipFirstPage && index == 0)) write(stamp.numberText(index, count), stamp.position)
            stamp.date?.takeIf { it.isNotBlank() }?.let { write(it, stamp.datePosition) }
        }
    }
}
