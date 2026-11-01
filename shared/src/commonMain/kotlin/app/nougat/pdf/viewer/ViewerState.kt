package app.nougat.pdf.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.nougat.pdf.engine.PasswordRequiredException
import app.nougat.pdf.engine.PdfEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

/** Ce que l'app affiche : l'accueil ou un document, plus les fenetres par-dessus. */
class ViewerState(
    private val engine: PdfEngine,
    private val scope: CoroutineScope,
) {
    /** Le document ouvert, ou null pour l'ecran d'accueil. */
    var session: DocumentSession? by mutableStateOf(null)
        private set

    /** Nom du fichier en cours d'ouverture. */
    var loadingName: String? by mutableStateOf(null)
        private set

    var passwordPrompt: PasswordPrompt? by mutableStateOf(null)
        private set

    var errorMessage: String? by mutableStateOf(null)
        private set

    private var openJob: Job? = null

    /** Ouvre le PDF [path] ; [name] est le nom montre a l'utilisateur. */
    fun open(path: String, name: String, password: String? = null) {
        openJob?.cancel()
        passwordPrompt = null
        errorMessage = null
        loadingName = name
        openJob = scope.launch {
            try {
                val doc = engine.open(path, password)
                session?.close()
                session = DocumentSession(name, path, doc, scope)
            } catch (e: CancellationException) {
                throw e
            } catch (e: PasswordRequiredException) {
                passwordPrompt = PasswordPrompt(path, name, e.wrongPassword)
            } catch (e: Throwable) {
                errorMessage = "Impossible d'ouvrir « $name ».\n${e.message ?: e::class.simpleName}"
            } finally {
                // Une ouverture plus recente a pris la main : c'est elle qui gere l'indicateur.
                if (openJob == currentCoroutineContext()[Job]) loadingName = null
            }
        }
    }

    fun submitPassword(password: String) {
        val prompt = passwordPrompt ?: return
        open(prompt.path, prompt.name, password)
    }

    fun dismissPassword() {
        passwordPrompt = null
    }

    /** Affiche un message d'erreur venu de la plateforme (ex. fichier illisible). */
    fun showError(message: String) {
        errorMessage = message
    }

    fun dismissError() {
        errorMessage = null
    }

    /** Ferme le document et revient a l'accueil. */
    fun closeDocument() {
        session?.close()
        session = null
    }

    val currentPath: String? get() = session?.path
}

data class PasswordPrompt(val path: String, val name: String, val wrongPassword: Boolean)
