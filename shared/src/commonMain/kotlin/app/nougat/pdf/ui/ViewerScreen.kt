package app.nougat.pdf.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import app.nougat.pdf.viewer.DocumentSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Largeur d'une page a 100 % : la largeur de l'ecran, sans depasser cette valeur. */
private val MaxPageWidth = 900.dp
private val PagePadding = 12.dp

/** Au-dela, l'image est agrandie au lieu d'etre recalculee (memoire). */
private const val MaxRenderPixels = 5_000_000f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    session: DocumentSession,
    platform: PlatformInfo,
    onOpenFile: () -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints {
            val wide = maxWidth > 640.dp
            TopAppBar(
                title = {
                    Text(session.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Fermer le document") }
                },
                actions = {
                    if (wide) ZoomControls(session)
                    IconButton(onClick = session::openSearch) { Icon(Icons.Filled.Search, contentDescription = "Rechercher") }
                    IconButton(onClick = onOpenFile) { Icon(NougatIcons.Folder, contentDescription = "Ouvrir un autre PDF") }
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        }
        if (session.searchOpen) {
            SearchBar(session)
            HorizontalDivider()
        }
        Box(Modifier.fillMaxSize()) {
            PageList(session)
            PageIndicator(session, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
        }
    }
}

@Composable
private fun ZoomControls(session: DocumentSession) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = session::zoomOut) { Icon(NougatIcons.Remove, contentDescription = "Dézoomer") }
        TextButton(onClick = session::resetZoom) { Text("${(session.zoom * 100).roundToInt()} %") }
        IconButton(onClick = session::zoomIn) { Icon(Icons.Filled.Add, contentDescription = "Zoomer") }
    }
}

@Composable
private fun SearchBar(session: DocumentSession) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = session.query,
            onValueChange = session::onQueryChange,
            placeholder = { Text("Rechercher dans le document") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { session.onSearchSubmit() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
            ),
            modifier = Modifier.weight(1f).focusRequester(focus).onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.Escape -> { session.closeSearch(); true }
                    Key.Enter, Key.NumPadEnter -> {
                        if (e.isShiftPressed) session.previousHit() else session.onSearchSubmit()
                        true
                    }
                    else -> false
                }
            },
        )
        val status = when {
            session.query.isBlank() -> ""
            session.hits.isNotEmpty() -> "${session.currentHit + 1} / ${session.hits.size}" + if (session.searching) "…" else ""
            session.searching -> "Recherche…"
            session.searchDone -> "Aucun résultat"
            else -> ""
        }
        Text(status, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 8.dp))
        IconButton(onClick = session::previousHit, enabled = session.hits.isNotEmpty()) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Résultat précédent")
        }
        IconButton(onClick = session::nextHit, enabled = session.hits.isNotEmpty()) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Résultat suivant")
        }
        IconButton(onClick = session::closeSearch) { Icon(Icons.Filled.Close, contentDescription = "Fermer la recherche") }
    }
}

@Composable
private fun PageList(session: DocumentSession) {
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
        val viewportWidth = maxWidth
        val viewportHeight = maxHeight
        val baseWidth = min(viewportWidth - PagePadding * 2, MaxPageWidth).coerceAtLeast(120.dp)
        val pageWidth = baseWidth * session.zoom
        val contentWidth = max(viewportWidth, pageWidth + PagePadding * 2)
        val density = LocalDensity.current
        val listState = session.listState
        val hScroll = rememberScrollState()

        // Page au centre de l'ecran, pour l'indicateur et les touches Page suivante/precedente.
        val centerPage by remember(listState) {
            derivedStateOf {
                val info = listState.layoutInfo
                val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
                info.visibleItemsInfo.firstOrNull { it.offset <= center && it.offset + it.size >= center }?.index
                    ?: listState.firstVisibleItemIndex
            }
        }
        LaunchedEffect(session) {
            snapshotFlow { centerPage }.collect { session.visiblePage = it }
        }

        // Defilement demande (recherche, aller a la page, clavier).
        val request = session.scrollRequest
        LaunchedEffect(request) {
            if (request == null) return@LaunchedEffect
            val size = session.pageSizes[request.page]
            val pageHeightPx = with(density) { (pageWidth / size.aspectRatio).toPx() }
            val viewportPx = with(density) { viewportHeight.toPx() }
            val offset = if (request.fractionInPage > 0f) {
                (request.fractionInPage * pageHeightPx - viewportPx / 3f).coerceAtLeast(0f)
            } else 0f
            listState.scrollToItem(request.page, offset.roundToInt())
            session.scrollHandled(request)
        }

        val transform = rememberTransformableState { zoomChange, _, _ ->
            session.setZoom(session.zoom * zoomChange)
        }

        Box(
            Modifier
                .fillMaxSize()
                .ctrlWheelZoom { factor -> session.setZoom(session.zoom * factor) }
                .transformable(transform, canPan = { false })
                .horizontalScroll(hScroll, enabled = contentWidth > viewportWidth),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.width(contentWidth).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(top = PagePadding, bottom = 72.dp),
                verticalArrangement = Arrangement.spacedBy(PagePadding),
            ) {
                items(session.pageCount, key = { it }) { index ->
                    PageView(session, index, pageWidth)
                }
            }
        }
    }
}

