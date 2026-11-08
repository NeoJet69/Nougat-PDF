package app.nougat.pdf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.nougat.pdf.i18n.Str
import app.nougat.pdf.i18n.tr
import app.nougat.pdf.viewer.ViewerState

/** « Remplir un formulaire papier » : les champs trouves sur la page, deja remplis avec le profil. */
@Composable
fun PaperFormDialog(state: ViewerState) {
    val form = state.paperForm
    val muted = LocalNougatPalette.current.muted
    AlertDialog(
        onDismissRequest = form::reset,
        icon = { Icon(NougatIcons.EditText, contentDescription = null) },
        title = { Text(tr(Str.PaperFormTitle)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    form.searching -> {
                        Text(tr(Str.PaperFormSearching), style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    form.noText -> Text(tr(Str.PaperFormNoText), style = MaterialTheme.typography.bodyMedium)
                    form.entries.isEmpty() -> Text(tr(Str.PaperFormNone), style = MaterialTheme.typography.bodyMedium)
                    else -> {
                        Text(tr(Str.PaperFormExplain, form.page + 1), style = MaterialTheme.typography.bodyMedium, color = muted)
                        for (entry in form.entries) {
                            OutlinedTextField(
                                value = entry.value,
                                onValueChange = { entry.value = it.take(120) },
                                label = { Text(entry.field.label) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        CheckRow(tr(Str.PaperFormSave), form.saveProfile) { form.saveProfile = it }
                    }
                }
            }
        },
        confirmButton = {
            if (form.entries.isNotEmpty() && !form.searching) {
                Button(enabled = form.entries.any { it.value.isNotBlank() }, onClick = state::fillPaperForm) { Text(tr(Str.PaperFormFill)) }
            } else {
                TextButton(onClick = form::reset) { Text(tr(Str.Close)) }
            }
        },
        dismissButton = {
            if (form.entries.isNotEmpty() && !form.searching) TextButton(onClick = form::reset) { Text(tr(Str.Cancel)) }
        },
    )
}
