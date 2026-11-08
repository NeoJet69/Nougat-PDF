package app.nougat.pdf.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.nougat.pdf.assistant.AssistantEngine
import app.nougat.pdf.assistant.AssistantSettings
import app.nougat.pdf.engine.DeviceAiStatus
import app.nougat.pdf.engine.NormRect
import app.nougat.pdf.engine.PdfEngine
import app.nougat.pdf.engine.SensitiveData
import app.nougat.pdf.engine.SensitiveFinding
import app.nougat.pdf.engine.SensitiveKind
import app.nougat.pdf.engine.TextSearch

/** Une information trouvee, a cacher ou non (l'utilisateur decide). */
class CensorFinding(val page: Int, val rects: List<NormRect>, val text: String, val kind: SensitiveKind) {
    var selected by mutableStateOf(true)
}

/**
 * La censure du document affiche : ce qu'il faut chercher (reglages gardes d'une fois a l'autre),
 * puis ce qui a ete trouve, a verifier sur les pages avant de confirmer.
 */
class CensorState(
    private val engine: PdfEngine,
    private val assistant: AssistantEngine?,
    private val files: PlatformFiles,
    /** Cle et modele de Claude, ou null sans cle. */
    private val claude: () -> AssistantSettings?,
) {
    /** Fenetre « Censurer le document » ouverte. */
    var dialogOpen by mutableStateOf(false)

    /** Les trouvailles sont affichees sur les pages, en attente de confirmation. */
    var reviewing by mutableStateOf(false)
        private set

    val findings = mutableStateListOf<CensorFinding>()

    val selectedCount: Int get() = findings.count { it.selected }

    // ---- Reglages gardes -------------------------------------------------------------------------

    var kinds by mutableStateOf(
        load(SETTING_KINDS)?.split(',')?.mapNotNull { name -> SensitiveKind.entries.firstOrNull { it.name == name } }?.toSet()
            ?: SensitiveKind.smart.toSet(),
    )
        private set
    var words by mutableStateOf(load(SETTING_WORDS).orEmpty())
        private set
    var useAi by mutableStateOf(load(SETTING_AI) != "false")
        private set

    /** Claude (avec une cle) peut aider a reperer les noms. Gemini Nano est verifie a la recherche. */
    val claudeAvailable: Boolean get() = assistant != null && claude() != null

    private fun load(key: String): String? = runCatching { files.loadSetting(key) }.getOrNull()?.takeIf { it.isNotBlank() }

    fun choose(kinds: Set<SensitiveKind>, words: String, useAi: Boolean) {
        this.kinds = kinds
        this.words = words
        this.useAi = useAi
        runCatching {
            files.saveSetting(SETTING_KINDS, kinds.joinToString(",") { it.name })
            files.saveSetting(SETTING_WORDS, words)
            files.saveSetting(SETTING_AI, useAi.toString())
        }
    }

    // ---- Chercher ------------------------------------------------------------------------------------

    /**
     * Cherche dans toutes les pages. [progress] (pages faites, total) renvoie faux pour arreter.
     * Renvoie le nombre de trouvailles, ou null si l'utilisateur a arrete. [aiError] recoit le
     * message si l'IA n'a pas pu aider (les autres recherches sont gardees).
     */
    suspend fun search(s: DocumentSession, progress: (Int, Int) -> Boolean, aiError: (String) -> Unit): Int? {
        findings.clear()
        reviewing = false
        val wordList = words.split(',', ';', '\n').map { it.trim() }.filter { it.length >= 2 }
        val api = if (useAi) claude()?.takeIf { assistant != null } else null
        val deviceAi = useAi && api == null &&
            runCatching { engine.deviceAiStatus() }.getOrDefault(DeviceAiStatus.Unavailable) == DeviceAiStatus.Available
        val aiKinds = kinds.filter { it in AI_KINDS.keys }
        var aiFailed = false
        val found = ArrayList<CensorFinding>()
        for (page in 0 until s.pageCount) {
            if (!progress(page, s.pageCount)) return null
            val pageText = s.pageTextOf(page)
            if (pageText.text.isBlank()) continue
            val text = SensitiveData.linedText(pageText)
            val local = SensitiveData.detect(text, kinds, wordList).toMutableList()
            // L'IA trouve surtout les noms sans « Madame » ni « Client : » devant.
            if (!aiFailed && aiKinds.isNotEmpty() && (api != null || deviceAi)) {
                val items = try {
                    if (api != null) {
                        assistant!!.findSensitive(text, aiKinds.map { AI_KINDS.getValue(it) }, api).mapNotNull { (kind, item) ->
                            AI_KINDS.entries.firstOrNull { it.value == kind }?.key?.let { it to item }
                        }
                    } else {
                        engine.deviceFindNames(text).orEmpty().filter { it.first in kinds }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    aiFailed = true
                    aiError(e.message ?: e::class.simpleName.orEmpty())
                    emptyList()
                }
                val haystack = TextSearch.normalize(text)
                for ((kind, item) in items) {
                    val needle = TextSearch.normalize(item.replace(Regex("\\s+"), " "))
                    if (needle.length < 2) continue
                    var from = 0
                    while (true) {
                        val at = haystack.indexOf(needle, from)
                        if (at < 0) break
                        val end = at + needle.length
                        if (local.none { it.start < end && at < it.end }) local.add(SensitiveFinding(at, end, kind))
                        from = end
                    }
                }
            }
            for (f in local.sortedBy { it.start }) {
                val rects = SensitiveData.rects(pageText, f.start, f.end)
                if (rects.isNotEmpty()) found.add(CensorFinding(page, rects, text.substring(f.start, f.end), f.kind))
            }
        }
        progress(s.pageCount, s.pageCount)
        findings.addAll(found)
        reviewing = found.isNotEmpty()
        return found.size
    }

    // ---- Verifier ------------------------------------------------------------------------------------

    fun toggle(finding: CensorFinding) {
        finding.selected = !finding.selected
    }

    fun selectAll() = findings.forEach { it.selected = true }

    fun selectNone() = findings.forEach { it.selected = false }

    /** Toucher la puce d'une sorte : toutes ses trouvailles choisies, ou aucune. */
    fun toggleKind(kind: SensitiveKind) {
        val ofKind = findings.filter { it.kind == kind }
        val select = ofKind.any { !it.selected }
        ofKind.forEach { it.selected = select }
    }

    fun stop() {
        reviewing = false
        findings.clear()
    }

    private companion object {
        const val SETTING_KINDS = "censor.kinds"
        const val SETTING_WORDS = "censor.words"
        const val SETTING_AI = "censor.ai"

        /** Les sortes que l'IA sait reperer, avec leur nom pour Claude. */
        val AI_KINDS = mapOf(
            SensitiveKind.Person to "person", SensitiveKind.Company to "company", SensitiveKind.Address to "address",
            SensitiveKind.BirthDate to "birthdate", SensitiveKind.IdNumber to "idnumber", SensitiveKind.Account to "account",
            SensitiveKind.Insurance to "insurance", SensitiveKind.Medical to "medical", SensitiveKind.Phone to "phone",
            SensitiveKind.Email to "email",
        )
    }
}
