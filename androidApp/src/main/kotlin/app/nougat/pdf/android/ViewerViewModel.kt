package app.nougat.pdf.android

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.nougat.pdf.engine.AndroidPdfEngine
import app.nougat.pdf.viewer.ViewerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Garde le document ouvert quand l'ecran tourne. */
class ViewerViewModel(app: Application) : AndroidViewModel(app) {
    val state = ViewerState(AndroidPdfEngine(app), viewModelScope)

    private val openedDir = File(app.cacheDir, "ouverts").apply { mkdirs() }

    /**
     * Android donne une adresse « content:// » et non un vrai fichier :
     * on en fait une copie privee, que PdfRenderer et PdfBox savent lire.
     */
    fun openUri(uri: Uri) {
        viewModelScope.launch {
            val name = displayName(uri)
            try {
                val copy = withContext(Dispatchers.IO) { copyToCache(uri) }
                state.open(copy.absolutePath, name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                state.showError("Impossible de lire « $name ».\n${e.message ?: e::class.simpleName}")
            }
        }
    }

    private fun copyToCache(uri: Uri): File {
        // Les anciennes copies ne servent plus, sauf celle du document affiche.
        val keep = state.currentPath
        openedDir.listFiles()?.filter { it.absolutePath != keep }?.forEach { it.delete() }

        val target = File(openedDir, "document-${System.currentTimeMillis()}.pdf")
        val input = getApplication<Application>().contentResolver.openInputStream(uri)
            ?: error("Fichier introuvable")
        input.use { src -> target.outputStream().use { dst -> src.copyTo(dst) } }
        return target
    }

    private fun displayName(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        val fromProvider = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        return fromProvider ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document.pdf"
    }

    override fun onCleared() {
        state.closeDocument()
    }
}
