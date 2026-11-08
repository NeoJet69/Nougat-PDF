package app.nougat.pdf.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import app.nougat.pdf.ui.NougatApp
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import app.nougat.pdf.ui.PlatformInfo
import java.io.File
import java.io.FileOutputStream
import app.nougat.pdf.i18n.Str
import app.nougat.pdf.i18n.tr

class MainActivity : ComponentActivity(), ActivityHost {
    private val viewModel: ViewerViewModel by viewModels()

    private var createCallback: ((Uri?) -> Unit)? = null
    private var openCallback: ((Uri?) -> Unit)? = null

    private val createLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) {
        createCallback?.invoke(it)
        createCallback = null
    }
    private val openLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) {
        openCallback?.invoke(it)
        openCallback = null
    }
    private var createTextCallback: ((Uri?) -> Unit)? = null
    private val createTextLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) {
        createTextCallback?.invoke(it)
        createTextCallback = null
    }
    private var scanCallback: ((Uri?, Throwable?) -> Unit)? = null

    /**
     * Le scanner de documents de Google (ML Kit, le meme que dans ArtisanGo) : il detecte la
     * page, la prend tout seul, la recadre, la redresse et nettoie les ombres ; plusieurs pages.
     */
    private val scannerLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val uri = if (result.resultCode == RESULT_OK) {
            GmsDocumentScanningResult.fromActivityResultIntent(result.data)?.pdf?.uri
        } else {
            null
        }
        scanCallback?.invoke(uri, null)
        scanCallback = null
    }
    private var imagesCallback: ((List<Uri>) -> Unit)? = null
    private val imagesLauncher = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) {
        imagesCallback?.invoke(it)
        imagesCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel.files.host = this
        // Ouvert via « Ouvrir avec » (et pas simplement apres une rotation de l'ecran).
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            // Retour : ferme ce qui est ouvert, puis le document ; sur l'accueil, deux fois pour quitter.
            var lastBack by remember { mutableLongStateOf(0L) }
            BackHandler {
                if (!viewModel.state.handleBack()) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastBack < 2_000) {
                        finish()
                    } else {
                        lastBack = now
                        viewModel.state.showMessage(tr(Str.ExitAgain))
                    }
                }
            }
            NougatApp(
                state = viewModel.state,
                platform = PlatformInfo(isDesktop = false, version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()),
                onOpenFile = {
                    openDocument { uri ->
                        if (uri != null) {
                            keepAccess(uri)
                            viewModel.openUri(uri)
                        }
                    }
                },
                onOpenRecent = { recent ->
                    viewModel.openUri(Uri.parse(recent.ref), onFailure = { viewModel.state.forgetRecent(recent.ref) })
                },
            )
        }
    }

    /** Garde l'acces au fichier choisi, pour le rouvrir plus tard depuis « Récents ». */
    private fun keepAccess(uri: Uri) {
        val readWrite = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { contentResolver.takePersistableUriPermission(uri, readWrite) }
            .recoverCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    override fun onDestroy() {
        if (viewModel.files.host === this) viewModel.files.host = null
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.data?.let(viewModel::openUri)
    }

    // ---- ActivityHost ----------------------------------------------------------------------

    override fun createDocument(suggestedName: String, onResult: (Uri?) -> Unit) {
        createCallback = onResult
        createLauncher.launch(if (suggestedName.lowercase().endsWith(".pdf")) suggestedName else "$suggestedName.pdf")
    }

    override fun openDocument(onResult: (Uri?) -> Unit) {
        openCallback = onResult
        openLauncher.launch(arrayOf("application/pdf"))
    }

    override fun createTextDocument(suggestedName: String, onResult: (Uri?) -> Unit) {
        createTextCallback = onResult
        createTextLauncher.launch(suggestedName)
    }

    override fun openTextDocument(onResult: (Uri?) -> Unit) {
        openCallback = onResult
        openLauncher.launch(arrayOf("text/markdown", "text/x-markdown", "text/plain", "application/octet-stream"))
    }

    override fun pickImages(onResult: (List<Uri>) -> Unit) {
        imagesCallback = onResult
        imagesLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    override fun scanDocument(onResult: (Uri?, Throwable?) -> Unit) {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(this)
            .addOnSuccessListener { sender ->
                scanCallback = onResult
                scannerLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener { e -> onResult(null, e) }
    }

    override fun share(file: File, name: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fichiers", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, tr(Str.ShareTitle, name)))
    }

    override fun print(path: String, name: String) {
        val manager = getSystemService(PRINT_SERVICE) as PrintManager
        manager.print(name, FilePrintAdapter(File(path), name), null)
    }
}

/** Envoie le PDF tel quel au service d'impression d'Android. */
private class FilePrintAdapter(private val file: File, private val name: String) : PrintDocumentAdapter() {
    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal.isCanceled) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder(name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build()
        callback.onLayoutFinished(info, oldAttributes != newAttributes)
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal,
        callback: WriteResultCallback,
    ) {
        try {
            file.inputStream().use { input -> FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) } }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback.onWriteFailed(e.message)
        }
    }
}
