package app.nougat.pdf.engine

import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.BufferedReader
import java.io.BufferedWriter
import java.util.Base64

/**
 * Les voix de Windows (System.Speech, par PowerShell) : un seul processus qui lit une ligne
 * « langue, vitesse, texte » et repond DONE a la fin, ou NOVOICE si la langue n'a pas de voix.
 */
internal class WindowsSpeaker : Speaker {
    override var rate = 1f
    private var process: Process? = null
    private var input: BufferedWriter? = null
    private var output: BufferedReader? = null

    private fun ensure(): Boolean {
        if (process?.isAlive == true) return true
        val encoded = Base64.getEncoder().encodeToString(SCRIPT.toByteArray(Charsets.UTF_16LE))
        val p = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded)
            .redirectErrorStream(true)
            .start()
        process = p
        input = p.outputStream.bufferedWriter(Charsets.UTF_8)
        output = p.inputStream.bufferedReader(Charsets.UTF_8)
        return true
    }

    override suspend fun speak(text: String, language: String): Boolean {
        val clean = text.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim()
        if (clean.isEmpty()) return true
        synchronized(this) { ensure() }
        val writer = input ?: return false
        val reader = output ?: return false
        // Vitesse de System.Speech : -10 a 10.
        val sapiRate = ((rate - 1f) * 8f).toInt().coerceIn(-10, 10)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { stop() }
            Thread {
                val answer = try {
                    writer.write("$language\t$sapiRate\t$clean\n")
                    writer.flush()
                    var line: String?
                    do {
                        line = reader.readLine()
                    } while (line != null && line != "DONE" && line != "NOVOICE")
                    line
                } catch (e: Exception) {
                    null
                }
                if (cont.isActive) cont.resumeWith(Result.success(answer != "NOVOICE"))
            }.apply { isDaemon = true }.start()
        }
    }

    override fun stop() {
        synchronized(this) {
            process?.destroyForcibly()
            process = null
            input = null
            output = null
        }
    }

    companion object {
        val supported: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")

        private val SCRIPT = """
            ${'$'}ErrorActionPreference = 'SilentlyContinue'
            Add-Type -AssemblyName System.Speech
            [Console]::InputEncoding = [Text.Encoding]::UTF8
            [Console]::OutputEncoding = [Text.Encoding]::UTF8
            ${'$'}s = New-Object System.Speech.Synthesis.SpeechSynthesizer
            ${'$'}s.SetOutputToDefaultAudioDevice()
            while (${'$'}true) {
              ${'$'}line = [Console]::In.ReadLine()
              if (${'$'}line -eq ${'$'}null) { break }
              ${'$'}p = ${'$'}line.Split([char]9, 3)
              ${'$'}voice = ${'$'}s.GetInstalledVoices() | Where-Object { ${'$'}_.Enabled -and ${'$'}_.VoiceInfo.Culture.TwoLetterISOLanguageName -eq ${'$'}p[0] } | Select-Object -First 1
              if (${'$'}voice -eq ${'$'}null) { [Console]::Out.WriteLine('NOVOICE'); [Console]::Out.Flush(); continue }
              ${'$'}s.SelectVoice(${'$'}voice.VoiceInfo.Name)
              ${'$'}s.Rate = [int]${'$'}p[1]
              ${'$'}s.Speak(${'$'}p[2])
              [Console]::Out.WriteLine('DONE')
              [Console]::Out.Flush()
            }
        """.trimIndent()
    }
}
