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
                "Lisez, éditez, signez et transformez vos PDF.\nAvec Claude intégré — et sans Internet pour tout le reste.",
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
                Text("Ouvrir un PDF", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    if (platform.isDesktop) "Glissez un fichier ici, ou choisissez-le  ·  Ctrl+O" else "Choisissez un fichier sur votre téléphone",
                    style = MaterialTheme.typography.bodyMedium,
                    color = p.muted,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(18.dp))
                GradientButton("Choisir un PDF", onClick = onOpenFile, icon = NougatIcons.Folder)
            }

            Spacer(Modifier.height(22.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.widthIn(max = 620.dp),
            ) {
                FeatureChip(NougatIcons.EditText, "Éditer le texte")
                FeatureChip(NougatIcons.Pen, "Annoter")
                FeatureChip(NougatIcons.Signature, "Signer")
                FeatureChip(NougatIcons.Pages, "Organiser")
                FeatureChip(NougatIcons.Sparkle, "Claude intégré")
            }

            if (state.recents.isNotEmpty()) {
                Spacer(Modifier.height(30.dp))
                Column(Modifier.widthIn(max = 620.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Récents", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 6.dp))
                    for (recent in state.recents.take(6)) {
                        RecentRow(recent, onOpen = { onOpenRecent(recent) }, onForget = { state.forgetRecent(recent.ref) })
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }

        // Theme, en haut a droite
        Island(Modifier.align(Alignment.TopEnd).padding(14.dp)) {
            IslandButton(NougatIcons.Contrast, "Thème : ${themeLabel(state.themeMode)}", onClick = state::cycleTheme)
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
                recent.ref.substringBeforeLast('/', "").substringAfterLast('/').ifEmpty { "Récent" },
                style = MaterialTheme.typography.labelMedium,
                color = LocalNougatPalette.current.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IslandButton(Icons.Filled.Close, "Retirer des récents", onClick = onForget, size = 40.dp, iconSize = 18.dp)
        }
    }
}
