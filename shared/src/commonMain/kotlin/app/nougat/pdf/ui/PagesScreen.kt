package app.nougat.pdf.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nougat.pdf.viewer.DocumentSession
import app.nougat.pdf.viewer.ViewerState
import kotlinx.coroutines.CancellationException

/** Organiser les pages : pivoter, deplacer, supprimer, extraire, ajouter. */
@Composable
fun PagesScreen(state: ViewerState, session: DocumentSession) {
    var selected by state::pageSelection
    val count = session.pageCount
    val any = selected.isNotEmpty()
    val p = LocalNougatPalette.current

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 600.dp
        LazyVerticalGrid(
            state = state.pagesGrid,
            columns = GridCells.Adaptive(if (compact) 140.dp else 170.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 96.dp, bottom = 140.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            items((0 until count).toList(), key = { it }) { index ->
                Thumbnail(session, index, index in selected) {
                    selected = if (index in selected) selected - index else selected + index
                }
            }
        }

        // En haut : retour, titre, tout selectionner, annuler (sur une ligne, sans chevauchement)
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Island(Modifier.weight(1f, fill = false)) {
                IslandButton(Icons.AutoMirrored.Filled.ArrowBack, "Retour au document", onClick = { state.pagesOpen = false })
                Column(Modifier.weight(1f, fill = false).padding(horizontal = 6.dp)) {
                    Text(
                        if (any) "${selected.size} sélectionnée${if (selected.size > 1) "s" else ""}" else "Pages",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                    )
                    Text(
                        if (any) "sur $count" else "Touchez des pages pour les choisir",
                        style = MaterialTheme.typography.labelMedium,
                        color = p.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Island {
                IslandButton(
                    NougatIcons.SelectAll,
                    if (selected.size == count) "Tout désélectionner" else "Tout sélectionner",
                    onClick = { selected = if (selected.size == count) emptySet() else (0 until count).toSet() },
                    active = selected.size == count && count > 0,
                )
                IslandButton(NougatIcons.Undo, "Annuler", onClick = state::undo, enabled = state.canUndo)
            }
        }

        // En bas : les actions
        Island(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp, start = 12.dp, end = 12.dp),
            shape = RoundedCornerShape(30.dp),
            scrollable = true,
        ) {
            PageAction(NougatIcons.RotateLeft, "Gauche", any, compact) { state.rotatePages(selected, -90) }
            PageAction(NougatIcons.RotateRight, "Droite", any, compact) { state.rotatePages(selected, 90) }
            PageAction(NougatIcons.ArrowLeft, "Avancer", any, compact) { state.movePages(selected, -1) }
            PageAction(NougatIcons.ArrowRight, "Reculer", any, compact) { state.movePages(selected, +1) }
            PageAction(NougatIcons.Extract, "Extraire", any, compact) { state.extractPages(selected) }
            PageAction(Icons.Filled.Delete, "Supprimer", any, compact, danger = true) { state.deletePages(selected) }
            IslandDivider()
            PageAction(Icons.Filled.Add, "Page blanche", true, compact) {
                state.insertBlankPage(selected.maxOrNull()?.plus(1) ?: count)
            }
            PageAction(NougatIcons.Folder, "Ajouter PDF", true, compact, onClick = state::appendPdf)
            PageAction(NougatIcons.Image, if (compact) "Photos" else "Images", true, compact, onClick = state::appendImages)
        }
    }
}

@Composable
private fun PageAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    compact: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val color = when {
        !enabled -> colors.onSurface.copy(alpha = 0.3f)
        danger -> colors.error
        else -> colors.onSurface
    }
    Column(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(3.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}

@Composable
private fun Thumbnail(session: DocumentSession, index: Int, selected: Boolean, onClick: () -> Unit) {
    val size = session.pageSizes[index]
    val image by produceState(session.cachedThumbnail(index), index) {
        try {
            value = session.thumbnail(index, 320)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
    }
    val p = LocalNougatPalette.current
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(8.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.72f).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .aspectRatio(size.aspectRatio)
                    .shadow(if (selected) 18.dp else 10.dp, shape, ambientColor = p.paperShadow, spotColor = if (selected) primary else p.paperShadow)
                    .clip(shape)
                    .background(Color.White)
                    .then(if (selected) Modifier.border(3.dp, primary, shape) else Modifier),
            ) {
                val img = image
                if (img != null) {
                    Image(img, "Page ${index + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit, filterQuality = FilterQuality.Medium)
                } else {
                    CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp), strokeWidth = 2.dp)
                }
                if (selected) {
                    GradientBadge(Icons.Filled.Check, Modifier.align(Alignment.TopEnd).padding(8.dp), size = 28.dp, iconSize = 16.dp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .clip(CircleShape)
                .background(if (selected) primary else p.island)
                .padding(horizontal = 12.dp, vertical = 3.dp),
        ) {
            Text(
                "${index + 1}",
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
