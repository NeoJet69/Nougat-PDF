package app.nougat.pdf.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nougat.pdf.engine.SensitiveKind
import app.nougat.pdf.i18n.Str
import app.nougat.pdf.i18n.tr
import app.nougat.pdf.i18n.trn
import app.nougat.pdf.viewer.DocumentSession
import app.nougat.pdf.viewer.ViewerState

/*
 * Censurer : la fenetre (mots a cacher, censure intelligente), puis les trouvailles en rouge sur les
 * pages, a garder ou non d'un toucher, et le bandeau pour confirmer.
 */

private val CensorRed = Color(0xFFE53935)

/** Le nom affiche d'une sorte d'information. */
fun kindLabel(kind: SensitiveKind): String = tr(
    when (kind) {
        SensitiveKind.Person -> Str.KindPerson
        SensitiveKind.Company -> Str.KindCompany
        SensitiveKind.IdNumber -> Str.KindIdNumber
        SensitiveKind.BirthDate -> Str.KindBirthDate
        SensitiveKind.Address -> Str.KindAddress
        SensitiveKind.Phone -> Str.KindPhone
        SensitiveKind.Email -> Str.KindEmail
        SensitiveKind.Account -> Str.KindAccount
        SensitiveKind.Insurance -> Str.KindInsurance
        SensitiveKind.Medical -> Str.KindMedical
        SensitiveKind.Custom -> Str.KindCustom
    },
)

/** « Censurer le document » : quoi chercher. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CensorDialog(state: ViewerState, session: DocumentSession, onDismiss: () -> Unit) {
    val censor = state.censor
    var words by remember { mutableStateOf(censor.words) }
    var kinds by remember { mutableStateOf(censor.kinds.filter { it != SensitiveKind.Custom }.toSet()) }
    var useAi by remember { mutableStateOf(censor.useAi) }
    val p = LocalNougatPalette.current
    val aiPossible = censor.claudeAvailable || state.deviceAi == app.nougat.pdf.engine.DeviceAiStatus.Available
    androidx.compose.runtime.LaunchedEffect(Unit) { state.refreshDeviceAi() }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(NougatIcons.Censor, contentDescription = null) },
        title = { Text(tr(Str.CensorTitle)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(tr(Str.CensorExplain), style = MaterialTheme.typography.bodyMedium, color = p.muted)
                if (session.ocrPages > 0) {
                    Text(tr(Str.CensorOcrNote), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { onDismiss(); state.ocrDialog = true }) { Text(tr(Str.ScanBannerButton)) }
                }
                OutlinedTextField(
                    value = words,
                    onValueChange = { words = it },
                    label = { Text(tr(Str.CensorWords)) },
                    placeholder = { Text(tr(Str.CensorWordsHint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(tr(Str.CensorSmart), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (kind in SensitiveKind.smart) {
                        val selected = kind in kinds
                        FilterChip(
                            selected = selected,
                            onClick = { kinds = if (selected) kinds - kind else kinds + kind },
                            label = { Text(kindLabel(kind)) },
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
                if (aiPossible) CheckRow(tr(Str.CensorAi), useAi) { useAi = it }
            }
        },
        confirmButton = {
            Button(
                enabled = kinds.isNotEmpty() || words.isNotBlank(),
                onClick = {
                    val chosen = if (words.isNotBlank()) kinds + SensitiveKind.Custom else kinds
                    state.startCensorSearch(chosen, words, useAi)
                },
            ) { Text(tr(Str.CensorSearch)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(Str.Cancel)) } },
    )
}

/** Le bandeau pendant la verification : combien, par sorte, tout ou rien, annuler, censurer. */
@Composable
fun CensorBar(state: ViewerState, compact: Boolean, modifier: Modifier) {
    val censor = state.censor
    if (!censor.reviewing) return
    val p = LocalNougatPalette.current
    val counts = censor.findings.groupBy { it.kind }
    Island(modifier.then(if (compact) Modifier.fillMaxWidth() else Modifier.widthIn(max = 900.dp)), shape = RoundedCornerShape(24.dp), padding = 8.dp) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(50)).background(CensorRed), contentAlignment = Alignment.Center) {
                    Icon(NougatIcons.Censor, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
                }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(
                        trn(Str.CensorFound, censor.findings.size) + " · " + trn(Str.CensorChosen, censor.selectedCount),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(tr(Str.CensorHint), style = MaterialTheme.typography.labelSmall, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IslandButton(Icons.Filled.Close, tr(Str.Cancel), onClick = censor::stop, size = 36.dp, iconSize = 18.dp)
            }
            Spacer(Modifier.height(6.dp))
            // Une puce par sorte trouvee : la toucher garde ou retire toutes ses trouvailles.
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((kind, list) in counts.entries.sortedBy { it.key.ordinal }) {
                    val chosen = list.count { it.selected }
                    FilterChip(
                        selected = chosen > 0,
                        onClick = { censor.toggleKind(kind) },
                        label = { Text("${kindLabel(kind)} $chosen/${list.size}") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = CensorRed.copy(alpha = 0.16f),
                            selectedLabelColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = censor::selectAll) { Text(tr(Str.CensorAll)) }
                TextButton(onClick = censor::selectNone) { Text(tr(Str.CensorNone)) }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(if (censor.selectedCount > 0) CensorRed else CensorRed.copy(alpha = 0.4f))
                        .clickable(enabled = censor.selectedCount > 0, onClick = state::applyCensor)
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    Text(tr(Str.CensorConfirm), style = MaterialTheme.typography.labelLarge, color = Color.White)
                }
                Spacer(Modifier.width(2.dp))
            }
        }
    }
}

/** Les trouvailles de la page : en rouge si elles seront cachees, en pointilles sinon. */
@Composable
fun CensorOverlay(state: ViewerState, page: Int) {
    val censor = state.censor
    val onPage = censor.findings.filter { it.page == page }
    if (onPage.isEmpty()) return
    val current by rememberUpdatedState(onPage)
    Canvas(
        Modifier.fillMaxSize().pointerInput(page) {
            detectTapGestures { o ->
                val x = o.x / size.width
                val y = o.y / size.height
                val margin = 0.006f
                current.firstOrNull { f -> f.rects.any { r -> x >= r.left - margin && x <= r.right + margin && y >= r.top - margin && y <= r.bottom + margin } }
                    ?.let(censor::toggle)
            }
        },
    ) {
        for (f in onPage) for (r in f.rects) {
            val topLeft = Offset(r.left * size.width - 2f, r.top * size.height - 1f)
            val box = Size((r.right - r.left) * size.width + 4f, (r.bottom - r.top) * size.height + 2f)
            if (f.selected) {
                drawRect(CensorRed.copy(alpha = 0.30f), topLeft, box)
                drawRect(CensorRed, topLeft, box, style = Stroke(width = 2f))
            } else {
                drawRect(Color.Gray, topLeft, box, style = Stroke(width = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))))
            }
        }
    }
}
