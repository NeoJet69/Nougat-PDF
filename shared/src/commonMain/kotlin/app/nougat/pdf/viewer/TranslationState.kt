package app.nougat.pdf.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.nougat.pdf.assistant.AssistantEngine
import app.nougat.pdf.assistant.AssistantSettings
import app.nougat.pdf.engine.DeviceAiStatus
import app.nougat.pdf.engine.Paragraphs
import app.nougat.pdf.engine.PdfEngine
import app.nougat.pdf.engine.TextParagraph
import app.nougat.pdf.engine.TranslatedParagraph
import app.nougat.pdf.engine.defaultTranslationTarget
import app.nougat.pdf.engine.translationLanguage
import app.nougat.pdf.i18n.I18n
import app.nougat.pdf.i18n.Str
import app.nougat.pdf.i18n.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.time.TimeSource

/** Qui traduit : le telephone (ML Kit, hors ligne) ou Claude (en ligne, avec la cle de l'utilisateur). */
enum class TranslationEngine { Device, Claude }

/** Une traduction en cours d'affichage. [polish] : relecture par Gemini Nano (telephone). */
data class TranslationSettings(val target: String, val engine: TranslationEngine, val polish: Boolean)

/** Ou en est la traduction d'une page. */
sealed interface PageTranslation {
    data object Working : PageTranslation

    /** Telechargement d'une langue (ML Kit). */
    data class Downloading(val language: String) : PageTranslation

    class Done(val paragraphs: List<TranslatedParagraph>) : PageTranslation

    /** Rien a traduire : pas de texte (un scan pas encore reconnu, une image). */
    data object NoText : PageTranslation

    /** La page est deja dans la langue demandee. */
    data object Same : PageTranslation

    /** La langue de la page n'est pas reconnue. */
    data object Unknown : PageTranslation

    data class Failed(val message: String) : PageTranslation
}

/**
 * La traduction du document affiche : les reglages, la traduction de chaque page (faite quand la
 * page s'affiche, la page visible d'abord) et ce que l'utilisateur regarde (original ou traduction).
 * Le document n'est jamais modifie ; [ViewerState.saveTranslation] en fait une copie traduite.
 */
