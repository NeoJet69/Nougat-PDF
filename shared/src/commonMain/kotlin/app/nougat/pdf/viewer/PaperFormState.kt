package app.nougat.pdf.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.nougat.pdf.engine.PaperField

/** Un champ trouve et ce qu'on y ecrira. */
class PaperFieldEntry(val field: PaperField, value: String) {
    var value by mutableStateOf(value)
}

/** « Remplir un formulaire papier » : les champs trouves sur la page, avec les valeurs du profil. */
class PaperFormState {
    var dialogOpen by mutableStateOf(false)
    var searching by mutableStateOf(false)
    var page by mutableStateOf(0)
    var entries by mutableStateOf<List<PaperFieldEntry>>(emptyList())

    /** La page est un scan dont le texte n'est pas reconnu : pas de libelle a lire. */
    var noText by mutableStateOf(false)

    /** Garder les informations sur l'appareil pour la prochaine fois. */
    var saveProfile by mutableStateOf(true)

    fun reset() {
        dialogOpen = false
        searching = false
        entries = emptyList()
        noText = false
    }
}
