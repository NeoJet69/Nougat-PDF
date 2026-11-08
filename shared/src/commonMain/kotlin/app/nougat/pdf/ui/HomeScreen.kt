package app.nougat.pdf.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nougat.pdf.viewer.RecentFile
import app.nougat.pdf.viewer.ViewerState
import app.nougat.pdf.i18n.Str
import app.nougat.pdf.i18n.tr
import app.nougat.pdf.i18n.I18n

/** L'accueil : Nougat le chat, la zone de depot animee, les fichiers recents. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    state: ViewerState,
    platform: PlatformInfo,
    onOpenFile: () -> Unit,
    onOpenRecent: (RecentFile) -> Unit,
) {
    val p = LocalNougatPalette.current
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 600.dp
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(if (compact) 24.dp else 40.dp))
            NougatMascot(Modifier.size(if (compact) 120.dp else 150.dp))
            Spacer(Modifier.height(12.dp))
            Text(
                "Nougat PDF",
                style = MaterialTheme.typography.displaySmall.copy(brush = NougatGradient),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                tr(Str.Tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = p.muted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(28.dp))

            // La zone de depot
            val shape = RoundedCornerShape(30.dp)
            Column(
                Modifier
                    .widthIn(max = 620.dp)
                    .fillMaxWidth()
                    .clip(shape)
                    .marchingAnts(colors.primary.copy(alpha = 0.55f), 30.dp)
                    .clickable(onClick = onOpenFile)
                    .padding(horizontal = 24.dp, vertical = if (compact) 26.dp else 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                GradientBadge(NougatIcons.Upload, size = 68.dp, iconSize = 34.dp)
                Spacer(Modifier.height(14.dp))
                Text(tr(Str.OpenPdf), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    tr(if (platform.isDesktop) Str.DropHintDesktop else Str.DropHintPhone),
                    style = MaterialTheme.typography.bodyMedium,
                    color = p.muted,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(18.dp))
                GradientButton(tr(Str.ChoosePdf), onClick = onOpenFile, icon = NougatIcons.Folder)
            }

            // Creer un PDF (cote a cote sur grand ecran, l'une sous l'autre sur telephone)
            Spacer(Modifier.height(14.dp))
            @Composable
            fun FromImages(modifier: Modifier) = CreateCard(
                NougatIcons.Image,
                tr(if (platform.isDesktop) Str.ImagesToPdf else Str.PhotosToPdf),
                tr(if (platform.isDesktop) Str.OnePagePerImage else Str.OnePagePerPhoto),
                modifier,
                onClick = state::createPdfFromImages,
            )

            @Composable
            fun Blank(modifier: Modifier) =
                CreateCard(NougatIcons.NewDocument, tr(Str.BlankDocument), tr(Str.BlankDocumentHint), modifier, onClick = state::createBlankPdf)

            @Composable
            fun Scan(modifier: Modifier) =
                CreateCard(NougatIcons.Camera, tr(Str.ScanDocument), tr(Str.ScanDocumentHint), modifier, onClick = state::createPdfFromScan)
            @Composable
            fun FromMarkdown(modifier: Modifier) =
                CreateCard(NougatIcons.Markdown, tr(Str.MarkdownToPdf), tr(Str.MarkdownToPdfHint), modifier, onClick = state::createPdfFromMarkdown)

            @Composable
            fun FromPhone(modifier: Modifier) =
                CreateCard(NougatIcons.Phone, tr(Str.TransferHome), tr(Str.TransferHomeHint), modifier, onClick = state::openTransfer)
            val cards = 3 + (if (state.canScan) 1 else 0) + (if (state.canTransfer) 1 else 0)
            if (compact) {
                Column(Modifier.widthIn(max = 620.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.canScan) Scan(Modifier.fillMaxWidth())
                    FromImages(Modifier.fillMaxWidth())
                    Blank(Modifier.fillMaxWidth())
                    FromMarkdown(Modifier.fillMaxWidth())
                    if (state.canTransfer) FromPhone(Modifier.fillMaxWidth())
                }
            } else {
                Row(Modifier.widthIn(max = (cards * 270).dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.canScan) Scan(Modifier.weight(1f))
                    FromImages(Modifier.weight(1f))
                    Blank(Modifier.weight(1f))
                    FromMarkdown(Modifier.weight(1f))
                    if (state.canTransfer) FromPhone(Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(22.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.widthIn(max = 620.dp),
            ) {
                FeatureChip(NougatIcons.EditText, tr(Str.FeatEditText))
                FeatureChip(NougatIcons.Pen, tr(Str.FeatAnnotate))
                FeatureChip(NougatIcons.Signature, tr(Str.FeatSign))
                FeatureChip(NougatIcons.SelectAll, tr(Str.FeatForms))
                if (state.canOcr) FeatureChip(NougatIcons.Scan, tr(Str.FeatOcr))
                FeatureChip(NougatIcons.Pages, tr(Str.FeatOrganize))
                FeatureChip(NougatIcons.Sparkle, tr(Str.FeatClaude))
            }

            if (state.recents.isNotEmpty()) {
                Spacer(Modifier.height(30.dp))
                Column(Modifier.widthIn(max = 620.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(tr(Str.Recents), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 6.dp))
                    for (recent in state.recents.take(6)) {
                        RecentRow(recent, onOpen = { onOpenRecent(recent) }, onForget = { state.forgetRecent(recent.ref) })
                    }
                }
            }
            // Les auteurs (et « A propos »)
            Spacer(Modifier.height(28.dp))
            Text(
                tr(Str.MadeBy),
                style = MaterialTheme.typography.labelLarge,
                color = p.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { state.aboutOpen = true }.padding(horizontal = 14.dp, vertical = 8.dp),
            )
            Spacer(Modifier.height(24.dp))
        }

        // Langue et theme, en haut a droite
        Island(Modifier.align(Alignment.TopEnd).padding(14.dp)) {
            IslandButton(NougatIcons.Language, tr(Str.LanguageLabel, I18n.lang.nativeName), onClick = { state.languageOpen = true })
            IslandButton(NougatIcons.Contrast, tr(Str.ThemeLabel, themeLabel(state.themeMode)), onClick = state::cycleTheme)
        }
    }
}

@Composable
private fun CreateCard(icon: ImageVector, title: String, subtitle: String, modifier: Modifier, onClick: () -> Unit) {
    Island(modifier.clickable(onClick = onClick), shape = RoundedCornerShape(22.dp), padding = 10.dp) {
        GradientBadge(icon, size = 38.dp, iconSize = 20.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.labelMedium, color = LocalNougatPalette.current.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun FeatureChip(icon: ImageVector, label: String) {
    Island(shape = RoundedCornerShape(50), padding = 0.dp) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun RecentRow(recent: RecentFile, onOpen: () -> Unit, onForget: () -> Unit) {
    Island(Modifier.fillMaxWidth().clickable(onClick = onOpen), shape = RoundedCornerShape(20.dp), padding = 8.dp) {
        GradientBadge(NougatIcons.Document, size = 40.dp, iconSize = 22.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(recent.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                recent.ref.substringBeforeLast('/', "").substringAfterLast('/').ifEmpty { tr(Str.Recent) },
                style = MaterialTheme.typography.labelMedium,
                color = LocalNougatPalette.current.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IslandButton(Icons.Filled.Close, tr(Str.RemoveRecent), onClick = onForget, size = 40.dp, iconSize = 18.dp)
        }
    }
}
