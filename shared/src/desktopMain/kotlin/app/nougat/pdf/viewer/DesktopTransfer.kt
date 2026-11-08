package app.nougat.pdf.viewer

import app.nougat.pdf.engine.QrCodes
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.Executors

/**
 * Le Wi-Fi local sur l'ordinateur : un petit serveur web (celui de Java), ouvert seulement tant que
 * la fenetre est affichee, sur une adresse secrete (un jeton au hasard dans l'adresse). Les PDF et
 * les images recus sont ranges dans le dossier Telechargements.
 */
class DesktopTransfer(private val folder: File = defaultFolder()) : LocalTransfer {
    private var server: HttpServer? = null

    /** Port et jeton du serveur en marche (pour les tests). */
    internal var port = 0
        private set
    internal var token = ""
        private set

    override fun start(texts: TransferPageTexts, document: () -> Pair<String, String>?, onFile: (String, String) -> Unit): TransferAddress? {
        val host = lanAddress() ?: return null
        startServer(texts, document, onFile)
        val url = "http://$host:$port/$token/"
        return TransferAddress(url, QrCodes.encode(url), folder.absolutePath)
    }

    /** Demarre le serveur sur toutes les interfaces, port libre au hasard. */
    internal fun startServer(texts: TransferPageTexts, document: () -> Pair<String, String>?, onFile: (String, String) -> Unit) {
        stop()
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })
        val s = HttpServer.create(InetSocketAddress(0), 16)
        s.executor = Executors.newFixedThreadPool(3) { r -> Thread(r, "nougat-wifi").apply { isDaemon = true } }
        s.createContext("/") { ex ->
            try {
                handle(ex, texts, document, onFile)
            } catch (e: Exception) {
                runCatching { send(ex, 500, "text/plain", "Erreur".toByteArray()) }
            } finally {
                ex.close()
            }
        }
        // Demarre depuis un fil « daemon » : le fil du serveur l'est aussi et ne retient pas l'app a la fermeture.
        Thread { s.start() }.apply { isDaemon = true; start(); join() }
        server = s
        port = s.address.port
    }

    override fun stop() {
        server?.stop(0)
        server = null
    }

    private fun handle(ex: HttpExchange, texts: TransferPageTexts, document: () -> Pair<String, String>?, onFile: (String, String) -> Unit) {
        val path = ex.requestURI.rawPath.orEmpty()
        val prefix = "/$token"
        if (token.isEmpty() || !(path == prefix || path.startsWith("$prefix/"))) {
            send(ex, 404, "text/plain", "Introuvable".toByteArray())
            return
        }
        when (val rest = path.removePrefix(prefix)) {
            "" -> {
                ex.responseHeaders.add("Location", "$prefix/")
                send(ex, 302, "text/plain", ByteArray(0))
            }
            "/" -> send(ex, 200, "text/html; charset=utf-8", page(texts, document()?.second).toByteArray())
            "/upload" -> {
                if (ex.requestMethod != "POST") return send(ex, 405, "text/plain", ByteArray(0))
                val name = ex.requestHeaders.getFirst("X-File-Name")?.let { URLDecoder.decode(it, "UTF-8") } ?: "document"
                val saved = receive(ex, name)
                if (saved == null) {
                    send(ex, 415, "application/json", "{\"ok\":false}".toByteArray())
                } else {
                    send(ex, 200, "application/json", "{\"ok\":true}".toByteArray())
                    onFile(saved.absolutePath, saved.name)
                }
            }
            "/document" -> {
                val (file, name) = document()?.let { File(it.first) to it.second } ?: return send(ex, 404, "text/plain", ByteArray(0))
                if (!file.isFile) return send(ex, 404, "text/plain", ByteArray(0))
                val pdfName = if (name.lowercase().endsWith(".pdf")) name else "$name.pdf"
                ex.responseHeaders.add("Content-Disposition", "attachment; filename*=UTF-8''" + URLEncoder.encode(pdfName, "UTF-8").replace("+", "%20"))
                ex.responseHeaders.add("Content-Type", "application/pdf")
                ex.sendResponseHeaders(200, file.length())
                ex.responseBody.use { out -> file.inputStream().use { it.copyTo(out) } }
            }
            else -> send(ex, 404, "text/plain", "Introuvable ($rest)".toByteArray())
        }
    }

    /** Enregistre le fichier recu s'il s'agit d'un PDF, d'un JPEG ou d'un PNG ; null sinon. */
    private fun receive(ex: HttpExchange, rawName: String): File? {
        folder.mkdirs()
        val temp = File.createTempFile("nougat-wifi", ".part", folder)
        var size = 0L
        try {
            ex.requestBody.use { input ->
                temp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        size += n
                        if (size > MAX_BYTES) throw IllegalStateException("trop gros")
                        out.write(buf, 0, n)
                    }
                }
            }
            val head = temp.inputStream().use { it.readNBytes(8) }
            val extension = when {
                head.size >= 4 && head[0] == '%'.code.toByte() && head[1] == 'P'.code.toByte() && head[2] == 'D'.code.toByte() && head[3] == 'F'.code.toByte() -> "pdf"
                head.size >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte() -> "jpg"
                head.size >= 4 && head[0] == 0x89.toByte() && head[1] == 'P'.code.toByte() && head[2] == 'N'.code.toByte() && head[3] == 'G'.code.toByte() -> "png"
                else -> null
            } ?: run {
                temp.delete()
                return null
            }
            val target = freeName(safeName(rawName, extension))
            if (!temp.renameTo(target)) {
                temp.copyTo(target)
                temp.delete()
            }
            return target
        } catch (e: Exception) {
            temp.delete()
            return null
        }
    }

    /** Un nom de fichier sans chemin ni caracteres interdits, avec la bonne extension. */
    private fun safeName(raw: String, extension: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("""[<>:"|?*\u0000-\u001F]"""), "_").trim().trim('.')
            .substringBeforeLast('.').take(120).ifBlank { "document" }
        return "$base.$extension"
    }

    private fun freeName(name: String): File {
        var file = File(folder, name)
        var n = 2
        while (file.exists()) {
            file = File(folder, name.substringBeforeLast('.') + " ($n)." + name.substringAfterLast('.'))
            n++
        }
        return file
    }

    private fun send(ex: HttpExchange, code: Int, type: String, body: ByteArray) {
        ex.responseHeaders.add("Content-Type", type)
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) ex.responseBody.use { it.write(body) }
    }

    private fun page(t: TransferPageTexts, documentName: String?): String {
        fun html(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        fun js(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("<", "\\u003c") + "\""
        val download = documentName?.let {
            "<a class=\"doc\" href=\"document\">" + html(t.getDocument.replace("{0}", it)) + "</a>"
        }.orEmpty()
        return """
            <!doctype html>
            <html lang="${html(t.language)}"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>${html(t.title)}</title>
            <style>
            :root { color-scheme: light dark; --accent: #cc542c; }
            body { font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; margin: 0; padding: 24px 16px; background: #fbf7f2; color: #2a211c; }
            @media (prefers-color-scheme: dark) { body { background: #1d1916; color: #f3ece6; } .item { background: #2b2420 !important; } }
            main { max-width: 520px; margin: 0 auto; }
            h1 { font-size: 1.5rem; margin: 0 0 8px; }
            p { line-height: 1.45; opacity: .85; }
            label.pick { display: block; text-align: center; background: var(--accent); color: #fff; font-weight: 600; font-size: 1.1rem; padding: 18px; border-radius: 18px; margin: 20px 0; cursor: pointer; }
            input[type=file] { display: none; }
            .item { background: #fff; border-radius: 14px; padding: 12px 14px; margin: 8px 0; }
            .bar { height: 6px; background: rgba(127,127,127,.25); border-radius: 3px; margin-top: 8px; overflow: hidden; }
            .bar > div { height: 100%; width: 0; background: var(--accent); transition: width .2s; }
            .ok { color: #2e7d32; } .ko { color: #c62828; }
            a.doc { display: block; text-align: center; padding: 14px; border: 2px solid var(--accent); color: var(--accent); border-radius: 18px; text-decoration: none; font-weight: 600; margin-top: 12px; }
            </style></head>
            <body><main>
            <h1>${html(t.title)}</h1>
            <p>${html(t.intro)}</p>
            <label class="pick">${html(t.choose)}<input id="files" type="file" multiple accept="application/pdf,image/jpeg,image/png"></label>
            <div id="list"></div>
            $download
            </main>
            <script>
            const T = { sending: ${js(t.sending)}, sent: ${js(t.sent)}, failed: ${js(t.failed)} };
            const list = document.getElementById('list');
            document.getElementById('files').addEventListener('change', async (e) => {
              for (const file of e.target.files) { await upload(file); }
              e.target.value = '';
            });
            function upload(file) {
              return new Promise((resolve) => {
                const item = document.createElement('div'); item.className = 'item';
                const label = document.createElement('div'); label.textContent = T.sending.replace('{0}', file.name);
                const bar = document.createElement('div'); bar.className = 'bar'; const fill = document.createElement('div'); bar.appendChild(fill);
                item.append(label, bar); list.prepend(item);
                const x = new XMLHttpRequest();
                x.open('POST', 'upload');
                x.setRequestHeader('X-File-Name', encodeURIComponent(file.name));
                x.upload.onprogress = (ev) => { if (ev.lengthComputable) fill.style.width = (100 * ev.loaded / ev.total) + '%'; };
                x.onload = () => {
                  const ok = x.status === 200;
                  label.textContent = (ok ? T.sent : T.failed).replace('{0}', file.name);
                  label.className = ok ? 'ok' : 'ko'; bar.remove(); resolve();
                };
                x.onerror = () => { label.textContent = T.failed.replace('{0}', file.name); label.className = 'ko'; bar.remove(); resolve(); };
                x.send(file);
              });
            }
            </script>
            </body></html>
        """.trimIndent()
    }

    companion object {
        /** Au-dela, le fichier est refuse (300 Mo). */
        const val MAX_BYTES = 300L * 1024 * 1024

        fun defaultFolder(): File = File(System.getProperty("user.home"), "Downloads").let { if (it.isDirectory) it else File(System.getProperty("user.home")) }

        /** L'adresse de l'ordinateur sur le reseau de la maison (192.168..., 10..., 172.16-31...). */
        fun lanAddress(): String? {
            val skip = listOf("virtual", "vmware", "vbox", "hyper-v", "vethernet", "docker", "wsl", "loopback", "tailscale", "zerotier", "vpn")
            val candidates = runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())
                .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }
                .filter { ni -> val n = (ni.displayName + " " + ni.name).lowercase(); skip.none { it in n } }
                .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { ni to it } }
                .filter { (_, a) -> a.isSiteLocalAddress }
            // Le Wi-Fi ou l'Ethernet d'abord.
            val preferred = candidates.sortedBy { (ni, _) ->
                val n = (ni.displayName + " " + ni.name).lowercase()
                if ("wi-fi" in n || "wifi" in n || "wlan" in n || "wireless" in n) 0 else if ("ethernet" in n || n.startsWith("eth") || n.startsWith("en")) 1 else 2
            }
            return preferred.firstOrNull()?.second?.hostAddress
        }
    }
}
