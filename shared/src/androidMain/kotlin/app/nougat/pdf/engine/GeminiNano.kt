package app.nougat.pdf.engine

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min

/*
 * Gemini Nano : le modele d'IA de Google qui tourne sur le telephone lui-meme (AICore), sans
 * Internet ni cle. Disponible sur les telephones recents compatibles (Pixel 9 et suivants,
 * Galaxy S26...). Nougat s'en sert pour relire les lignes ou Tesseract doute : il « voit »
 * l'image de la ligne et corrige la lecture, ou lit une ligne manuscrite.
 */
internal object GeminiNano {
    private var model: GenerativeModel? = null

    @Synchronized
    private fun client(): GenerativeModel = model ?: Generation.getClient().also { model = it }

    /** Ce que permet l'appareil. */
    suspend fun status(): DeviceAiStatus = runCatching {
        // Quelques secondes au plus : sans reponse d'AICore, on fait sans Gemini Nano.
        when (withTimeoutOrNull(5_000) { client().checkStatus() }) {
            FeatureStatus.AVAILABLE -> DeviceAiStatus.Available
            FeatureStatus.DOWNLOADABLE -> DeviceAiStatus.Downloadable
            FeatureStatus.DOWNLOADING -> DeviceAiStatus.Downloading
            else -> DeviceAiStatus.Unavailable
        }
    }.getOrDefault(DeviceAiStatus.Unavailable)

    /** Telechargement deja demande (Android le poursuit seul, meme si l'app est fermee). */
    @Volatile
    private var downloading = false

    /** Telecharge le modele (Android le garde ensuite pour toutes les apps). [onBytes] : progression. */
    suspend fun download(onBytes: (Long) -> Unit): Boolean {
        if (downloading) return false
        downloading = true
        var ok = false
        runCatching {
            client().download().collect { s ->
                when (s) {
                    is DownloadStatus.DownloadProgress -> onBytes(s.totalBytesDownloaded)
                    DownloadStatus.DownloadCompleted -> ok = true
                    is DownloadStatus.DownloadFailed -> ok = false
                    else -> Unit
                }
            }
        }
        downloading = false
        return ok || status() == DeviceAiStatus.Available
    }

    /** Le relecteur de lignes, pour les langues [languages] (codes Tesseract). */
    fun lineReader(languages: List<String>): LineReader {
        val names = languages.mapNotNull { LANGUAGE_NAMES[it] }.ifEmpty { listOf("English") }.joinToString(", ")
        return LineReader { line, current ->
            // Une ligne tres longue est reduite : le modele lit mieux une image de taille moyenne.
            val image = if (line.width > 1400) line.scale(1400f / line.width) else line
            val bitmap = image.toBitmap()
            try {
                val prompt = buildString {
                    append("This image shows a single line of text from a scanned document")
                    append(" (language: ").append(names).append("). ")
                    append("Transcribe the line exactly, character by character, keeping accents, punctuation, digits and capital letters. ")
                    append("Do not translate, do not correct the author's spelling, do not add anything. ")
                    if (current.isNotBlank()) append("A basic OCR engine read: \"").append(current).append("\" (it may contain mistakes). ")
                    append("Answer with the transcribed text only, on one line.")
                }
                runBlocking {
                    withTimeoutOrNull(20_000) {
                        val response = client().generateContent(
                            generateContentRequest(ImagePart(bitmap), TextPart(prompt)) {
                                temperature = 0f
                                topK = 1
                                maxOutputTokens = min(256, current.length * 2 + 40)
                            },
                        )
                        response.candidates.firstOrNull()?.text
                    }
                }
            } finally {
                bitmap.recycle()
            }
        }
    }

