package app.nougat.pdf.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/*
 * Echanger avec le telephone par le Wi-Fi local, sans cloud : l'ordinateur ouvre une petite page web
 * sur le reseau de la maison ; le telephone la trouve en scannant un code QR avec l'appareil photo,
 * puis envoie des PDF ou des photos (ou telecharge le document ouvert). Rien ne passe par Internet.
 */

/** Les textes de la page web montree au telephone ({0} : nom du fichier). */
data class TransferPageTexts(
    val language: String,
    val title: String,
    val intro: String,
    val choose: String,
    val sending: String,
    val sent: String,
    val failed: String,
    val getDocument: String,
)

/** L'adresse de la page et son code QR (vrai = module noir). */
class TransferAddress(val url: String, val qr: List<BooleanArray>, val folder: String)

/** Le petit serveur du Wi-Fi local (ordinateur seulement). */
interface LocalTransfer {
    /**
     * Demarre la page ; null s'il n'y a pas de reseau local. [document] : le document que le
     * telephone peut telecharger (chemin, nom), ou null. [onFile] : un fichier recu (chemin, nom),
     * appele hors du fil de l'ecran.
     */
    fun start(texts: TransferPageTexts, document: () -> Pair<String, String>?, onFile: (String, String) -> Unit): TransferAddress?

    fun stop()
}

/** Un fichier recu du telephone. */
class ReceivedFile(val path: String, val name: String) {
    val isPdf: Boolean get() = name.lowercase().endsWith(".pdf")
}

/** La fenetre « Echanger avec le telephone ». */
class TransferState {
    var dialogOpen by mutableStateOf(false)
    var address by mutableStateOf<TransferAddress?>(null)

    /** Pas de reseau local : rien a montrer. */
    var noNetwork by mutableStateOf(false)
    val received = mutableStateListOf<ReceivedFile>()

    fun reset() {
        dialogOpen = false
        address = null
        noNetwork = false
        received.clear()
    }
}
