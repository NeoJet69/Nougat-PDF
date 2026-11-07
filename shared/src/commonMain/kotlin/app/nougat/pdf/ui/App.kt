package app.nougat.pdf.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.nougat.pdf.viewer.RecentFile
import app.nougat.pdf.viewer.ViewerState
import kotlinx.coroutines.delay
import app.nougat.pdf.i18n.Str
import app.nougat.pdf.i18n.tr
import app.nougat.pdf.i18n.I18n
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/** Ce qui change d'une plateforme a l'autre dans l'interface. */
data class PlatformInfo(
    /** Ordinateur : glisser-deposer et raccourcis clavier disponibles. */
    val isDesktop: Boolean,
    /** Numero de version, pour « A propos » (null si inconnu). */
    val version: String? = null,
)

/**
 * L'app entiere. [onOpenFile] ouvre le selecteur de fichiers de la plateforme,
 * qui appelle ensuite [ViewerState.open] ; [onOpenRecent] rouvre un fichier recent.
 */
@Composable
fun NougatApp(
    state: ViewerState,
    platform: PlatformInfo,
    onOpenFile: () -> Unit,
    onOpenRecent: (RecentFile) -> Unit = {},
) {
    NougatTheme(state.themeMode) {
      // L'arabe s'ecrit de droite a gauche : toute l'interface suit (pas les pages du PDF).
      val direction = if (I18n.lang.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
      CompositionLocalProvider(LocalLayoutDirection provides direction) {
        val p = LocalNougatPalette.current
        Box(Modifier.fillMaxSize().nougatCanvas(p)) {
            // Sur Android, le contenu ne passe ni sous la barre d'etat ni sous la barre de navigation.
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                val session = state.session
                if (session == null) {
                    HomeScreen(state, platform, onOpenFile, onOpenRecent)
                } else {
                    // Tout l'etat de l'ecran repart de zero pour chaque version du document.
                    key(session) {
                        if (state.pagesOpen) {
                            PagesScreen(state, session)
                        } else {
                            ViewerScreen(state, session, platform, onOpenFile = { state.runAfterConfirm(onOpenFile) })
                        }
                    }
                }

                Toast(state, Modifier.align(Alignment.TopCenter).padding(top = 92.dp))

                state.busyMessage?.let { LoadingOverlay(it, state.busyCancel) }
                state.passwordPrompt?.let { prompt ->
                    PasswordDialog(
                        fileName = prompt.name,
                        wrongPassword = prompt.wrongPassword,
                        onSubmit = state::submitPassword,
                        onDismiss = state::dismissPassword,
                    )
                }
                if (state.confirmDiscard != null) {
                    ConfirmSaveDialog(session?.name ?: tr(Str.DocDefaultName), state::answerConfirm)
                }
                state.savePrompt?.let { name -> SaveChoiceDialog(name, state::answerSave) }
                if (state.languageOpen) LanguageDialog(I18n.lang, state::setLanguage) { state.languageOpen = false }
                if (state.aboutOpen) {
                    AboutDialog(platform.version, onLicenses = { state.aboutOpen = false; state.licensesOpen = true }) { state.aboutOpen = false }
                }
                if (state.licensesOpen) LicensesDialog(platform.isDesktop) { state.licensesOpen = false }
                if (state.ocrDialog && session != null) OcrDialog(state) { state.ocrDialog = false }
                if (state.translation.dialogOpen && session != null) TranslateDialog(state) { state.translation.dialogOpen = false }
                if (state.finalizeOpen && session != null) FinalizeDialog(state) { state.finalizeOpen = false }
                state.errorMessage?.let { message ->
                    AlertDialog(
                        onDismissRequest = state::dismissError,
                        confirmButton = { TextButton(onClick = state::dismissError) { Text(tr(Str.Ok)) } },
                        title = { Text(tr(Str.Oops)) },
                        text = { Text(message) },
                    )
                }
            }
        }
      }
    }
}

/** Petit message de confirmation, en pilule flottante. */
@Composable
private fun Toast(state: ViewerState, modifier: Modifier) {
    val message = state.toast
    LaunchedEffect(message) {
        if (message != null) {
            delay(2800)
            state.dismissToast()
        }
    }
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn() + slideInVertically { -it },
        exit = fadeOut() + slideOutVertically { -it },
        modifier = modifier,
    ) {
        Island(shape = RoundedCornerShape(50), padding = 6.dp, modifier = Modifier.widthIn(max = 560.dp)) {
            GradientBadge(Icons.Filled.Check, size = 30.dp, iconSize = 18.dp)
            Text(
                message ?: "",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 10.dp, end = 14.dp),
            )
        }
    }
}

@Composable
private fun LoadingOverlay(text: String, onCancel: (() -> Unit)?) {
    // Une operation rapide (cocher une case, tourner une page) ne fait pas clignoter l'ecran :
    // les clics sont bloques tout de suite, mais le voile n'apparait qu'apres un court instant.
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(250)
        visible = true
    }
    Box(
        Modifier.fillMaxSize()
            .then(if (visible) Modifier.background(Color.Black.copy(alpha = 0.28f)) else Modifier)
            // Bloque les clics pendant l'operation.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        if (visible) Island(shape = RoundedCornerShape(28.dp), padding = 0.dp) {
            Column(Modifier.padding(horizontal = 32.dp, vertical = 26.dp).widthIn(max = 320.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                NougatMascot(Modifier.size(64.dp))
                Spacer(Modifier.height(10.dp))
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
                if (onCancel != null) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onCancel) { Text(tr(Str.Stop)) }
                }
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
        title = { Text(tr(Str.PdfProtected)) },
        text = {
            Column {
                Text(tr(Str.AsksPassword, fileName))
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(tr(Str.Password)) },
                    singleLine = true,
                    isError = wrongPassword,
                    supportingText = if (wrongPassword) {
                        { Text(tr(Str.WrongPassword)) }
                    } else null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSubmit(password) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSubmit(password) }) { Text(tr(Str.Open)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(Str.Cancel)) } },
    )
}