class TranslationState(
    private val engine: PdfEngine,
    private val assistant: AssistantEngine?,
    private val scope: CoroutineScope,
    private val files: PlatformFiles,
    /** Cle et modele de Claude, ou null sans cle. */
    private val claude: () -> AssistantSettings?,
    private val showMessage: (String) -> Unit,
) {
    /** Fenetre « Traduire le document » ouverte. */
    var dialogOpen by mutableStateOf(false)

    /** La traduction affichee, ou null. */
    var settings: TranslationSettings? by mutableStateOf(null)
        private set

    val active: Boolean get() = settings != null

    /** Montrer l'original (la traduction reste prete). */
    var showOriginal by mutableStateOf(false)

    /** La traduction de chaque page deja demandee. */
    val pages = mutableStateMapOf<Int, PageTranslation>()

    /** Paragraphes touches pour revoir l'original : (page, numero du paragraphe). */
    val peeked = mutableStateListOf<Pair<Int, Int>>()

    val canDevice: Boolean get() = engine.deviceTranslator != null
    val canClaude: Boolean get() = assistant != null

    /** Les langues proposees par le traducteur de l'appareil (toutes avec Claude). */
    val deviceLanguages: Set<String> get() = engine.deviceTranslator?.languages ?: emptySet()

    // ---- Reglages gardes d'une fois a l'autre -----------------------------------------------------

    var target by mutableStateOf(
        load(SETTING_TARGET)?.takeIf { translationLanguage(it) != null } ?: defaultTranslationTarget(I18n.lang.code),
    )
        private set
    var preferredEngine by mutableStateOf(
        TranslationEngine.entries.firstOrNull { it.name == load(SETTING_ENGINE) }
            ?.takeIf { it == TranslationEngine.Claude || canDevice }
            ?: if (canDevice) TranslationEngine.Device else TranslationEngine.Claude,
    )
        private set
    var polish by mutableStateOf(load(SETTING_POLISH) != "false")
        private set

    private fun load(key: String): String? = runCatching { files.loadSetting(key) }.getOrNull()?.takeIf { it.isNotBlank() }

    // ---- Demarrer, arreter -------------------------------------------------------------------------

    private var session: DocumentSession? = null
    private var worker: Job? = null
    private val wanted = mutableStateListOf<Int>()
    private var lastSource: String? = null
    private var aiReady = false

    fun start(s: DocumentSession, wantedSettings: TranslationSettings) {
        target = wantedSettings.target
        preferredEngine = wantedSettings.engine
        if (wantedSettings.engine == TranslationEngine.Device) polish = wantedSettings.polish
        runCatching {
            files.saveSetting(SETTING_TARGET, target)
            files.saveSetting(SETTING_ENGINE, preferredEngine.name)
            files.saveSetting(SETTING_POLISH, polish.toString())
        }
        dialogOpen = false
        showOriginal = false
        lastSource = null
        aiReady = false
        settings = wantedSettings
        reset(s)
        if (wantedSettings.engine == TranslationEngine.Device && wantedSettings.polish) {
            scope.launch {
                // On n'attend jamais Gemini Nano : s'il n'est pas pret, Android le telecharge et il
                // servira la prochaine fois.
                val status = runCatching { engine.deviceAiStatus() }.getOrDefault(DeviceAiStatus.Unavailable)
                aiReady = status == DeviceAiStatus.Available
                if (status == DeviceAiStatus.Downloadable) scope.launch { runCatching { engine.prepareDeviceAi {} } }
                if (status == DeviceAiStatus.Downloadable || status == DeviceAiStatus.Downloading) showMessage(tr(Str.TranslateAiLater))
            }
        }
    }

    fun stop() {
        settings = null
        session = null
        worker?.cancel()
        worker = null
        wanted.clear()
        pages.clear()
        peeked.clear()
        showOriginal = false
    }

    /** Le document a ete rouvert apres une modification (OCR...) : les pages se retraduisent. */
    fun documentChanged(s: DocumentSession?) {
        if (settings == null) return
        if (s == null) stop() else reset(s)
    }

    private fun reset(s: DocumentSession) {
        worker?.cancel()
        worker = null
        wanted.clear()
        pages.clear()
        peeked.clear()
        session = s
    }

    /** Toucher un paragraphe : l'original, puis de nouveau la traduction. */
    fun togglePeek(page: Int, index: Int) {
        val key = page to index
        if (!peeked.remove(key)) peeked.add(key)
    }

    // ---- Traduire les pages ---------------------------------------------------------------------------

    /** La page [page] s'affiche : la traduire (la page visible passe en premier). */
    fun want(s: DocumentSession, page: Int) {
        if (settings == null || s !== session) return
        if (pages.containsKey(page) || page in wanted) return
        wanted.add(page)
        ensureWorker()
    }

    fun retry(page: Int) {
        val s = session ?: return
        pages.remove(page)
        want(s, page)
    }

    private fun ensureWorker() {
        if (worker?.isActive == true) return
        val s = session ?: return
        val current = settings ?: return
        worker = scope.launch {
            while (true) {
                val page = wanted.toList().minByOrNull { abs(it - s.visiblePage) } ?: break
                if (!wanted.remove(page)) continue
                if (pages.containsKey(page)) continue
                translateInto(s, page, current)
            }
        }
    }

    /** Traduit la page et range le resultat dans [pages] (une erreur devient [PageTranslation.Failed]). */
    private suspend fun translateInto(s: DocumentSession, page: Int, current: TranslationSettings): PageTranslation {
        pages[page] = PageTranslation.Working
        val result = try {
            translatePage(s, page, current) { pages[page] = it }
        } catch (e: CancellationException) {
            pages.remove(page)
            throw e
        } catch (e: Throwable) {
            PageTranslation.Failed(e.message ?: e::class.simpleName ?: tr(Str.UnknownError))
        }
        if (session === s && settings == current) pages[page] = result
        return result
    }

    private suspend fun translatePage(
        s: DocumentSession,
        page: Int,
        current: TranslationSettings,
        onStep: (PageTranslation) -> Unit,
    ): PageTranslation {
        val size = s.pageSizes[page]
        val paragraphs = Paragraphs.group(page, s.textBlocks(page), size.width, size.height)
            .filter { Paragraphs.needsTranslation(it.text) }
        if (paragraphs.isEmpty()) return PageTranslation.NoText
        return when (current.engine) {
            TranslationEngine.Claude -> translateWithClaude(paragraphs, current.target)
            TranslationEngine.Device -> translateOnDevice(paragraphs, current, onStep)
        }
    }

    private suspend fun translateWithClaude(paragraphs: List<TextParagraph>, target: String): PageTranslation {
        val api = claude() ?: throw IllegalStateException(tr(Str.TranslateNeedKey))
        val engine = assistant ?: throw IllegalStateException(tr(Str.TranslateNeedKey))
        val language = translationLanguage(target)?.englishName ?: target
        val translated = engine.translate(paragraphs.map { it.text }, language, api)
        val done = paragraphs.zip(translated)
            .filter { (p, t) -> t.isNotBlank() && t.trim() != p.text.trim() }
            .map { (p, t) -> TranslatedParagraph(p, t.trim()) }
        return if (done.isEmpty()) PageTranslation.Same else PageTranslation.Done(done)
    }

    private suspend fun translateOnDevice(
        paragraphs: List<TextParagraph>,
        current: TranslationSettings,
        onStep: (PageTranslation) -> Unit,
    ): PageTranslation {
        val translator = engine.deviceTranslator ?: throw IllegalStateException(tr(Str.TranslateNeedKey))
        val target = current.target
        val texts = paragraphs.map { it.text }
        val source = translator.detect(texts.joinToString("\n").take(2000)) ?: lastSource ?: return PageTranslation.Unknown
        lastSource = source
        if (source == target) return PageTranslation.Same
        val missing = listOf(source, target).filter { !translator.ready(it) }
        if (missing.isNotEmpty()) {
            onStep(PageTranslation.Downloading(translationLanguage(missing.first())?.nativeName ?: missing.first()))
            try {
                translator.prepare(missing)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val names = missing.joinToString(", ") { translationLanguage(it)?.nativeName ?: it }
                throw IllegalStateException(tr(Str.TranslateLanguageFailed, names), e)
            }
            onStep(PageTranslation.Working)
        }
        // Un paragraphe deja dans la langue demandee (une citation, une adresse...) reste tel quel.
        val todo = paragraphs.indices.filter { i ->
            texts[i].length < 30 || translator.detect(texts[i]) != target
        }
        val drafts = translator.translate(todo.map { texts[it] }, source, target)
        val results = todo.zip(drafts).filter { (i, t) -> t.isNotBlank() && t.trim() != texts[i].trim() }
            .map { (i, t) -> i to t.trim() }.toMutableList()
        if (results.isEmpty()) return PageTranslation.Same
        if (!(current.polish && aiReady)) return PageTranslation.Done(results.map { (i, t) -> TranslatedParagraph(paragraphs[i], t) })

        // Gemini Nano relit : la traduction de ML Kit s'affiche tout de suite, puis s'ameliore.
        onStep(PageTranslation.Done(results.map { (i, t) -> TranslatedParagraph(paragraphs[i], t) }))
        val start = TimeSource.Monotonic.markNow()
        for (k in results.indices.take(MAX_POLISHED)) {
            if (start.elapsedNow().inWholeMilliseconds > POLISH_BUDGET_MS) break
            val (i, draft) = results[k]
            val better = runCatching { translator.polish(texts[i], draft, source, target) }.getOrNull() ?: continue
            results[k] = i to better
        }
        return PageTranslation.Done(results.map { (i, t) -> TranslatedParagraph(paragraphs[i], t) })
    }

    /**
     * Pour la copie traduite : la traduction de toutes les pages (celles deja faites sont reprises).
     * [progress] (pages faites, total) renvoie faux pour arreter : le resultat est alors null.
     */
    suspend fun translateAll(s: DocumentSession, progress: (Int, Int) -> Boolean): Map<Int, List<TranslatedParagraph>>? {
        val current = settings ?: return null
        worker?.cancel()
        worker = null
        val result = HashMap<Int, List<TranslatedParagraph>>()
        for (page in 0 until s.pageCount) {
            if (!progress(page, s.pageCount)) return null
            val known = pages[page]
            val done = if (known is PageTranslation.Done) known else translateInto(s, page, current)
            if (done is PageTranslation.Failed) throw IllegalStateException(done.message)
            if (done is PageTranslation.Done) result[page] = done.paragraphs
        }
        progress(s.pageCount, s.pageCount)
        // Les pages encore a l'ecran reprennent leur traduction normale.
        if (wanted.isNotEmpty()) ensureWorker()
        return result
    }

    private companion object {
        const val SETTING_TARGET = "translate.target"
        const val SETTING_ENGINE = "translate.engine"
        const val SETTING_POLISH = "translate.polish"
        const val MAX_POLISHED = 25
        const val POLISH_BUDGET_MS = 45_000L
    }
}
