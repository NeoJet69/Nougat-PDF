package app.nougat.pdf.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.nougat.pdf.engine.LetterDigest

/** « Ce que je dois faire » pour le document affiche : en cours, le resultat, ou pas de texte. */
class TodoState {
    var dialogOpen by mutableStateOf(false)
    var working by mutableStateOf(false)
    var digest by mutableStateOf<LetterDigest?>(null)

    /** Le document n'a pas de texte (scan pas encore reconnu). */
    var noText by mutableStateOf(false)

    /** Message de Claude s'il n'a pas pu repondre (les dates trouvees sans IA sont montrees). */
    var aiError by mutableStateOf<String?>(null)

    fun reset() {
        dialogOpen = false
        working = false
        digest = null
        noText = false
        aiError = null
    }
}
