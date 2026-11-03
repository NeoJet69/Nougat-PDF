package app.nougat.pdf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nougat.pdf.i18n.Str
import app.nougat.pdf.i18n.tr
import app.nougat.pdf.legal.Licenses
import app.nougat.pdf.legal.Shipped

/**
 * « A propos → Licences » : la licence de Nougat PDF (MIT), puis chaque composant livre avec
 * l'app, sa licence et ses avis (NOTICE...), puis les textes complets. Toucher une ligne
 * deplie ses textes, en anglais (leur langue officielle) et selectionnables.
 */
@Composable
fun LicensesDialog(desktop: Boolean, onDismiss: () -> Unit) {
    val components = remember(desktop) { Licenses.components(desktop) }
    var open by remember { mutableStateOf<String?>(null) }
    fun toggle(key: String) {
        open = if (open == key) null else key
    }
    val p = LocalNougatPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(Str.Licenses)) },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tr(Str.LicensesIntro), style = MaterialTheme.typography.bodyMedium, color = p.muted)
                Spacer(Modifier.height(10.dp))
                LicenseRow("Nougat PDF", Licenses.NOUGAT_LICENSE, "NeoJet69", null, null, open == "nougat") { toggle("nougat") }
                if (open == "nougat") LegalText(Licenses.nougat)
                Heading(tr(Str.LicensesComponents))
                for (c in components) {
                    val tag = when (c.shipped) {
                        Shipped.Windows -> "Windows"
                        Shipped.Android -> "Android"
                        Shipped.Everywhere -> null
                    }
                    LicenseRow(c.name, c.license, c.holders, c.url, tag, open == c.name) { toggle(c.name) }
                    if (open == c.name) {
                        c.details?.let { LegalText(it, mono = false) }
                        for (t in c.texts) LegalText(t)
                        if (c.license.startsWith("Apache-2.0")) LegalText("Licensed under the Apache License, Version 2.0 (full text below).", mono = false)
                    }
                }
                Heading(tr(Str.LicensesFullTexts))
                for (f in Licenses.fullTexts) {
                    LicenseRow(f.title, null, null, null, null, open == f.title) { toggle(f.title) }
                    if (open == f.title) LegalText(f.text)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr(Str.Close)) } },
    )
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun LicenseRow(
    name: String,
    license: String?,
    holders: String?,
    url: String?,
    tag: String?,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val p = LocalNougatPalette.current
    val uri = LocalUriHandler.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            if (license != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(license, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    if (tag != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            tag,
                            style = MaterialTheme.typography.labelSmall,
                            color = p.muted,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(p.muted.copy(alpha = 0.12f))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            if (holders != null) Text("© $holders", style = MaterialTheme.typography.bodySmall, color = p.muted)
            if (url != null && expanded) {
                Text(
                    url,
                    style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { runCatching { uri.openUri(url) } },
                )
            }
        }
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = p.muted,
            modifier = Modifier.rotate(if (expanded) 180f else 0f),
        )
    }
}

/** Un texte juridique, tel quel (police a chasse fixe : les textes sont mis en forme pour elle). */
@Composable
private fun LegalText(text: String, mono: Boolean = true) {
    val p = LocalNougatPalette.current
    SelectionContainer {
        Text(
            text.trimEnd(),
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                fontSize = if (mono) 10.sp else 12.sp,
                lineHeight = if (mono) 13.sp else 16.sp,
                textDirection = TextDirection.Ltr,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp))
                .background(p.muted.copy(alpha = 0.08f)).padding(10.dp),
        )
    }
}
