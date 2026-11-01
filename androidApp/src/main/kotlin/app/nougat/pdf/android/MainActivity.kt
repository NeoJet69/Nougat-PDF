package app.nougat.pdf.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import app.nougat.pdf.ui.NougatApp
import app.nougat.pdf.ui.PlatformInfo

class MainActivity : ComponentActivity() {
    private val viewModel: ViewerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Ouvert via « Ouvrir avec » (et pas simplement apres une rotation de l'ecran).
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) viewModel.openUri(uri)
            }
            NougatApp(
                state = viewModel.state,
                platform = PlatformInfo(isDesktop = false),
                onOpenFile = { picker.launch(arrayOf("application/pdf")) },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.data?.let(viewModel::openUri)
    }
}
