package app.nougat.pdf.viewer

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.nougat.pdf.assistant.AssistantEngine
import app.nougat.pdf.assistant.AssistantModels
import app.nougat.pdf.assistant.AssistantSettings
import app.nougat.pdf.assistant.AssistantUiState
import app.nougat.pdf.assistant.ChatItem
import app.nougat.pdf.assistant.NougatToolbox
import app.nougat.pdf.engine.EditableDoc
import app.nougat.pdf.engine.PasswordRequiredException
import app.nougat.pdf.engine.PdfEngine
import app.nougat.pdf.engine.TextBlock
import app.nougat.pdf.engine.TextEditException
import app.nougat.pdf.engine.TextEditResult
import app.nougat.pdf.engine.burn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

/** Ce que l'app affiche : l'accueil ou un document, l'edition, et les fenetres par-dessus. */
class ViewerState(
    private val engine: PdfEngine,
    private val scope: CoroutineScope,
    val files: PlatformFiles,
    /** L'assistant Claude (null si la plateforme ne le propose pas). */
    private val assistantEngine: AssistantEngine? = null,
) {
    /** Le document ouvert, ou null pour l'ecran d'accueil. */
    var session: DocumentSession? by mutableStateOf(null)
        private set

    /** Message pendant une operation (ouverture, modification, enregistrement). */
    var busyMessage: String? by mutableStateOf(null)
        private set

    var passwordPrompt: PasswordPrompt? by mutableStateOf(null)
        private set

    var errorMessage: String? by mutableStateOf(null)
        private set

    /** Petit message de confirmation (« Enregistré »). */
    var toast: String? by mutableStateOf(null)
        private set

    /** Action en attente d'une reponse a « Enregistrer les modifications ? ». */
    var confirmDiscard: (() -> Unit)? by mutableStateOf(null)
        private set

    /** Outils d'edition (conserves d'un document a l'autre). */
    val tools = EditTools()

    // ---- Assistant Claude ------------------------------------------------------------------------

    val assistant = AssistantUiState()
    val assistantAvailable: Boolean get() = assistantEngine != null

    /** Cle API Anthropic de l'utilisateur (stockee seulement sur cet appareil). */
    var apiKey by mutableStateOf(runCatching { files.loadSetting(SETTING_API_KEY) }.getOrNull().orEmpty())
        private set
    var assistantModel by mutableStateOf(
        runCatching { files.loadSetting(SETTING_MODEL) }.getOrNull()?.takeIf { it.isNotBlank() } ?: AssistantModels.DEFAULT,
    )
        private set
    private var assistantJob: Job? = null

    fun saveAssistantSettings(key: String, model: String) {
        apiKey = key.trim()
        assistantModel = model
        runCatching {
            files.saveSetting(SETTING_API_KEY, apiKey)
            files.saveSetting(SETTING_MODEL, model)
        }
        assistant.showSettings = false
    }

    fun sendToAssistant(text: String) {
        val engine = assistantEngine ?: return
        val message = text.trim()
        if (message.isEmpty() || assistant.running) return
        if (apiKey.isBlank()) {
            assistant.showSettings = true
            return
        }
        if (session == null) {
            assistant.items.add(ChatItem.Error("Ouvre d'abord un PDF."))
            return
        }
        assistant.items.add(ChatItem.User(message))
        assistant.input = ""
        assistant.running = true
        assistantJob = scope.launch {
            try {
                engine.send(message, AssistantSettings(apiKey, assistantModel), NougatToolbox(this@ViewerState)) { item ->
                    assistant.items.add(item)
                }
            } catch (e: CancellationException) {
                assistant.items.add(ChatItem.Error("Arrêté."))
                throw e
            } catch (e: Throwable) {
                assistant.items.add(ChatItem.Error(e.message ?: "Erreur inconnue"))
            } finally {
                assistant.running = false
            }
        }
    }

    fun stopAssistant() {
        assistantJob?.cancel()
    }

    /** Nouvelle conversation. */
    fun resetAssistant() {
        stopAssistant()
        assistantEngine?.reset()
        assistant.items.clear()
    }

    /** Ecran d'organisation des pages ouvert. */
    var pagesOpen by mutableStateOf(false)

    /** Pages choisies dans l'ecran Pages (conservees d'une modification a l'autre). */
    var pageSelection by mutableStateOf(emptySet<Int>())

    /** Defilement de la grille des pages (idem). */
    var pagesGrid = LazyGridState()
        private set

    // Versions precedentes du document, pour « Annuler ».
    private val history = ArrayList<Version>()
    private var historySize by mutableStateOf(0)
    private val temps = ArrayList<String>()
    private var tempCounter = 0

    /** Fichier tel qu'enregistre pour la derniere fois (pour savoir s'il y a des modifications). */
    private var savedPath: String? by mutableStateOf(null)

    private var openJob: Job? = null

    val modified: Boolean
        get() {
            val s = session ?: return false
            return s.path != savedPath || s.marks.isNotEmpty()
        }

    val canUndo: Boolean get() = (session?.marks?.isNotEmpty() == true) || historySize > 0

    // ---- Ouvrir / fermer -------------------------------------------------------------------

    /**
     * Ouvre le PDF [path] ; [name] est le nom montre a l'utilisateur et [sourceRef]
     * l'endroit ou « Enregistrer » ecrira (le meme fichier par defaut).
     */
    fun open(path: String, name: String, password: String? = null, sourceRef: String = path) {
        openJob?.cancel()
        passwordPrompt = null
        errorMessage = null
        busyMessage = "Ouverture de « $name »…"
        openJob = scope.launch {
            try {
                val doc = engine.open(path, password)
                closeCurrent()
                session = DocumentSession(name, path, sourceRef, password, doc, scope)
                savedPath = path
                pagesOpen = false
                resetAssistant()
                pageSelection = emptySet()
                pagesGrid = LazyGridState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: PasswordRequiredException) {
                passwordPrompt = PasswordPrompt(path, name, sourceRef, e.wrongPassword)
            } catch (e: Throwable) {
                errorMessage = "Impossible d'ouvrir « $name ».\n${e.message ?: e::class.simpleName}"
            } finally {
                // Une ouverture plus recente a pris la main : c'est elle qui gere l'indicateur.
                if (openJob == currentCoroutineContext()[Job]) busyMessage = null
            }
        }
    }

    fun submitPassword(password: String) {
        val p = passwordPrompt ?: return
        open(p.path, p.name, password, p.sourceRef)
    }

    fun dismissPassword() {
        passwordPrompt = null
    }

    fun showError(message: String) {
        errorMessage = message
    }

    fun dismissError() {
        errorMessage = null
    }

    fun showToast(message: String) {
        toast = message
    }

    fun dismissToast() {
        toast = null
    }

    /**
     * Lance [action] tout de suite, ou apres avoir demande quoi faire des modifications
     * non enregistrees (fermer, ouvrir un autre fichier, quitter).
     */
    fun runAfterConfirm(action: () -> Unit) {
        if (modified) confirmDiscard = action else action()
    }

    /** Reponse a « Enregistrer les modifications ? » : oui, non, ou null pour annuler. */
    fun answerConfirm(save: Boolean?) {
        val action = confirmDiscard ?: return
        confirmDiscard = null
        when (save) {
            null -> Unit
            false -> action()
            true -> scope.launch { if (saveNow()) action() }
        }
    }

    /** Ferme le document (sans rien demander : voir [runAfterConfirm]). */
    fun closeDocument() {
        closeCurrent()
        session = null
        pagesOpen = false
    }

    private fun closeCurrent() {
        session?.close()
        history.clear()
        historySize = 0
        temps.forEach(files::delete)
        temps.clear()
    }

    // ---- Modifier ------------------------------------------------------------------------------

    /**
     * Applique une modification : grave d'abord les annotations en attente, puis [block].
     * Le resultat est une nouvelle copie de travail ; l'ancienne reste disponible pour « Annuler ».
     */
    fun edit(label: String, block: (EditableDoc) -> Unit = {}) {
        scope.launch { editNow(label, session?.password, block) }
    }

    /** Applique une modification et attend la fin ; leve une exception en cas d'echec (assistant). */
    suspend fun editAwait(label: String, block: (EditableDoc) -> Unit) {
        performEdit(label, session?.password, block)
    }

    /** Remplace le texte d'une ligne et attend la fin (assistant). */
    suspend fun replaceTextAwait(block: TextBlock, newText: String): TextEditResult {
        var result: TextEditResult? = null
        performEdit("Modification du texte…", session?.password) { doc -> result = doc.replaceText(block, newText) }
        return result ?: TextEditResult.SameFont
    }

    private suspend fun performEdit(label: String, newPassword: String?, block: (EditableDoc) -> Unit) {
        val s = session ?: throw IllegalStateException("Aucun document ouvert")
        busyMessage = label
        try {
            val marks = s.marks.toList()
            val dst = newTempPath()
            engine.edit(s.path, s.password, dst) { doc ->
                doc.burn(marks)
                block(doc)
            }
            history.add(Version(s.path, s.password))
            historySize = history.size
            reopen(dst, newPassword)
        } finally {
            busyMessage = null
        }
    }

    private suspend fun editNow(
        label: String,
        newPassword: String?,
        block: (EditableDoc) -> Unit,
    ): Boolean {
        return try {
            performEdit(label, newPassword, block)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: TextEditException) {
            errorMessage = e.message ?: "Ce texte ne peut pas être modifié."
            false
        } catch (e: Throwable) {
            errorMessage = "La modification a échoué.\n${e.message ?: e::class.simpleName}"
            false
        }
    }

    /** Remplace le document affiche par [path], en gardant la page et le zoom. */
    private suspend fun reopen(path: String, password: String?) {
        val old = session ?: return
        val doc = engine.open(path, password)
        val fresh = DocumentSession(
            old.name, path, old.sourceRef, password, doc, scope,
            initialItem = old.listState.firstVisibleItemIndex,
            initialOffset = old.listState.firstVisibleItemScrollOffset,
        )
        fresh.updateZoom(old.zoom)
        fresh.visiblePage = old.visiblePage.coerceAtMost(doc.pageCount - 1)
        old.close()
        pageSelection = pageSelection.filter { it < doc.pageCount }.toSet()
        session = fresh
    }

    /** Annule la derniere annotation, ou sinon la derniere modification du document. */
    fun undo() {
        val s = session ?: return
        if (s.marks.isNotEmpty()) {
            s.marks.removeAt(s.marks.lastIndex)
            return
        }
        val previous = history.removeLastOrNull() ?: return
        historySize = history.size
        scope.launch {
            busyMessage = "Annulation…"
            try {
                reopen(previous.path, previous.password)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                errorMessage = "Impossible d'annuler.\n${e.message}"
            } finally {
                busyMessage = null
            }
        }
    }

    // ---- Pages -----------------------------------------------------------------------------------

    fun rotatePages(pages: Set<Int>, delta: Int) = edit("Rotation…") { doc ->
        for (i in pages) doc.setRotation(i, doc.geometry(i).rotation + delta)
    }

    fun deletePages(pages: Set<Int>) {
        val s = session ?: return
        if (pages.size >= s.pageCount) {
            errorMessage = "Un PDF doit garder au moins une page."
            return
        }
        pageSelection = emptySet()
        edit("Suppression…") { doc -> doc.reorder((0 until doc.pageCount).filter { it !in pages }) }
    }

    /** Deplace les pages choisies d'un cran ([direction] = -1 ou +1) ; la selection les suit. */
    fun movePages(pages: Set<Int>, direction: Int) {
        val s = session ?: return
        val order = moveInOrder(s.pageCount, pages, direction)
        if (order == (0 until s.pageCount).toList()) return
        pageSelection = order.indices.filter { order[it] in pages }.toSet()
        edit("Déplacement…") { it.reorder(order) }
    }

    fun insertBlankPage(at: Int) {
        val s = session ?: return
        val size = s.pageSizes.getOrNull((at - 1).coerceIn(0, s.pageCount - 1)) ?: return
        pageSelection = setOf(at)
        edit("Ajout d'une page…") { it.insertBlankPage(at, size.width, size.height) }
    }

    fun appendPdf() {
        scope.launch {
            val other = files.pickPdf() ?: return@launch
            editNow("Ajout de « ${other.name} »…", session?.password) { it.appendPdf(other.path) }
        }
    }

    /** Enregistre les pages choisies dans un nouveau fichier. */
    fun extractPages(pages: Set<Int>) {
        val s = session ?: return
        scope.launch {
            busyMessage = "Extraction…"
            try {
                val dst = newTempPath()
                val marks = s.marks.toList()
                engine.edit(s.path, s.password, dst) { doc ->
                    doc.burn(marks)
                    doc.reorder(pages.sorted())
                }
                busyMessage = null
                val saved = files.saveAs(dst, s.name.removeSuffix(".pdf") + " (extrait).pdf")
                if (saved != null) toast = "Pages enregistrées dans « ${saved.name} »"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                errorMessage = "L'extraction a échoué.\n${e.message}"
            } finally {
                busyMessage = null
            }
        }
    }

    /**
     * Remplace le texte d'une ligne du PDF par [newText], au meme endroit et dans le meme style.
     */
    fun replaceText(block: TextBlock, newText: String) {
        val s = session ?: return
        s.editingBlock = null
        if (newText.trim() == block.text.trim()) return
        scope.launch {
            var result: TextEditResult? = null
            val ok = editNow("Modification du texte…", s.password) { doc ->
                result = doc.replaceText(block, newText)
            }
            if (ok) {
                toast = when (result) {
                    TextEditResult.SimilarFont -> "Texte modifié (police proche : l'originale ne contient pas ces caractères)"
                    TextEditResult.InstalledFont -> "Texte modifié (police d'origine installée sur cet ordinateur)"
                    else -> "Texte modifié"
                }
            }
        }
    }

    /** Protege le PDF par mot de passe, ou retire la protection ([password] null). */
    fun setPassword(password: String?) {
        val label = if (password == null) "Retrait du mot de passe…" else "Protection…"
        scope.launch { editNow(label, password) { it.setPassword(password) } }
    }

    // ---- Enregistrer, imprimer, partager ------------------------------------------------------

    fun save() {
        scope.launch { saveNow() }
    }

    /** Enregistre a l'emplacement d'origine (ou demande ou, si impossible). */
    private suspend fun saveNow(): Boolean {
        if (!commitMarks()) return false
        val s = session ?: return false
        if (s.path == savedPath) return true
        busyMessage = "Enregistrement…"
        val ok = try {
            files.overwrite(s.path, s.sourceRef)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        } finally {
            busyMessage = null
        }
        if (ok) {
            savedPath = s.path
            toast = "Enregistré"
            return true
        }
        return saveAsNow()
    }

    fun saveAs() {
        scope.launch { saveAsNow() }
    }

    private suspend fun saveAsNow(): Boolean {
        if (!commitMarks()) return false
        val s = session ?: return false
        return try {
            val saved = files.saveAs(s.path, s.name) ?: return false
            s.rename(saved.name, saved.ref)
            savedPath = s.path
            toast = "Enregistré sous « ${saved.name} »"
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            errorMessage = "Impossible d'enregistrer.\n${e.message}"
            false
        }
    }

    fun print() {
        scope.launch {
            if (!commitMarks()) return@launch
            val s = session ?: return@launch
            try {
                files.print(s.path, s.password, s.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                errorMessage = "Impression impossible.\n${e.message}"
            }
        }
    }

    fun share() {
        scope.launch {
            if (!commitMarks()) return@launch
            val s = session ?: return@launch
            try {
                files.share(s.path, s.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                errorMessage = "Partage impossible.\n${e.message}"
            }
        }
    }

    /** Grave les annotations en attente (et la ligne de texte en cours de modification). */
    private suspend fun commitMarks(): Boolean {
        if (!commitPendingTextEdit()) return false
        val s = session ?: return false
        return s.marks.isEmpty() || editNow("Application des modifications…", s.password) {}
    }

    /** Applique la ligne en cours de modification (outil « Éditer »), si son texte a change. */
    suspend fun commitPendingTextEdit(): Boolean {
        val s = session ?: return true
        val block = s.editingBlock ?: return true
        val text = s.editingText
        s.editingBlock = null
        if (text.trim() == block.text.trim()) return true
        return editNow("Modification du texte…", s.password) { it.replaceText(block, text) }
    }

    /** Toucher ailleurs pendant une modification : on applique (au lieu de perdre le texte tape). */
    fun commitPendingTextEditLater() {
        scope.launch { commitPendingTextEdit() }
    }

    private fun newTempPath(): String {
        val path = "${files.tempDir}/travail-${++tempCounter}-${kotlin.random.Random.nextInt(1_000_000)}.pdf"
        temps.add(path)
        return path
    }

    private data class Version(val path: String, val password: String?)

    private companion object {
        const val SETTING_API_KEY = "assistant.apiKey"
        const val SETTING_MODEL = "assistant.model"
    }
}

/** Nouvel ordre des pages quand on deplace [pages] d'un cran vers [direction]. */
internal fun moveInOrder(pageCount: Int, pages: Set<Int>, direction: Int): List<Int> {
    val order = (0 until pageCount).toMutableList()
    val positions = if (direction < 0) order.indices.toList() else order.indices.reversed()
    for (pos in positions) {
        val page = order[pos]
        val target = pos + direction
        if (page in pages && target in order.indices && order[target] !in pages) {
            order[pos] = order[target]
            order[target] = page
        }
    }
    return order
}

data class PasswordPrompt(val path: String, val name: String, val sourceRef: String, val wrongPassword: Boolean)
