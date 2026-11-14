package app.nougat.pdf.viewer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.printing.PDFPageable
import java.awt.FileDialog
import java.awt.Frame
import java.awt.print.PrinterJob
import java.io.File
import java.nio.file.Files
import app.nougat.pdf.i18n.Str
import app.nougat.pdf.i18n.tr

/** Fichiers sur ordinateur : boites de dialogue Windows, copies, impression. */
class DesktopFiles(private val frame: () -> Frame?) : PlatformFiles {

    override val tempDir: String = Files.createTempDirectory("nougat-pdf").toFile().apply { deleteOnExit() }.absolutePath

    override fun copy(src: String, dst: String) {
        File(src).copyTo(File(dst), overwrite = true)
    }

    override fun delete(path: String) {
        val f = File(path)
        if (!f.delete()) f.deleteOnExit()
    }

    override suspend fun overwrite(path: String, sourceRef: String): Boolean = withContext(Dispatchers.IO) {
        // Ecriture « sur place » (sans effacer le fichier) : Windows refuse d'effacer un fichier
        // encore ouvert, alors qu'il accepte qu'on le reecrive.
        repeat(3) { attempt ->
            if (runCatching { writeInPlace(File(path), File(sourceRef)) }.isSuccess) return@withContext true
            kotlinx.coroutines.delay(300L * (attempt + 1))
        }
        false
    }

    private fun writeInPlace(src: File, dst: File) {
        src.inputStream().use { input -> java.io.FileOutputStream(dst).use { input.copyTo(it) } }
    }

    override suspend fun saveAs(path: String, suggestedName: String): SavedFile? {
        val target = chooseFile(tr(Str.SaveAs).trimEnd('…'), FileDialog.SAVE, suggestedName) ?: return null
        val fixed = if (target.name.lowercase().endsWith(".pdf")) target else File(target.parentFile, target.name + ".pdf")
        withContext(Dispatchers.IO) { writeInPlace(File(path), fixed) }
        return SavedFile(fixed.absolutePath, fixed.name)
    }

    override suspend fun saveText(text: String, suggestedName: String): SavedFile? {
        val dialog = FileDialog(frame(), tr(Str.SaveAs).trimEnd('…'), FileDialog.SAVE).apply {
            file = suggestedName
            isVisible = true
        }
        val name = dialog.file ?: return null
        val chosen = File(dialog.directory, name)
        val target = if (chosen.name.contains('.')) chosen else File(chosen.parentFile, chosen.name + ".md")
        withContext(Dispatchers.IO) { target.writeText(text) }
        return SavedFile(target.absolutePath, target.name)
    }

    override val localTransfer: LocalTransfer by lazy { DesktopTransfer() }

    /** Le scanner ou l'imprimante multifonction branche a l'ordinateur (Windows, WIA). */
    override val canScan: Boolean = WindowsScanner.supported

    override suspend fun scanDocument(): String? = WindowsScanner.scan(tempDir, frame())

    override fun todayIso(): String = java.time.LocalDate.now().toString()

    /** Un fichier .ics ouvert avec le calendrier de l'ordinateur (Outlook, Calendrier...). */
    override fun addToCalendar(title: String, notes: String, dateIso: String): Boolean = runCatching {
        val day = java.time.LocalDate.parse(dateIso)
        val start = day.toString().replace("-", "")
        val end = day.plusDays(1).toString().replace("-", "")
        val stamp = java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))
        fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")
        val ics = listOf(
            "BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Nougat PDF//EN", "BEGIN:VEVENT",
            "UID:${java.util.UUID.randomUUID()}@nougat-pdf", "DTSTAMP:$stamp",
            "DTSTART;VALUE=DATE:$start", "DTEND;VALUE=DATE:$end",
            "SUMMARY:${esc(title)}", "DESCRIPTION:${esc(notes)}",
            "BEGIN:VALARM", "TRIGGER:-P1D", "ACTION:DISPLAY", "DESCRIPTION:${esc(title)}", "END:VALARM",
            "END:VEVENT", "END:VCALENDAR",
        ).joinToString("\r\n", postfix = "\r\n")
        val file = File(tempDir, "nougat-$start-${(1000..9999).random()}.ics")
        file.writeText(ics)
        java.awt.Desktop.getDesktop().open(file)
        true
    }.getOrDefault(false)

    override fun copyText(text: String) {
        runCatching { java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null) }
    }

    override suspend fun pickText(): Pair<String, String>? {
        val dialog = FileDialog(frame(), tr(Str.ChooseMarkdown), FileDialog.LOAD).apply {
            file = "*.md;*.markdown;*.txt"
            setFilenameFilter { _, n -> n.substringAfterLast('.', "").lowercase() in setOf("md", "markdown", "txt") }
            isVisible = true
        }
        val file = dialog.files.orEmpty().firstOrNull { it.isFile } ?: return null
        return file.name to withContext(Dispatchers.IO) { file.readText() }
    }

    override suspend fun pickPdf(): SavedFile? {
        val file = chooseFile(tr(Str.ChoosePdf), FileDialog.LOAD, "*.pdf") ?: return null
        return SavedFile(file.absolutePath, file.name)
    }

    override suspend fun pickImages(): List<String> {
        val dialog = FileDialog(frame(), tr(Str.ChooseImages), FileDialog.LOAD).apply {
            isMultipleMode = true
            // Windows filtre avec ce motif, les autres systemes avec le filtre.
            file = IMAGE_EXTENSIONS.joinToString(";") { "*.$it" }
            setFilenameFilter { _, name -> name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS }
            isVisible = true
        }
        return dialog.files.orEmpty().filter { it.isFile }.map { it.absolutePath }
    }

    /** Fenetre « Ouvrir » / « Enregistrer » native, limitee aux PDF. */
    fun chooseFile(title: String, mode: Int, fileName: String): File? {
        val dialog = FileDialog(frame(), title, mode).apply {
            file = fileName
            setFilenameFilter { _, name -> name.lowercase().endsWith(".pdf") }
            isVisible = true
        }
        val name = dialog.file ?: return null
        return File(dialog.directory, name)
    }

    private companion object {
        /** Formats d'image que PDFBox sait mettre dans un PDF. */
        val IMAGE_EXTENSIONS = listOf("jpg", "jpeg", "png", "bmp", "gif")
    }

    override val canPrint: Boolean = true

    override suspend fun print(path: String, password: String?, name: String) {
        withContext(Dispatchers.IO) {
            Loader.loadPDF(File(path), password ?: "").use { doc ->
                val job = PrinterJob.getPrinterJob()
                job.jobName = name
                job.setPageable(PDFPageable(doc))
                if (job.printDialog()) job.print()
            }
        }
    }

    override val canShare: Boolean = false

    override suspend fun share(path: String, name: String) = Unit

    private val prefs = java.util.prefs.Preferences.userRoot().node("app/nougat/pdf")

    override fun fileSize(path: String): Long = runCatching { java.io.File(path).length() }.getOrDefault(-1L)

    override fun today(): String = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))

    override fun loadSetting(key: String): String? = prefs.get(key, null)

    override fun saveSetting(key: String, value: String) {
        if (value.isEmpty()) prefs.remove(key) else prefs.put(key, value)
        prefs.flush()
    }
}