@Composable
private fun PageView(session: DocumentSession, index: Int, width: Dp) {
    val pageSize = session.pageSizes[index]
    val height = width / pageSize.aspectRatio
    val widthPx = with(LocalDensity.current) { width.toPx() }
    val targetPx = renderWidthFor(widthPx, pageSize.aspectRatio)

    val image by produceState(session.cachedPage(index), index, targetPx) {
        // Pendant un zoom au doigt, on attend que le geste se calme avant de recalculer.
        if (value != null) delay(150)
        try {
            value = session.renderPage(index, targetPx)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Page illisible : on garde ce qu'on a (ou la page blanche).
        }
    }

    Box(
        Modifier
            .size(width, height)
            .shadow(3.dp, RoundedCornerShape(2.dp))
            .background(Color.White),
    ) {
        val img = image
        if (img != null) {
            Image(
                bitmap = img,
                contentDescription = "Page ${index + 1}",
                contentScale = ContentScale.FillBounds,
                filterQuality = FilterQuality.Medium,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(32.dp), color = NougatColors.Caramel)
        }

        val hits = session.hitsOnPage(index)
        if (hits.isNotEmpty()) {
            val current = session.currentHit
            Canvas(Modifier.fillMaxSize()) {
                for ((hitIndex, hit) in hits) {
                    val color = if (hitIndex == current) NougatColors.HighlightCurrent else NougatColors.Highlight
                    for (r in hit.rects) {
                        drawRect(
                            color = color,
                            topLeft = Offset(r.left * size.width - 1f, r.top * size.height - 1f),
                            size = Size((r.right - r.left) * size.width + 2f, (r.bottom - r.top) * size.height + 2f),
                        )
                    }
                }
            }
        }
    }
}

/** Largeur de rendu arrondie (pour ne pas tout recalculer a chaque pixel de zoom) et plafonnee. */
private fun renderWidthFor(widthPx: Float, aspectRatio: Float): Int {
    val step = 160f
    val rounded = ceil(widthPx / step) * step
    val maxWidth = sqrt(MaxRenderPixels * aspectRatio)
    return rounded.coerceIn(step, maxWidth.coerceAtLeast(step)).toInt()
}

@Composable
private fun PageIndicator(session: DocumentSession, modifier: Modifier) {
    var askPage by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.clickable { askPage = true },
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
    ) {
        Text(
            "Page ${session.visiblePage + 1} / ${session.pageCount}",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
    if (askPage) {
        GoToPageDialog(
            pageCount = session.pageCount,
            onGo = { session.goToPage(it - 1); askPage = false },
            onDismiss = { askPage = false },
        )
    }
}

@Composable
private fun GoToPageDialog(pageCount: Int, onGo: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val number = text.toIntOrNull()
    val valid = number != null && number in 1..pageCount
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aller à la page") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v.filter(Char::isDigit).take(6) },
                label = { Text("Page (1 à $pageCount)") },
                singleLine = true,
                isError = text.isNotEmpty() && !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (valid) onGo(number!!) }),
                modifier = Modifier.focusRequester(focus).onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter) && valid) {
                        onGo(number!!); true
                    } else false
                },
            )
        },
        confirmButton = { TextButton(onClick = { onGo(number!!) }, enabled = valid) { Text("Aller") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
