package app.nougat.pdf.android

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.nougat.pdf.assistant.ClaudeAssistantEngine
import app.nougat.pdf.engine.AndroidPdfEngine
import app.nougat.pdf.viewer.Tool
import app.nougat.pdf.viewer.ViewerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Garde le document ouvert quand l'ecran tourne. */
class ViewerViewModel(app: Application) : AndroidViewModel(app) {
    val files = AndroidFiles(app)
    val state = ViewerState(AndroidPdfEngine(app), viewModelScope, files, ClaudeAssistantEngine()).apply {
        // Sur telephone, un doigt fait defiler : on choisit l'outil (stylo...) ensuite.
        tools.tool = Tool.Pan
    }

    /**
     * Android donne une adresse « content:// » et non un vrai fichier : on lit une copie privee,
     * et « Enregistrer » ecrira a l'adresse d'origine.
     */
    fun openUri(uri: Uri) {
        state.runAfterConfirm {
            viewModelScope.launch {
                try {
                    val copy = files.copyToCache(uri, keep = state.currentPathOrNull())
                    state.open(copy.path, copy.name, sourceRef = copy.ref)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    state.showError("Impossible de lire ce fichier.\n${e.message ?: e::class.simpleName}")
                }
            }
        }
    }

    override fun onCleared() {
        state.closeDocument()
    }
}

private fun ViewerState.currentPathOrNull(): String? = session?.path
