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
        when (client().checkStatus()) {
            FeatureStatus.AVAILABLE -> DeviceAiStatus.Available
            FeatureStatus.DOWNLOADABLE -> DeviceAiStatus.Downloadable
            FeatureStatus.DOWNLOADING -> DeviceAiStatus.Downloading
            else -> DeviceAiStatus.Unavailable
        }
    }.getOrDefault(DeviceAiStatus.Unavailable)

    /** Telecharge le modele (Android le garde ensuite pour toutes les apps). [onBytes] : progression. */
    suspend fun download(onBytes: (Long) -> Unit): Boolean {
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

    private val LANGUAGE_NAMES = mapOf(
        "fra" to "French", "eng" to "English", "deu" to "German", "ita" to "Italian", "spa" to "Spanish",
        "por" to "Portuguese", "nld" to "Dutch", "pol" to "Polish", "tur" to "Turkish", "rus" to "Russian",
        "ukr" to "Ukrainian", "ara" to "Arabic", "chi_sim" to "Simplified Chinese", "swe" to "Swedish",
        "ell" to "Greek", "ron" to "Romanian",
    )
}
