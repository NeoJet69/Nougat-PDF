package app.nougat.pdf.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.nougat.pdf.viewer.DocumentSession
import app.nougat.pdf.viewer.ViewerState
import kotlinx.coroutines.CancellationException

/** Organiser les pages : pivoter, deplacer, supprimer, extraire, ajouter. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PagesScreen(state: ViewerState, session: DocumentSession) {
    var selected by remember(session) { mutableStateOf(emptySet<Int>()) }
    val count = session.pageCount
    val any = selected.isNotEmpty()

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = {
                Text(
                    if (any) "${selected.size} sélectionnée${if (selected.size > 1) "s" else ""}" else "Pages ($count)",
                    style = MaterialTheme.typography.titleMedium,
                )
            },
            navigationIcon = {
                IconButton(onClick = { state.pagesOpen = false }) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Retour au document")
                }
            },
            actions = {
                IconButton(onClick = { selected = if (selected.size == count) emptySet() else (0 until count).toSet() }) {
                    Icon(NougatIcons.SelectAll, contentDescription = "Tout sélectionner")
                }
                IconButton(onClick = state::undo, enabled = state.canUndo) {
                    Icon(NougatIcons.Undo, contentDescription = "Annuler")
                }
            },
            windowInsets = WindowInsets(0, 0, 0, 0),
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        )

        // Actions sur la selection
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
                .horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionButton(NougatIcons.RotateLeft, "Pivoter à gauche", any) { state.rotatePages(selected, -90) }
            ActionButton(NougatIcons.RotateRight, "Pivoter à droite", any) { state.rotatePages(selected, 90) }
            ActionButton(NougatIcons.ArrowLeft, "Avancer", any) { selected = state.movePages(selected, -1) }
            ActionButton(NougatIcons.ArrowRight, "Reculer", any) { selected = state.movePages(selected, +1) }
            ActionButton(NougatIcons.Extract, "Extraire", any) { state.extractPages(selected) }
            ActionButton(Icons.Filled.Delete, "Supprimer", any, danger = true) {
                state.deletePages(selected)
                selected = emptySet()
            }
        }
        HorizontalDivider()

        LazyVerticalGrid(
            columns = GridCells.Adaptive(132.dp),
            modifier = Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items((0 until count).toList(), key = { it }) { index ->
                Thumbnail(session, index, index in selected) {
                    selected = if (index in selected) selected - index else selected + index
                }
            }
        }

        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            OutlinedButton(onClick = {
                val after = selected.maxOrNull()?.plus(1) ?: count
                state.insertBlankPage(after)
            }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("  Page blanche")
            }
            OutlinedButton(onClick = state::appendPdf) {
                Icon(NougatIcons.Folder, contentDescription = null)
                Text("  Ajouter un PDF")
            }
        }
    }
}

@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val color = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
        danger -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        Modifier.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
private fun Thumbnail(session: DocumentSession, index: Int, selected: Boolean, onClick: () -> Unit) {
    val size = session.pageSizes[index]
    val image by produceState(session.cachedThumbnail(index), index) {
        try {
            value = session.thumbnail(index, 280)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
    }
    val primary = MaterialTheme.colorScheme.primary
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.75f)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .aspectRatio(size.aspectRatio)
                    .shadow(if (selected) 6.dp else 2.dp, MaterialTheme.shapes.extraSmall)
                    .background(Color.White)
                    .then(if (selected) Modifier.border(3.dp, primary, MaterialTheme.shapes.extraSmall) else Modifier),
            ) {
                val img = image
                if (img != null) {
                    Image(img, "Page ${index + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit, filterQuality = FilterQuality.Medium)
                } else {
                    CircularProgressIndicator(Modifier.align(Alignment.Center).size(24.dp), strokeWidth = 2.dp)
                }
                if (selected) {
                    Surface(
                        shape = CircleShape,
                        color = primary,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp),
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(3.dp))
                    }
                }
            }
        }
        Text(
            "${index + 1}",
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
