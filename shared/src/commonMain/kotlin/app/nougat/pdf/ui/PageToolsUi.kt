package app.nougat.pdf.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.nougat.pdf.engine.CompressLevel
import app.nougat.pdf.engine.NumberFormat
import app.nougat.pdf.engine.PageStamp
import app.nougat.pdf.engine.StampPosition
import app.nougat.pdf.i18n.Str
import app.nougat.pdf.i18n.tr
import app.nougat.pdf.viewer.ViewerState

/*
 * Outils de pages : compresser le document, ajouter les numeros de page et la date.
 */

/** « Compresser le document » : trois niveaux. */
@Composable
fun CompressDialog(state: ViewerState, onDismiss: () -> Unit) {
    var level by remember { mutableStateOf(CompressLevel.Medium) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(NougatIcons.Compress, contentDescription = null) },
        title = { Text(tr(Str.CompressTitle)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr(Str.CompressExplain), style = MaterialTheme.typography.bodyMedium, color = LocalNougatPalette.current.muted)
                ChoiceRow(tr(Str.CompressLight), level == CompressLevel.Light) { level = CompressLevel.Light }
                ChoiceRow(tr(Str.CompressMedium), level == CompressLevel.Medium) { level = CompressLevel.Medium }
                ChoiceRow(tr(Str.CompressStrong), level == CompressLevel.Strong) { level = CompressLevel.Strong }
            }
        },
        confirmButton = { Button(onClick = { state.compressDocument(level) }) { Text(tr(Str.CompressStart)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(Str.Cancel)) } },
    )
}

private fun positionLabel(p: StampPosition) = tr(
    when (p) {
        StampPosition.BottomCenter -> Str.PosBottomCenter
        StampPosition.BottomRight -> Str.PosBottomRight
        StampPosition.BottomLeft -> Str.PosBottomLeft
        StampPosition.TopCenter -> Str.PosTopCenter
        StampPosition.TopRight -> Str.PosTopRight
        StampPosition.TopLeft -> Str.PosTopLeft
    },
)

/** « Numeros de page et date ». */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StampDialog(state: ViewerState, onDismiss: () -> Unit) {
    val count = state.session?.pageCount ?: 1
    var numbers by remember { mutableStateOf(true) }
    var format by remember { mutableStateOf(NumberFormat.PageOf) }
    var position by remember { mutableStateOf(StampPosition.BottomCenter) }
    var first by remember { mutableStateOf("1") }
    var skipFirst by remember { mutableStateOf(false) }
    var withDate by remember { mutableStateOf(false) }
    var date by remember { mutableStateOf(state.files.today()) }
    var datePosition by remember { mutableStateOf(StampPosition.TopRight) }
    val stamp = PageStamp(
        numbers = numbers, format = format, position = position, firstNumber = first.toIntOrNull() ?: 1,
        skipFirstPage = skipFirst, date = if (withDate) date else null, datePosition = datePosition,
        pageWord = tr(Str.StampPageWord), ofWord = tr(Str.StampOfWord),
    )

    @Composable
    fun positions(selected: StampPosition, onPick: (StampPosition) -> Unit) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (p in StampPosition.entries) {
                FilterChip(
                    selected = p == selected,
                    onClick = { onPick(p) },
                    label = { Text(positionLabel(p)) },
                    leadingIcon = if (p == selected) {
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
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(NougatIcons.Numbers, contentDescription = null) },
        title = { Text(tr(Str.Stamp)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CheckRow(tr(Str.StampNumbers), numbers) { numbers = it }
                if (numbers) {
                    Text(tr(Str.StampFormat), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    // Chaque format montre son resultat sur la deuxieme page.
                    for (f in NumberFormat.entries) {
                        ChoiceRow(stamp.copy(format = f, skipFirstPage = false).numberText(1, count), format == f) { format = f }
                    }
                    Text(tr(Str.StampPosition), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    positions(position) { position = it }
                    OutlinedTextField(
                        value = first,
                        onValueChange = { v -> first = v.filter(Char::isDigit).take(5) },
                        label = { Text(tr(Str.StampFirst)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    CheckRow(tr(Str.StampSkipFirst), skipFirst) { skipFirst = it }
                }
                CheckRow(tr(Str.StampDate), withDate) { withDate = it }
                if (withDate) {
                    OutlinedTextField(value = date, onValueChange = { date = it.take(40) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    positions(datePosition) { datePosition = it }
                }
            }
        },
        confirmButton = {
            Button(enabled = numbers || (withDate && date.isNotBlank()), onClick = { state.stampDocument(stamp) }) { Text(tr(Str.StampApply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(Str.Cancel)) } },
    )
}
