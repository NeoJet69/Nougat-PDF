package app.nougat.pdf.engine

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

/** La synthese vocale d'Android (voix Google ou du fabricant), sans Internet si la voix est installee. */
internal class AndroidSpeaker(private val context: Context) : Speaker {
    override var rate = 1f
    private var tts: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null

    private suspend fun engine(): TextToSpeech? {
        val waiting = ready ?: CompletableDeferred<Boolean>().also { deferred ->
            ready = deferred
            withContext(Dispatchers.Main) {
                tts = TextToSpeech(context) { status -> deferred.complete(status == TextToSpeech.SUCCESS) }
            }
        }
        return if (waiting.await()) tts else null
    }

    override suspend fun speak(text: String, language: String): Boolean {
        val t = engine() ?: return false
        val result = t.setLanguage(Locale.forLanguageTag(language))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) return false
        t.setSpeechRate(rate)
        val id = UUID.randomUUID().toString()
        return suspendCancellableCoroutine { cont ->
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resume(true)
                }

                @Deprecated("Remplace par onError(String, Int)")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resume(true)
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    if (utteranceId == id && cont.isActive) cont.resume(true)
                }
            })
            cont.invokeOnCancellation { t.stop() }
            t.speak(text.take(TextToSpeech.getMaxSpeechInputLength() - 1), TextToSpeech.QUEUE_FLUSH, null, id)
        }
    }

    override fun stop() {
        tts?.stop()
    }
}
