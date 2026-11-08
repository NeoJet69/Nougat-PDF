package app.nougat.pdf.android

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import app.nougat.pdf.viewer.PlatformFiles
import app.nougat.pdf.viewer.SavedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/** Ce que seule l'activite sait faire (boites de dialogue du systeme, impression, partage). */
interface ActivityHost {
    fun createDocument(suggestedName: String, onResult: (Uri?) -> Unit)
    fun openDocument(onResult: (Uri?) -> Unit)

    /** Creer un fichier texte (Markdown) a l'endroit choisi. */
    fun createTextDocument(suggestedName: String, onResult: (Uri?) -> Unit)

    /** Choisir un fichier texte (Markdown). */
    fun openTextDocument(onResult: (Uri?) -> Unit)
    fun pickImages(onResult: (List<Uri>) -> Unit)

    /** Scanner de documents de Google (ML Kit) ; [onResult] recoit le PDF, null si annule ou erreur. */
    fun scanDocument(onResult: (Uri?, Throwable?) -> Unit)
    fun print(path: String, name: String)
    fun share(file: File, name: String)
}

/** Fichiers sur Android : adresses « content:// » du selecteur de fichiers, copies dans le cache. */
class AndroidFiles(private val app: Application) : PlatformFiles {
    /** L'activite au premier plan (null pendant une rotation de l'ecran). */
    var host: ActivityHost? = null

    override val tempDir: String = File(app.cacheDir, "travail").apply { mkdirs() }.absolutePath
    private val openedDir = File(app.cacheDir, "ouverts").apply { mkdirs() }

    override fun copy(src: String, dst: String) {
        File(src).copyTo(File(dst), overwrite = true)
    }

    override fun delete(path: String) {
        File(path).delete()
    }

    override suspend fun overwrite(path: String, sourceRef: String): Boolean = withContext(Dispatchers.IO) {
        if (!sourceRef.startsWith("content://")) return@withContext false
        val uri = Uri.parse(sourceRef)
        for (mode in listOf("wt", "rwt", "w")) {
            val ok = runCatching {
                app.contentResolver.openOutputStream(uri, mode)!!.use { out -> File(path).inputStream().use { it.copyTo(out) } }
            }.isSuccess
            if (ok) return@withContext true
        }
        false
    }

    override suspend fun saveAs(path: String, suggestedName: String): SavedFile? {
        val h = host ?: return null
        val uri = suspendCancellableCoroutine<Uri?> { cont -> h.createDocument(suggestedName) { cont.resume(it) } } ?: return null
        withContext(Dispatchers.IO) {
            app.contentResolver.openOutputStream(uri, "w")!!.use { out -> File(path).inputStream().use { it.copyTo(out) } }
        }
        return SavedFile(uri.toString(), displayName(uri) ?: suggestedName)
    }

    override suspend fun saveText(text: String, suggestedName: String): SavedFile? {
        val h = host ?: return null
        val uri = suspendCancellableCoroutine<Uri?> { cont -> h.createTextDocument(suggestedName) { cont.resume(it) } } ?: return null
        withContext(Dispatchers.IO) {
            app.contentResolver.openOutputStream(uri, "w")!!.use { it.write(text.toByteArray()) }
        }
        return SavedFile(uri.toString(), displayName(uri) ?: suggestedName)
    }

    override fun todayIso(): String = java.time.LocalDate.now().toString()