    /**
     * Relit la traduction automatique [draft] de [original] (langues [from] et [to], en anglais)
     * pour un texte plus naturel. Null si la reponse est douteuse : chiffres changes, longueur
     * tres differente, refus, commentaire... La traduction de ML Kit est alors gardee.
     */
    suspend fun polishTranslation(original: String, draft: String, from: String, to: String): String? {
        if (original.length < 12 || draft.length > 600) return null
        val prompt = buildString {
            append("Here is a text in ").append(from).append(" and its machine translation into ").append(to).append(".\n\n")
            append("Original:\n").append(original).append("\n\n")
            append("Translation:\n").append(draft).append("\n\n")
            append("Rewrite the translation so that it is accurate, natural and fluent ").append(to)
            append(", faithful to the meaning of the original. Keep names, numbers, amounts, dates, codes, e-mail and web addresses exactly as they are. ")
            append("Do not add notes or explanations. Answer with the improved ").append(to).append(" translation only.")
        }
        val answer = runCatching {
            withTimeoutOrNull(15_000) {
                client().generateContent(
                    generateContentRequest(TextPart(prompt)) {
                        temperature = 0.2f
                        topK = 8
                        maxOutputTokens = 256
                    },
                ).candidates.firstOrNull()?.text
            }
        }.getOrNull()?.trim()?.removeSurrounding("\"")?.trim() ?: return null
        if (answer.isEmpty() || '\n' in answer.trimEnd()) return null
        val ratio = answer.length.toFloat() / draft.length
        if (ratio < 0.6f || ratio > 1.6f) return null
        val lower = answer.lowercase()
        if (listOf("i can't", "i cannot", "sorry", "as an ai", "translation:", "original:").any { lower.startsWith(it) || it in lower.take(40) }) return null
        // Les montants, les dates, les numeros ne doivent pas bouger.
        fun numbers(s: String) = Regex("\\d+").findAll(s).map { it.value }.sorted().toList()
        if (numbers(answer) != numbers(draft)) return null
        return answer
    }

    /** « Ce que je dois faire » : la reponse a [instructions] pour le debut de [text] (Gemini Nano lit peu). */
    suspend fun digest(text: String, instructions: String): String? = runCatching {
        withTimeoutOrNull(45_000) {
            client().generateContent(
                generateContentRequest(TextPart(instructions + "\n\nDocument:\n" + text.take(5000))) {
                    temperature = 0.2f
                    topK = 8
                    maxOutputTokens = 400
                },
            ).candidates.firstOrNull()?.text
        }
    }.getOrNull()

    /** Censure : les noms de personnes et d'entreprises de [text], par morceaux de 2500 caracteres. */
    suspend fun findNames(text: String): List<Pair<SensitiveKind, String>> {
        val result = ArrayList<Pair<SensitiveKind, String>>()
        for (chunk in text.chunked(2500)) {
            val prompt = "List every person name and every company or organization name in the text below. " +
                "Answer one per line, as P: name for a person or C: name for a company, copied exactly as in the text. " +
                "If there is none, answer NONE.\n\nText:\n" + chunk
            val answer = runCatching {
                withTimeoutOrNull(20_000) {
                    client().generateContent(
                        generateContentRequest(TextPart(prompt)) {
                            temperature = 0f
                            topK = 1
                            maxOutputTokens = 256
                        },
                    ).candidates.firstOrNull()?.text
                }
            }.getOrNull() ?: continue
            for (line in answer.lines()) {
                val t = line.trim().removePrefix("-").trim()
                val kind = when {
                    t.startsWith("P:", ignoreCase = true) -> SensitiveKind.Person
                    t.startsWith("C:", ignoreCase = true) -> SensitiveKind.Company
                    else -> continue
                }
                val name = t.substring(2).trim().trim('"')
                // Seulement ce qui est vraiment dans le texte (le modele peut inventer).
                if (name.length >= 3 && name in chunk) result.add(kind to name)
            }
        }
        return result
    }

    private val LANGUAGE_NAMES = mapOf(
        "fra" to "French", "eng" to "English", "deu" to "German", "ita" to "Italian", "spa" to "Spanish",
        "por" to "Portuguese", "nld" to "Dutch", "pol" to "Polish", "tur" to "Turkish", "rus" to "Russian",
        "ukr" to "Ukrainian", "ara" to "Arabic", "chi_sim" to "Simplified Chinese", "swe" to "Swedish",
        "ell" to "Greek", "ron" to "Romanian",
    )
}
