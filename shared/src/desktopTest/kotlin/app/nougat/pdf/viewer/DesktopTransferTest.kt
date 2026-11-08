package app.nougat.pdf.viewer

import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopTransferTest {
    private val folder = createTempDirectory("nougat-wifi").toFile()
    private val transfer = DesktopTransfer(folder)
    private val received = CopyOnWriteArrayList<Pair<String, String>>()
    private val texts = TransferPageTexts("fr", "Envoyer à Nougat PDF", "Choisissez des fichiers.", "Choisir", "Envoi de {0}…", "{0} est arrivé.", "{0} a échoué.", "Télécharger {0}")
    private val pdf = "%PDF-1.4\n1 0 obj << >> endobj\ntrailer << >>\n%%EOF\n".toByteArray()

    @AfterTest
    fun stop() = transfer.stop()

    private fun url(path: String) = URI("http://127.0.0.1:${transfer.port}/${transfer.token}$path").toURL()

    private fun post(path: String, name: String, body: ByteArray): Int {
        val c = url(path).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("X-File-Name", URLEncoder.encode(name, "UTF-8").replace("+", "%20"))
        c.outputStream.use { it.write(body) }
        return c.responseCode.also { c.disconnect() }
    }

    @Test
    fun receivesAPdfAndRefusesTheRest() {
        val doc = File(folder, "ouvert.pdf").apply { writeBytes(pdf) }
        transfer.startServer(texts, { doc.path to "Devis Müller.pdf" }) { path, name -> received += path to name }

        val page = url("/").openStream().use { it.readBytes().decodeToString() }
        assertTrue("Envoyer à Nougat PDF" in page && "Télécharger Devis Müller.pdf" in page, page)

        assertEquals(200, post("/upload", "../../Facture été.pdf", pdf))
        assertEquals(1, received.size)
        val (path, name) = received.single()
        assertEquals("Facture été.pdf", name, "le chemin est retire du nom")
        assertEquals(folder.canonicalPath, File(path).parentFile.canonicalPath)
        assertContentEquals(pdf, File(path).readBytes())

        assertEquals(200, post("/upload", "Facture été.pdf", pdf))
        assertEquals("Facture été (2).pdf", received.last().second, "un nom deja pris recoit un numero")

        assertEquals(415, post("/upload", "virus.exe", "MZ\u0090\u0000".toByteArray()))
        assertEquals(2, received.size)
        assertTrue(folder.listFiles()!!.none { it.name.endsWith(".part") || it.name.endsWith(".exe") })

        val download = url("/document").openStream().use { it.readBytes() }
        assertContentEquals(pdf, download)
    }

    @Test
    fun wrongTokenIsRefused() {
        transfer.startServer(texts, { null }) { _, _ -> }
        val c = URI("http://127.0.0.1:${transfer.port}/mauvais-jeton/").toURL().openConnection() as HttpURLConnection
        assertEquals(404, c.responseCode)
        val d = url("/document").openConnection() as HttpURLConnection
        assertEquals(404, d.responseCode, "pas de document ouvert")
    }
}
