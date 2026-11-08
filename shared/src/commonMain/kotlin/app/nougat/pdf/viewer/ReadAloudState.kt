package app.nougat.pdf.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.nougat.pdf.engine.NormRect

/** La lecture a voix haute en cours : ou elle en est (page, paragraphe surligne) et sa vitesse. */
class ReadAloudState {
    /** La barre de lecture est affichee. */
    var active by mutableStateOf(false)

    /** La voix parle (sinon : en pause). */
    var playing by mutableStateOf(false)

    var page by mutableIntStateOf(0)
    var index by mutableIntStateOf(0)

    /** Les lignes du paragraphe lu, surlignees sur la page. */
    var rects by mutableStateOf<List<NormRect>>(emptyList())

    /** La langue lue (fr, de...). */
    var language by mutableStateOf("")

    /** On lit la traduction affichee (et non le texte d'origine). */
    var translated by mutableStateOf(false)

    var rate by mutableStateOf(1f)

    fun reset() {
        active = false
        playing = false
        rects = emptyList()
        translated = false
    }
}
