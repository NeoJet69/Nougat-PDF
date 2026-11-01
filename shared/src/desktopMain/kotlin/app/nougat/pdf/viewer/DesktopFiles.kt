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
        val target = chooseFile("Enregistrer sous", FileDialog.SAVE, suggestedName) ?: return null
        val fixed = if (target.name.lowercase().endsWith(".pdf")) target else File(target.parentFile, target.name + ".pdf")
        withContext(Dispatchers.IO) { writeInPlace(File(path), fixed) }
        return SavedFile(fixed.absolutePath, fixed.name)
    }

    override suspend fun pickPdf(): SavedFile? {
        val file = chooseFile("Choisir un PDF", FileDialog.LOAD, "*.pdf") ?: return null
        return SavedFile(file.absolutePath, file.name)
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

    override fun loadSetting(key: String): String? = prefs.get(key, null)

    override fun saveSetting(key: String, value: String) {
        if (value.isEmpty()) prefs.remove(key) else prefs.put(key, value)
        prefs.flush()
    }
}
