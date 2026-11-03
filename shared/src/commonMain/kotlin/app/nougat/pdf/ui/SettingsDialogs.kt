package app.nougat.pdf.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.nougat.pdf.i18n.Lang
import app.nougat.pdf.i18n.Str
import app.nougat.pdf.i18n.tr
import app.nougat.pdf.viewer.SaveChoice
import app.nougat.pdf.viewer.copyName
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.nougat.pdf.engine.OCR_LANGUAGES

/**
 * Premier enregistrement d'un document : remplacer le fichier d'origine, ou enregistrer une
 * copie (« Nom_miaou.pdf ») a l'endroit choisi. Null = annuler.
 */
@Composable
fun SaveChoiceDialog(name: String, onChoice: (SaveChoice?) -> Unit) {
    val p = LocalNougatPalette.current
    AlertDialog(
        onDismissRequest = { onChoice(null) },
        title = { Text(tr(Str.SaveChoiceTitle, name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(tr(Str.SaveChoiceText))
                Spacer(Modifier.height(2.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    GradientButton(tr(Str.ReplaceOriginal), onClick = { onChoice(SaveChoice.Replace) }, icon = NougatIcons.Save)
                }
                OutlinedButton(
                    onClick = { onChoice(SaveChoice.Copy) },
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Icon(NougatIcons.Folder, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(tr(Str.SaveCopy), style = MaterialTheme.typography.titleSmall)
                        Text(copyName(name), style = MaterialTheme.typography.labelMedium, color = p.muted, maxLines = 1)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onChoice(null) }) { Text(tr(Str.Cancel)) } },
    )
}

/** Les 16 langues et les 8 dialectes, chacun ecrit dans sa propre langue. */
@Composable
fun LanguageDialog(current: Lang, onPick: (Lang) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(NougatIcons.Language, contentDescription = null) },
        title = { Text(tr(Str.ChooseLanguage)) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                SectionTitle(tr(Str.Languages))
                for (lang in Lang.entries.filter { !it.dialect }) LanguageRow(lang, lang == current) { onPick(lang) }
                Spacer(Modifier.height(10.dp))
                SectionTitle(tr(Str.Dialects))
                for (lang in Lang.entries.filter { it.dialect }) LanguageRow(lang, lang == current) { onPick(lang) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr(Str.Close)) } },
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
    )
}

@Composable
private fun LanguageRow(lang: Lang, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            lang.nativeName,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}

/** Les auteurs : NeoJet, Nougat le chat et Caramel (Claude). */
@Composable
fun AboutDialog(version: String?, onLicenses: () -> Unit, onDismiss: () -> Unit) {
    val p = LocalNougatPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { NougatMascot(Modifier.size(72.dp)) },
        title = { Text("Nougat PDF") },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(tr(Str.AboutTagline), textAlign = TextAlign.Center, color = p.muted)
                if (!version.isNullOrBlank()) {
                    Text(tr(Str.Version, version), style = MaterialTheme.typography.labelMedium, color = p.muted)
                }
                Credit("NeoJet", tr(Str.AboutNeoJet))
                Credit("Nougat 🐱", tr(Str.AboutNougat))
                Credit("Caramel ✨", tr(Str.AboutCaramel))
                Text(tr(Str.AboutFreeSoftware), style = MaterialTheme.typography.labelMedium, color = p.muted, textAlign = TextAlign.Center)
            }
        },
        dismissButton = { TextButton(onClick = onLicenses) { Text(tr(Str.Licenses)) } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr(Str.Close)) } },
    )
}

@Composable
private fun Credit(name: String, role: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(role, style = MaterialTheme.typography.bodyMedium, color = LocalNougatPalette.current.muted, textAlign = TextAlign.Center)
    }
}

/** Reconnaitre le texte (OCR) : langues du document, pages, redressement. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OcrDialog(state: app.nougat.pdf.viewer.ViewerState, onDismiss: () -> Unit) {
    var languages by remember { mutableStateOf(state.ocrLanguages) }
    var currentOnly by remember { mutableStateOf(false) }
    var autoRotate by remember { mutableStateOf(true) }
    var redo by remember { mutableStateOf(false) }
    val p = LocalNougatPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(NougatIcons.Scan, contentDescription = null) },
        title = { Text(tr(Str.RecognizeText)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr(Str.OcrExplain), style = MaterialTheme.typography.bodyMedium, color = p.muted)
                Text(tr(Str.OcrLanguages), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for ((code, name) in OCR_LANGUAGES) {
                        if (code !in state.ocrLanguageChoices) continue
                        val selected = code in languages
                        FilterChip(
                            selected = selected,
                            onClick = { languages = if (selected) languages - code else languages + code },
                            label = { Text(name) },
                            leadingIcon = if (selected) {
                                { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp)) }
                            } else {
                                null
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                                selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        )
                    }
                }
                Column {
                    ChoiceRow(tr(Str.OcrPagesAll), !currentOnly) { currentOnly = false }
                    ChoiceRow(tr(Str.OcrPagesCurrent), currentOnly) { currentOnly = true }
                }
                CheckRow(tr(Str.OcrAutoRotate), autoRotate) { autoRotate = it }
                CheckRow(tr(Str.OcrRedo), redo) { redo = it }
            }
        },
        confirmButton = {
            Button(
                enabled = languages.isNotEmpty(),
                onClick = {
                    state.chooseOcrLanguages(languages)
                    state.recognizeText(currentOnly, autoRotate, redo)
                },
            ) { Text(tr(Str.OcrStart)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(Str.Cancel)) } },
    )
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