    /** L'appli Agenda s'ouvre sur un nouvel evenement d'une journee, deja rempli. */
    override fun addToCalendar(title: String, notes: String, dateIso: String): Boolean = runCatching {
        val start = java.time.LocalDate.parse(dateIso).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val intent = android.content.Intent(android.content.Intent.ACTION_INSERT)
            .setData(android.provider.CalendarContract.Events.CONTENT_URI)
            .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, start + 86_400_000L)
            .putExtra(android.provider.CalendarContract.EXTRA_EVENT_ALL_DAY, true)
            .putExtra(android.provider.CalendarContract.Events.TITLE, title)
            .putExtra(android.provider.CalendarContract.Events.DESCRIPTION, notes)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(intent)
        true
    }.getOrDefault(false)

    override fun copyText(text: String) {
        val clipboard = app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Nougat PDF", text))
    }

    override suspend fun pickText(): Pair<String, String>? {
        val h = host ?: return null
        val uri = suspendCancellableCoroutine<Uri?> { cont -> h.openTextDocument { cont.resume(it) } } ?: return null
        val text = withContext(Dispatchers.IO) {
            app.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        }
        return (displayName(uri) ?: "document.md") to text
    }

    override suspend fun pickPdf(): SavedFile? {
        val h = host ?: return null
        val uri = suspendCancellableCoroutine<Uri?> { cont -> h.openDocument { cont.resume(it) } } ?: return null
        return copyToCache(uri)
    }

    private val imagesDir = File(app.cacheDir, "images").apply { mkdirs() }

    /** Photos choisies dans la galerie, copiees dans le cache (le moteur lit des fichiers). */
    override suspend fun pickImages(): List<String> {
        val h = host ?: return emptyList()
        val uris = suspendCancellableCoroutine<List<Uri>> { cont -> h.pickImages { cont.resume(it) } }
        if (uris.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            imagesDir.listFiles()?.forEach { it.delete() }
            uris.mapIndexedNotNull { i, uri ->
                runCatching {
                    val target = File(imagesDir, "image-$i-${System.nanoTime()}")
                    app.contentResolver.openInputStream(uri)!!.use { src -> target.outputStream().use { src.copyTo(it) } }
                    target.absolutePath
                }.getOrNull()
            }
        }
    }

    override val canScan: Boolean = true

    private val scansDir = File(app.cacheDir, "scans").apply { mkdirs() }

    override suspend fun scanDocument(): String? {
        val h = host ?: return null
        val uri = suspendCancellableCoroutine<Uri?> { cont ->
            h.scanDocument { uri, error -> if (error != null) cont.resumeWith(Result.failure(error)) else cont.resume(uri) }
        } ?: return null
        return withContext(Dispatchers.IO) {
            scansDir.listFiles()?.forEach { it.delete() }
            val target = File(scansDir, "scan-${System.nanoTime()}.pdf")
            app.contentResolver.openInputStream(uri)!!.use { src -> target.outputStream().use { src.copyTo(it) } }
            target.absolutePath
        }
    }

    /** Copie privee d'un fichier choisi, que PDFBox et PdfRenderer savent lire. */
    suspend fun copyToCache(uri: Uri, keep: String? = null): SavedFile = withContext(Dispatchers.IO) {
        openedDir.listFiles()?.filter { it.absolutePath != keep && it.lastModified() < System.currentTimeMillis() - 60_000 }?.forEach { it.delete() }
        val target = File(openedDir, "document-${System.nanoTime()}.pdf")
        val input = app.contentResolver.openInputStream(uri) ?: error("Fichier introuvable")
        input.use { src -> target.outputStream().use { dst -> src.copyTo(dst) } }
        SavedFile(uri.toString(), displayName(uri) ?: "document.pdf", target.absolutePath)
    }

    fun displayName(uri: Uri): String? = runCatching {
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    override val canPrint: Boolean = true

    override suspend fun print(path: String, password: String?, name: String) {
        host?.print(path, name)
    }

    override val canShare: Boolean = true


    private val prefs = app.getSharedPreferences("nougat", android.content.Context.MODE_PRIVATE)

    override fun fileSize(path: String): Long = runCatching { File(path).length() }.getOrDefault(-1L)

    override fun today(): String = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.ROOT).format(java.util.Date())

    override fun loadSetting(key: String): String? = prefs.getString(key, null)

    override fun saveSetting(key: String, value: String) {
        prefs.edit().apply { if (value.isEmpty()) remove(key) else putString(key, value) }.apply()
    }

    override suspend fun share(path: String, name: String) {
        val dir = File(app.cacheDir, "partage").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").let { if (it.lowercase().endsWith(".pdf")) it else "$it.pdf" }
        val copy = File(dir, safeName)
        withContext(Dispatchers.IO) { File(path).copyTo(copy, overwrite = true) }
        host?.share(copy, safeName)
    }
}
