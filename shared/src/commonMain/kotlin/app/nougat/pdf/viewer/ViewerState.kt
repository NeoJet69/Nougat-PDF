package app.nougat.pdf.viewer

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import app.nougat.pdf.ui.ThemeMode
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.nougat.pdf.assistant.AssistantEngine
import app.nougat.pdf.assistant.AssistantModels
import app.nougat.pdf.assistant.AssistantSettings
import app.nougat.pdf.assistant.AssistantUiState
import app.nougat.pdf.assistant.ChatItem
import app.nougat.pdf.assistant.NougatToolbox
import app.nougat.pdf.engine.A4_HEIGHT
import app.nougat.pdf.engine.A4_WIDTH
import app.nougat.pdf.engine.EditableDoc
import app.nougat.pdf.engine.FieldKind
import app.nougat.pdf.engine.FormField
import app.nougat.pdf.engine.FormFieldException
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
import app.nougat.pdf.i18n.I18n
import app.nougat.pdf.i18n.Lang
import app.nougat.pdf.i18n.Str
import app.nougat.pdf.i18n.tr
import app.nougat.pdf.i18n.trn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.selects.select
import app.nougat.pdf.engine.OcrOptions
import app.nougat.pdf.engine.OcrSummary
import app.nougat.pdf.engine.defaultOcrLanguages
import app.nougat.pdf.engine.DeviceAiStatus
import app.nougat.pdf.engine.OCR_LANGUAGES

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

    // ---- Langue -----------------------------------------------------------------------------------

    init {
        // La langue choisie, sinon celle de l'appareil (si Nougat la parle), sinon l'anglais.
        I18n.lang = Lang.fromCode(runCatching { files.loadSetting(SETTING_LANGUAGE) }.getOrNull()) ?: Lang.fromSystem()
    }

    /** Fenetre « Choisir la langue » ouverte. */
    var languageOpen by mutableStateOf(false)

    /** Fenetre « A propos » ouverte. */
    var aboutOpen by mutableStateOf(false)

    /** Fenetre « Licences » (depuis « A propos »). */
    var licensesOpen by mutableStateOf(false)

    fun setLanguage(lang: Lang) {
        I18n.lang = lang
        languageOpen = false
        runCatching { files.saveSetting(SETTING_LANGUAGE, lang.code) }
    }

    // ---- Theme et fichiers recents ---------------------------------------------------------------

    var themeMode by mutableStateOf(
        ThemeMode.entries.firstOrNull { it.name == runCatching { files.loadSetting(SETTING_THEME) }.getOrNull() } ?: ThemeMode.Auto,
    )
        private set

    /** Auto -> Clair -> Sombre -> Auto. */
    fun cycleTheme() {
        themeMode = ThemeMode.entries[(themeMode.ordinal + 1) % ThemeMode.entries.size]
        runCatching { files.saveSetting(SETTING_THEME, themeMode.name) }
    }

    /** Les derniers fichiers ouverts (le plus recent en premier). */
    val recents = mutableStateListOf<RecentFile>().apply { addAll(loadRecents()) }

    private fun loadRecents(): List<RecentFile> =
        runCatching { files.loadSetting(SETTING_RECENTS) }.getOrNull().orEmpty().lines().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size == 2 && parts[0].isNotBlank()) RecentFile(parts[0], parts[1]) else null
        }

    private fun addRecent(ref: String, name: String) {
        recents.removeAll { it.ref == ref }
        recents.add(0, RecentFile(ref, name))
        while (recents.size > MAX_RECENTS) recents.removeAt(recents.lastIndex)
        saveRecents()
    }

    fun forgetRecent(ref: String) {
        recents.removeAll { it.ref == ref }
        saveRecents()
    }

    private fun saveRecents() {
        runCatching { files.saveSetting(SETTING_RECENTS, recents.joinToString("\n") { "${it.ref}\t${it.name}" }) }
    }

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
            assistant.items.add(ChatItem.Error(tr(Str.OpenPdfFirst)))
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
                assistant.items.add(ChatItem.Error(tr(Str.Stopped)))
                throw e
            } catch (e: Throwable) {
                assistant.items.add(ChatItem.Error(e.message ?: tr(Str.UnknownError)))
            } finally {
                assistant.running = false
            }
        }
    }

    fun stopAssistant() {
        assistantJob?.cancel()
    }

    // ---- Traduction ------------------------------------------------------------------------------

    /** La traduction du document affiche (par-dessus les pages ; le document ne change pas). */
    val translation = TranslationState(
        engine, assistantEngine, scope, files,
        claude = { apiKey.takeIf { it.isNotBlank() }?.let { AssistantSettings(it, assistantModel) } },
        showMessage = { toast = it },
    )

    fun startTranslation(settings: TranslationSettings) {
        val s = session ?: return
        translation.start(s, settings)
    }

    /** Sans cle : la fenetre de Claude s'ouvre sur ses reglages. */
    fun askApiKey() {
        translation.dialogOpen = false
        assistant.open = true
        assistant.showSettings = true
    }

    /**
     * Enregistre une copie traduite du document (« Facture_DE.pdf ») a l'endroit choisi, puis
     * l'ouvre. Le document affiche ne change pas.
     */
    fun saveTranslation() {
        val s = session ?: return
        val current = translation.settings ?: return
        scope.launch {
            var stop = false
            busyCancel = { stop = true }
            busyMessage = tr(Str.TranslatePages, 1, s.pageCount)
            try {
                val all = translation.translateAll(s) { done, total ->
                    busyMessage = tr(Str.TranslatePages, (done + 1).coerceAtMost(total), total)
                    !stop
                }
                if (all == null) {
                    toast = tr(Str.TranslateStopped)
                    return@launch
                }
                if (all.values.all { it.isEmpty() }) {
                    toast = tr(Str.TranslateNothing)
                    return@launch
                }
                busyCancel = null
                busyMessage = tr(Str.TranslateWriting)
                val dst = "${files.tempDir}/traduction-${kotlin.random.Random.nextInt(1_000_000_000)}.pdf"
                val marks = s.marks.toList()
                engine.edit(s.path, s.password, dst) { doc ->
                    doc.burn(marks)
                    for (page in all.keys.sorted()) {
                        val items = all.getValue(page)
                        if (items.isNotEmpty()) doc.writeTranslation(page, items, current.target)
                    }
                }
                busyMessage = null
                val saved = files.saveAs(dst, translatedName(s.name, current.target))
                if (saved == null) {
                    files.delete(dst)
                    return@launch
                }
                toast = tr(Str.TranslateSaved, saved.name)
                runAfterConfirm { open(dst, saved.name, s.password, sourceRef = saved.ref, temporary = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TextEditException) {
                errorMessage = e.message
            } catch (e: Throwable) {
                errorMessage = tr(Str.TranslateSaveFailed, e.message ?: e::class.simpleName)
            } finally {
                busyMessage = null
                busyCancel = null
            }
        }
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

    /** Fichiers que l'utilisateur a accepte de remplacer (on ne redemande pas a chaque fois). */
    private val replaceAllowed = HashSet<String>()

    /** « Remplacer l'original ou enregistrer une copie ? » : le nom du document, pendant la question. */
    var savePrompt: String? by mutableStateOf(null)
        private set
    private var saveDecision: CompletableDeferred<SaveChoice?>? = null

    /** Reponse a [savePrompt] ; null = annuler. */
    fun answerSave(choice: SaveChoice?) {
        savePrompt = null
        saveDecision?.complete(choice)
        saveDecision = null
    }

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
    fun open(
        path: String,
        name: String,
        password: String? = null,
        sourceRef: String = path,
        /** Document cree dans l'app : pas encore enregistre nulle part (« Enregistrer » demande ou). */
        isNew: Boolean = false,
        /** [path] est une copie de travail de l'app, a effacer a la fermeture. */
        temporary: Boolean = isNew,
    ) {
        openJob?.cancel()
        passwordPrompt = null
        errorMessage = null
        busyMessage = tr(Str.Opening, name)
        openJob = scope.launch {
            try {
                val doc = engine.open(path, password)
                closeCurrent()
                session = DocumentSession(name, path, sourceRef, password, doc, scope)
                savedPath = if (isNew) null else path
                if (temporary) temps.add(path)
                translation.stop()
                pagesOpen = false
                resetAssistant()
                if (!isNew) addRecent(sourceRef, name)
                pageSelection = emptySet()
                pagesGrid = LazyGridState()
                val opened = session
                scope.launch {
                    if (doc.hasForm() && session === opened) {
                        opened?.hasForm = true
                        toast = tr(Str.FormDetected)
                    }
                }
                if (canOcr) scope.launch {
                    val pages = doc.pagesNeedingOcr()
                    if (session === opened) opened?.ocrPages = pages
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PasswordRequiredException) {
                passwordPrompt = PasswordPrompt(path, name, sourceRef, e.wrongPassword)
            } catch (e: Throwable) {
                errorMessage = tr(Str.CannotOpen, name, e.message ?: e::class.simpleName)
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

    /** Montre un petit message (plateforme : « appuyez encore pour quitter »...). */
    fun showMessage(message: String) {
        toast = message
    }

    /**
     * Bouton Retour d'Android : ferme ce qui est ouvert, du plus recent au plus ancien (saisie,
     * Claude, pages, recherche, outils), puis le document. Faux sur l'accueil : rien a fermer.
     */
    fun handleBack(): Boolean {
        val s = session
        when {
            busyMessage != null -> busyCancel?.invoke()
            s?.editingBlock != null -> s.editingBlock = null
            s?.editingField != null -> s.editingField = null
            assistant.open -> assistant.open = false
            pagesOpen -> pagesOpen = false
            s?.searchOpen == true -> s.closeSearch()
            tools.editing -> tools.enter(DockMode.Read)
            translation.active -> translation.stop()
            s != null -> runAfterConfirm(::closeDocument)
            else -> return false
        }
        return true
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
        translation.stop()
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
        performEdit(tr(Str.EditingText), session?.password) { doc -> result = doc.replaceText(block, newText) }
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
            errorMessage = e.message
            false
        } catch (e: FormFieldException) {
            errorMessage = e.message
            false
        } catch (e: NothingRecognized) {
            toast = tr(if (e.stopped) Str.OcrStopped else Str.OcrNothing)
            false
        } catch (e: Throwable) {
            errorMessage = tr(Str.EditFailed, e.message ?: e::class.simpleName)
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
        fresh.hasForm = old.hasForm
        fresh.ocrPages = old.ocrPages
        fresh.ocrBannerClosed = old.ocrBannerClosed
        old.close()
        pageSelection = pageSelection.filter { it < doc.pageCount }.toSet()
        session = fresh
        translation.documentChanged(fresh)
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
            busyMessage = tr(Str.Undoing)
            try {
                reopen(previous.path, previous.password)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                errorMessage = tr(Str.CannotUndo, e.message)
            } finally {
                busyMessage = null
            }
        }
    }

    // ---- Pages -----------------------------------------------------------------------------------

    fun rotatePages(pages: Set<Int>, delta: Int) = edit(tr(Str.Rotating)) { doc ->
        for (i in pages) doc.setRotation(i, doc.geometry(i).rotation + delta)
    }

    fun deletePages(pages: Set<Int>) {
        val s = session ?: return
        if (pages.size >= s.pageCount) {
            errorMessage = tr(Str.KeepOnePage)
            return
        }
        pageSelection = emptySet()
        edit(tr(Str.Deleting)) { doc -> doc.reorder((0 until doc.pageCount).filter { it !in pages }) }
    }

    /** Deplace les pages choisies d'un cran ([direction] = -1 ou +1) ; la selection les suit. */
    fun movePages(pages: Set<Int>, direction: Int) {
        val s = session ?: return
        val order = moveInOrder(s.pageCount, pages, direction)
        if (order == (0 until s.pageCount).toList()) return
        pageSelection = order.indices.filter { order[it] in pages }.toSet()
        edit(tr(Str.Moving)) { it.reorder(order) }
    }

    fun insertBlankPage(at: Int) {
        val s = session ?: return
        val size = s.pageSizes.getOrNull((at - 1).coerceIn(0, s.pageCount - 1)) ?: return
        pageSelection = setOf(at)
        edit(tr(Str.AddingPage)) { it.insertBlankPage(at, size.width, size.height) }
    }

    fun appendPdf() {
        scope.launch {
            val other = files.pickPdf() ?: return@launch
            editNow(tr(Str.AddingFile, other.name), session?.password) { it.appendPdf(other.path) }
        }
    }

    /** Enregistre les pages choisies dans un nouveau fichier. */
    fun extractPages(pages: Set<Int>) {
        val s = session ?: return
        scope.launch {
            busyMessage = tr(Str.Extracting)
            try {
                val dst = newTempPath()
                val marks = s.marks.toList()
                engine.edit(s.path, s.password, dst) { doc ->
                    doc.burn(marks)
                    doc.reorder(pages.sorted())
                }
                busyMessage = null
                val saved = files.saveAs(dst, s.name.removeSuffix(".pdf") + " (" + tr(Str.ExtractedSuffix) + ").pdf")
                if (saved != null) toast = tr(Str.PagesSavedIn, saved.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                errorMessage = tr(Str.ExtractFailed, e.message)
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
            val ok = editNow(tr(Str.EditingText), s.password) { doc ->
                result = doc.replaceText(block, newText)
            }
            if (ok) {
                toast = when (result) {
                    TextEditResult.SimilarFont -> tr(Str.TextChangedSimilar)
                    TextEditResult.InstalledFont -> tr(Str.TextChangedInstalled)
                    TextEditResult.ScanText -> tr(Str.TextChangedScan)
                    else -> tr(Str.TextChanged)
                }
            }
        }
    }

    /** Protege le PDF par mot de passe, ou retire la protection ([password] null). */
    fun setPassword(password: String?) {
        val label = tr(if (password == null) Str.RemovingPassword else Str.Protecting)
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
        // Document cree dans l'app : il n'existe encore nulle part.
        if (s.sourceRef.isBlank()) return saveAsNow()
        // La premiere fois : remplacer l'original, ou enregistrer une copie (« Nom_miaou.pdf ») ailleurs.
        if (s.sourceRef !in replaceAllowed) {
            val decision = CompletableDeferred<SaveChoice?>()
            saveDecision?.complete(null)
            saveDecision = decision
            savePrompt = s.name
            when (decision.await()) {
                null -> return false
                SaveChoice.Copy -> return saveAsNow(copyName(s.name))
                SaveChoice.Replace -> replaceAllowed.add(s.sourceRef)
            }
        }
        busyMessage = tr(Str.Saving)
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
            toast = tr(Str.Saved)
            return true
        }
        return saveAsNow()
    }

    fun saveAs() {
        scope.launch { saveAsNow() }
    }

    private suspend fun saveAsNow(suggestedName: String? = null): Boolean {
        if (!commitMarks()) return false
        val s = session ?: return false
        return try {
            val saved = files.saveAs(s.path, suggestedName ?: s.name) ?: return false
            s.rename(saved.name, saved.ref)
            savedPath = s.path
            // C'est le fichier choisi par l'utilisateur : les prochains enregistrements vont dedans.
            replaceAllowed.add(saved.ref)
            toast = tr(Str.SavedAs, saved.name)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            errorMessage = tr(Str.CannotSave, e.message)
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
                errorMessage = tr(Str.CannotPrint, e.message)
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
                errorMessage = tr(Str.CannotShare, e.message)
            }
        }
    }

    /** Grave les annotations en attente (et la ligne de texte en cours de modification). */
    private suspend fun commitMarks(): Boolean {
        if (!commitPendingTextEdit()) return false
        if (!commitPendingFieldEdit()) return false
        val s = session ?: return false
        return s.marks.isEmpty() || editNow(tr(Str.ApplyingChanges), s.password) {}
    }

    /** Applique la ligne en cours de modification (outil « Éditer »), si son texte a change. */
    suspend fun commitPendingTextEdit(): Boolean {
        val s = session ?: return true
        val block = s.editingBlock ?: return true
        val text = s.editingText
        s.editingBlock = null
        if (text.trim() == block.text.trim()) return true
        return editNow(tr(Str.EditingText), s.password) { it.replaceText(block, text) }
    }

    // ---- Reconnaissance de texte (OCR) ----------------------------------------------------------

    /** La plateforme sait reconnaitre le texte des scans. */
    val canOcr: Boolean get() = engine.ocrLanguages.isNotEmpty()

    /** Langues de reconnaissance proposees. */
    val ocrLanguageChoices: List<String> get() = engine.ocrLanguages

    /** Faux si la langue doit d'abord etre telechargee (Android). */
    fun ocrLanguageReady(code: String): Boolean = engine.ocrLanguageReady(code)

    /** Telephone : les langues se telechargent, tout se passe sur l'appareil. */
    val ocrOnPhone: Boolean get() = engine.isPhone

    /** Fenetre « Reconnaitre le texte » ouverte. */
    var ocrDialog by mutableStateOf(false)

    /** Langues choisies pour la reconnaissance (gardees d'une fois a l'autre). */
    var ocrLanguages by mutableStateOf(
        runCatching { files.loadSetting(SETTING_OCR_LANGUAGES) }.getOrNull().orEmpty().split(',')
            .filter { it in engine.ocrLanguages }
            .ifEmpty { defaultOcrLanguages(I18n.lang.code).filter { it in engine.ocrLanguages } },
    )
        private set

    fun chooseOcrLanguages(languages: List<String>) {
        ocrLanguages = languages
        runCatching { files.saveSetting(SETTING_OCR_LANGUAGES, languages.joinToString(",")) }
    }

    /** L'IA de l'appareil (Gemini Nano) : verifiee a l'ouverture de la fenetre de l'OCR. */
    var deviceAi by mutableStateOf(DeviceAiStatus.Unavailable)
        private set

    /** Relecture par l'IA de l'appareil demandee (gardee d'une fois a l'autre). */
    var ocrAi by mutableStateOf(runCatching { files.loadSetting(SETTING_OCR_AI) }.getOrNull() != "false")
        private set

    fun chooseOcrAi(enabled: Boolean) {
        ocrAi = enabled
        runCatching { files.saveSetting(SETTING_OCR_AI, enabled.toString()) }
    }

    fun refreshDeviceAi() {
        scope.launch { deviceAi = runCatching { engine.deviceAiStatus() }.getOrDefault(DeviceAiStatus.Unavailable) }
    }

    /** Pendant une longue operation : arreter (null si l'operation ne s'arrete pas). */
    var busyCancel: (() -> Unit)? by mutableStateOf(null)
        private set

    /** Demande d'arret de l'OCR (lue par le fil de calcul : etat partage de Compose). */
    private var ocrStop by mutableStateOf(false)

    /** Reconnait le texte des pages scannees, page par page, avec une barre de progression. */
    fun recognizeText(currentPageOnly: Boolean, autoRotate: Boolean, redo: Boolean, ai: Boolean = false) {
        val s = session ?: return
        ocrDialog = false
        val options = OcrOptions(ocrLanguages, if (currentPageOnly) listOf(s.visiblePage) else null, autoRotate, redo, ai)
        scope.launch {
            val summary = runOcr(options, report = true) ?: return@launch
            var message = trn(Str.OcrDone, summary.recognized)
            if (summary.rotated > 0) message += trn(Str.OcrRotated, summary.rotated)
            if (summary.aiLines > 0) message += trn(Str.OcrAiLines, summary.aiLines)
            toast = message
        }
    }

    /** Pour l'assistant : reconnait le texte et attend la fin (null : rien a reconnaitre). */
    suspend fun recognizeTextAwait(pages: List<Int>?): OcrSummary? =
        runOcr(OcrOptions(ocrLanguages, pages, aiProofread = ocrAi && deviceAi == DeviceAiStatus.Available), report = false)

    /**
     * Prepare l'OCR : telecharge les langues qui manquent et, si la relecture est demandee,
     * l'IA de l'appareil. Renvoie les options a utiliser, ou null (erreur deja montree).
     */
    private suspend fun prepareOcr(options: OcrOptions, report: Boolean): OcrOptions? {
        var result = options
        val missing = options.languages.filter { !engine.ocrLanguageReady(it) }
        if (missing.isNotEmpty()) {
            busyMessage = tr(Str.OcrDownloading, ocrLanguageName(missing.first()), 0)
            try {
                engine.prepareOcrLanguages(missing) { code, percent -> busyMessage = tr(Str.OcrDownloading, ocrLanguageName(code), percent) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val names = missing.filter { !engine.ocrLanguageReady(it) }.joinToString(", ") { ocrLanguageName(it) }
                if (!report) throw IllegalStateException(tr(Str.OcrDownloadFailed, names), e)
                errorMessage = tr(Str.OcrDownloadFailed, names)
                return null
            } finally {
                busyMessage = null
            }
        }
        if (options.aiProofread) {
            // On n'attend jamais Gemini Nano : s'il n'est pas pret, Android le telecharge en
            // arriere-plan (parfois seulement en Wi-Fi ou en charge) et il servira la prochaine fois.
            val status = runCatching { engine.deviceAiStatus() }.getOrDefault(DeviceAiStatus.Unavailable)
            deviceAi = status
            if (status != DeviceAiStatus.Available) {
                result = result.copy(aiProofread = false)
                if (status == DeviceAiStatus.Downloadable) scope.launch { runCatching { engine.prepareDeviceAi {} } }
                if (report && status != DeviceAiStatus.Unavailable) toast = tr(Str.OcrAiLater)
            }
        }
        return result
    }

    private fun ocrLanguageName(code: String): String = OCR_LANGUAGES.firstOrNull { it.first == code }?.second ?: code

    /**
     * Lance l'OCR dans une modification du document. [report] : les erreurs sont montrees a
     * l'utilisateur ; sinon elles remontent (l'assistant les recoit).
     */
    private suspend fun runOcr(requested: OcrOptions, report: Boolean): OcrSummary? {
        ocrStop = false
        // La preparation (telechargement d'une langue) s'arrete tout de suite avec « Arreter ».
        val stopped = CompletableDeferred<OcrOptions?>()
        busyCancel = {
            ocrStop = true
            stopped.complete(null)
        }
        try {
            // runCatching : une erreur reste ici (elle ne doit pas annuler le reste de l'app).
            val prep = scope.async { runCatching { prepareOcr(requested, report) } }
            val options = select {
                prep.onAwait { it.getOrThrow() }
                stopped.onAwait { it }
            }
            if (options == null || ocrStop) {
                prep.cancel()
                if (ocrStop && report) toast = tr(Str.OcrStopped)
                return null
            }
            var summary: OcrSummary? = null
            val block: (EditableDoc) -> Unit = { doc ->
                val result = doc.recognizeText(options) { done, total ->
                    busyMessage = tr(Str.OcrProgress, done, total)
                    !ocrStop
                }
                summary = result
                if (result.recognized == 0) throw NothingRecognized(result.cancelled || ocrStop)
            }
            val ok = if (report) {
                editNow(tr(Str.OcrPreparing), session?.password, block)
            } else {
                try {
                    performEdit(tr(Str.OcrPreparing), session?.password, block)
                    true
                } catch (e: NothingRecognized) {
                    false
                }
            }
            if (!ok) return null
            // Recompter : apres un arret ou une seule page, il en reste peut-etre (le bandeau le dit).
            session?.let { s -> s.ocrPages = runCatching { s.document.pagesNeedingOcr() }.getOrDefault(0) }
            return summary
        } finally {
            busyCancel = null
        }
    }

    // ---- Creer un PDF -----------------------------------------------------------------------------

    /** Nouveau document : une page A4 blanche, a remplir avec les outils (texte, signature...). */
    fun createBlankPdf() = runAfterConfirm {
        scope.launch { createNow(tr(Str.BlankPdfName) + ".pdf", tr(Str.CreatingDocument)) { it.insertBlankPage(0, A4_WIDTH, A4_HEIGHT) } }
    }

    /** Nouveau PDF a partir de photos ou d'images : une page par image. */
    fun createPdfFromImages() = runAfterConfirm {
        scope.launch {
            val images = pickImagesOrReport() ?: return@launch
            val label = if (images.size > 1) tr(Str.CreatingPdfImages, images.size) else tr(Str.CreatingPdf)
            createNow(tr(if (images.size > 1) Str.ImagesPdfName else Str.ImagePdfName) + ".pdf", label) { it.appendImages(images) }
        }
    }

    /** Ajoute des photos ou des images a la fin du document ouvert. */
    fun appendImages() {
        scope.launch {
            val images = pickImagesOrReport() ?: return@launch
            val s = session ?: return@launch
            if (editNow(tr(Str.AddingImages), s.password) { it.appendImages(images) }) {
                toast = trn(Str.PagesAddedAtEnd, images.size)
            }
        }
    }

    /** Le telephone sait scanner un document avec l'appareil photo. */
    val canScan: Boolean get() = files.canScan

    /** Nouveau PDF a partir d'un document scanne avec l'appareil photo. */
    fun createPdfFromScan() = runAfterConfirm {
        scope.launch {
            val scan = scanOrReport() ?: return@launch
            createNow(tr(Str.ScanPdfName) + ".pdf", tr(Str.CreatingPdf)) { it.appendPdf(scan) }
        }
    }

    /** Scanne des pages et les ajoute a la fin du document ouvert. */
    fun appendScan() {
        scope.launch {
            val scan = scanOrReport() ?: return@launch
            val s = session ?: return@launch
            if (editNow(tr(Str.AddingImages), s.password) { it.appendPdf(scan) }) {
                toast = tr(Str.ScanAdded)
                // Le document a ete rouvert : le bandeau « reconnaitre le texte » compte les nouvelles pages.
                session?.let { fresh ->
                    fresh.ocrBannerClosed = false
                    fresh.ocrPages = runCatching { fresh.document.pagesNeedingOcr() }.getOrDefault(0)
                }
            }
        }
    }

    private suspend fun scanOrReport(): String? = try {
        files.scanDocument()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        errorMessage = tr(Str.ScannerUnavailable)
        null
    }

    private suspend fun pickImagesOrReport(): List<String>? {
        val images = try {
            files.pickImages()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            errorMessage = tr(Str.CannotReadImages, e.message ?: e::class.simpleName)
            return null
        }
        return images.takeIf { it.isNotEmpty() }
    }

    private suspend fun createNow(name: String, label: String, block: (EditableDoc) -> Unit) {
        val path = "${files.tempDir}/nouveau-${kotlin.random.Random.nextInt(1_000_000_000)}.pdf"
        busyMessage = label
        try {
            engine.create(path, block)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            busyMessage = null
            errorMessage = tr(Str.CannotCreate, e.message ?: e::class.simpleName)
            return
        }
        open(path, name, sourceRef = "", isNew = true)
    }

    // ---- Formulaires ------------------------------------------------------------------------------

    /**
     * Remplit un champ : texte, choix d'une liste, case cochee ([FIELD_OFF] pour decocher) ou bouton
     * radio. [next] : le champ a ouvrir ensuite (touche Tab ou Entree), dans le document mis a jour.
     */
    fun setFieldValue(field: FormField, value: String, next: FormField? = null) {
        val s = session ?: return
        // Un autre champ etait en cours de saisie : il est enregistre dans la meme operation.
        val pending = s.editingField?.takeIf { !(it.name == field.name && it.rect == field.rect) }
        val pendingText = s.editingFieldText
        s.editingField = null
        val writePending = pending != null && pendingText != pending.value
        if (value == field.value && field.kind != FieldKind.Radio && !writePending) {
            next?.let(s::startFieldEditing)
            return
        }
        scope.launch {
            val ok = editNow(tr(Str.FillingForm), s.password) { doc ->
                if (writePending && pending != null) doc.setFieldValue(pending.name, pendingText)
                if (value != field.value || field.kind == FieldKind.Radio) doc.setFieldValue(field.name, value)
            }
            if (ok) next?.let { session?.startFieldEditing(it) }
        }
    }

    /** Ouvre la saisie d'un champ texte, apres avoir enregistre celui qui etait en cours. */
    fun startFieldEditing(field: FormField) {
        val s = session ?: return
        val pending = s.editingField
        val text = s.editingFieldText
        if (pending == null || (pending.name == field.name && pending.rect == field.rect) || text == pending.value) {
            s.startFieldEditing(field)
            return
        }
        s.editingField = null
        scope.launch {
            val ok = editNow(tr(Str.FillingForm), s.password) { it.setFieldValue(pending.name, text) }
            if (ok) session?.startFieldEditing(field)
        }
    }

    /** Remplit un champ et attend la fin (assistant). */
    suspend fun setFieldValueAwait(name: String, value: String) {
        performEdit(tr(Str.FillingForm), session?.password) { it.setFieldValue(name, value) }
    }

    /** Applique le champ en cours de saisie (formulaire), si son texte a change. */
    suspend fun commitPendingFieldEdit(): Boolean {
        val s = session ?: return true
        val field = s.editingField ?: return true
        val text = s.editingFieldText
        s.editingField = null
        if (text == field.value) return true
        return editNow(tr(Str.FillingForm), s.password) { it.setFieldValue(field.name, text) }
    }

    fun commitPendingFieldEditLater() {
        scope.launch { commitPendingFieldEdit() }
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
        const val SETTING_THEME = "theme"
        const val SETTING_RECENTS = "recents"
        const val SETTING_LANGUAGE = "language"
        const val SETTING_OCR_LANGUAGES = "ocr.languages"
        const val SETTING_OCR_AI = "ocr.ai"
        const val MAX_RECENTS = 8
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

/** Un fichier ouvert recemment : [ref] = chemin (ordinateur) ou adresse content:// (Android). */
data class RecentFile(val ref: String, val name: String)

data class PasswordPrompt(val path: String, val name: String, val sourceRef: String, val wrongPassword: Boolean)

/** Reponse a « Remplacer l'original ou enregistrer une copie ? ». */
enum class SaveChoice { Replace, Copy }

/** « Facture.pdf » -> « Facture_DE.pdf » : le nom propose pour une copie traduite. */
fun translatedName(name: String, language: String): String =
    name.replace(Regex("(?i)\\.pdf$"), "") + "_" + language.uppercase() + ".pdf"

/** « Facture.pdf » -> « Facture_miaou.pdf » : le nom propose pour une copie. */
fun copyName(name: String): String = name.replace(Regex("(?i)\\.pdf$"), "") + "_miaou.pdf"

/** La reconnaissance n'a rien trouve a faire (ou a ete arretee avant la premiere page). */
private class NothingRecognized(val stopped: Boolean) : Exception()
