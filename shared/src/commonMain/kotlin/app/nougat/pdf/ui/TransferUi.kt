package app.nougat.pdf.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nougat.pdf.i18n.Str
import app.nougat.pdf.i18n.tr
import app.nougat.pdf.viewer.ViewerState

/** « Echanger avec le telephone » : le code QR a scanner, et les fichiers recus. */
@Composable
fun TransferDialog(state: ViewerState) {
    val t = state.transfer
    val muted = LocalNougatPalette.current.muted
    AlertDialog(
        onDismissRequest = state::closeTransfer,
        icon = { Icon(NougatIcons.Phone, contentDescription = null) },
        title = { Text(tr(Str.TransferTitle)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val address = t.address
                if (t.noNetwork || address == null) {
                    Text(tr(Str.TransferNoNetwork), style = MaterialTheme.typography.bodyMedium)
                } else {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        QrImage(address.qr)
                    }
                    Text(tr(Str.TransferHow), style = MaterialTheme.typography.bodyMedium)
                    SelectionContainer {
                        Text(address.url, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = muted)
                    }
                    if (state.session != null) Text(tr(Str.TransferPhoneDocument), style = MaterialTheme.typography.bodySmall, color = muted)
                    Text(tr(Str.TransferFirewall), style = MaterialTheme.typography.bodySmall, color = muted)

                    Text(tr(Str.TransferReceived), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    if (t.received.isEmpty()) {
                        Text(tr(Str.TransferWaiting), style = MaterialTheme.typography.bodyMedium, color = muted)
                    } else {
                        for (file in t.received.toList().asReversed()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (file.isPdf) NougatIcons.NewDocument else NougatIcons.Image,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    file.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                                )
                                if (file.isPdf) TextButton(onClick = { state.openReceived(file) }) { Text(tr(Str.TransferOpen)) }
                            }
                        }
                        if (t.received.any { !it.isPdf }) {
                            Button(onClick = state::receivedImagesToPdf) { Text(tr(Str.TransferImagesToPdf)) }
                        }
                        Text(tr(Str.TransferSavedIn, address.folder), style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = state::closeTransfer) { Text(tr(Str.Close)) } },
    )
}

/** Le code QR, noir sur blanc, avec sa marge blanche (necessaire pour le scanner). */
@Composable
private fun QrImage(modules: List<BooleanArray>) {
    Box(Modifier.size(220.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(14.dp)) {
        Canvas(Modifier.size(192.dp)) {
            val n = modules.size
            if (n == 0) return@Canvas
            val cell = size.minDimension / n
            for (y in 0 until n) {
                val row = modules[y]
                var x = 0
                while (x < n) {
                    if (!row[x]) {
                        x++
                        continue
                    }
                    val start = x
                    while (x < n && row[x]) x++
                    drawRect(Color.Black, Offset(start * cell, y * cell), Size((x - start) * cell + 0.5f, cell + 0.5f))
                }
            }
        }
    }
}
