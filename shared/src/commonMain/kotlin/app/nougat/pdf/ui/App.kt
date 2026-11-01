package app.nougat.pdf.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nougat.pdf.viewer.ViewerState

/** Ce qui change d'une plateforme a l'autre dans l'interface. */
data class PlatformInfo(
    /** Ordinateur : glisser-deposer et raccourcis clavier disponibles. */
    val isDesktop: Boolean,
)

/**
 * L'app entiere. [onOpenFile] ouvre le selecteur de fichiers de la plateforme,
 * qui appelle ensuite [ViewerState.open].
 */
@Composable
fun NougatApp(state: ViewerState, platform: PlatformInfo, onOpenFile: () -> Unit) {
    NougatTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // Sur Android, le contenu ne passe ni sous la barre d'etat ni sous la barre de navigation.
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                val session = state.session
                if (session == null) {
                    HomeScreen(platform, onOpenFile)
                } else {
                    // Tout l'etat de l'ecran repart de zero pour chaque document.
                    key(session) {
                        ViewerScreen(session, platform, onOpenFile, onClose = state::closeDocument)
                    }
                }

                state.loadingName?.let { LoadingOverlay(it) }
                state.passwordPrompt?.let { prompt ->
                    PasswordDialog(
                        fileName = prompt.name,
                        wrongPassword = prompt.wrongPassword,
                        onSubmit = state::submitPassword,
                        onDismiss = state::dismissPassword,
                    )
                }
                state.errorMessage?.let { message ->
                    AlertDialog(
                        onDismissRequest = state::dismissError,
                        confirmButton = { TextButton(onClick = state::dismissError) { Text("OK") } },
                        title = { Text("Oups") },
                        text = { Text(message) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(platform: PlatformInfo, onOpenFile: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(rememberVectorPainter(NougatIcons.NougatLogo), contentDescription = null, modifier = Modifier.size(140.dp))
        Spacer(Modifier.height(16.dp))
        Text("Nougat PDF", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        Text(
            "Lisez vos PDF, gratuitement et sans Internet.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onOpenFile) {
            Icon(NougatIcons.Folder, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("Ouvrir un PDF")
        }
        if (platform.isDesktop) {
            Spacer(Modifier.height(16.dp))
            Text(
                "ou glissez un fichier PDF dans cette fenêtre  ·  Ctrl+O",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun LoadingOverlay(name: String) {
    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            // Bloque les clics pendant l'ouverture.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
            Column(Modifier.padding(28.dp).widthIn(max = 320.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Ouverture de « $name »…", textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun PasswordDialog(
    fileName: String,
    wrongPassword: Boolean,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("PDF protégé") },
        text = {
            Column {
                Text("« $fileName » demande un mot de passe.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Mot de passe") },
                    singleLine = true,
                    isError = wrongPassword,
                    supportingText = if (wrongPassword) {
                        { Text("Mot de passe incorrect") }
                    } else null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onSubmit(password) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(password) }) { Text("Ouvrir") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
