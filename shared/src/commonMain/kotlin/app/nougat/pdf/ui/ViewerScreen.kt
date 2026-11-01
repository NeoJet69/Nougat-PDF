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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.vector.ImageVector
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
import app.nougat.pdf.engine.NPoint
import app.nougat.pdf.engine.NormRect
import app.nougat.pdf.engine.SignatureMark
import app.nougat.pdf.engine.TextMark
import app.nougat.pdf.viewer.DocumentSession
import app.nougat.pdf.viewer.InkColors
import app.nougat.pdf.viewer.Tool
import app.nougat.pdf.viewer.ViewerState
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
    state: ViewerState,
    session: DocumentSession,
    platform: PlatformInfo,
    onOpenFile: () -> Unit,
) {
    val tools = state.tools
    var textRequest by remember { mutableStateOf<TextRequest?>(null) }
    var signatureAt by remember { mutableStateOf<Pair<Int, NPoint>?>(null) }
    var askPassword by remember { mutableStateOf(false) }

    fun placeSignature(page: Int, at: NPoint) {
        val sig = tools.signature ?: return
        val size = session.pageSizes[page]
        val w = 0.3f
        val h = w * size.width / sig.aspectRatio / size.height
        val left = (at.x - w / 2).coerceIn(0f, 1f - w)
        val top = (at.y - h / 2).coerceIn(0f, (1f - h).coerceAtLeast(0f))
        session.marks.add(SignatureMark(page, NormRect(left, top, left + w, top + h), sig.strokes, InkColors.Blue))
    }

    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints {
            val wide = maxWidth > 720.dp
            val narrow = maxWidth <= 480.dp
            TopAppBar(
                title = {
                    Column {
                        Text(session.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        if (state.modified) {
                            Text("Modifié", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { state.runAfterConfirm(state::closeDocument) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Fermer le document")
                    }
                },
                actions = {
                    if (wide) ZoomControls(session)
                    if (state.assistantAvailable) {
                        IconButton(onClick = { state.assistant.open = !state.assistant.open }) {
                            Icon(
                                NougatIcons.Sparkle,
                                contentDescription = "Assistant Claude",
                                tint = if (state.assistant.open) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                            )
                        }
                    }
                    IconButton(onClick = session::openSearch) { Icon(Icons.Filled.Search, contentDescription = "Rechercher") }
                    if (!narrow) {
                        IconButton(onClick = { state.pagesOpen = true }) { Icon(NougatIcons.Pages, contentDescription = "Pages") }
                    }
                    EditToggle(active = tools.editing, compact = narrow) { tools.editing = !tools.editing }
                    if (state.modified) {
                        IconButton(onClick = state::save) {
                            Icon(NougatIcons.Save, contentDescription = "Enregistrer", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                    OverflowMenu(state, session, onOpenFile, onProtect = { askPassword = true })
                },
                windowInsets = WindowInsets(0, 0, 0, 0),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        }
        if (session.searchOpen) {
            SearchBar(session)
            HorizontalDivider()
        }
        val dockToolbar = platform.isDesktop
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Ecran large : l'assistant a cote du document ; sinon par-dessus.
            val sidePanel = maxWidth >= 900.dp
            Column(Modifier.fillMaxSize()) {
                if (tools.editing && dockToolbar) EditToolbar(state, Modifier, docked = true)
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        PageList(
                            session = session,
                            state = state,
                            inlineEditor = platform.isDesktop,
                            onTextRequest = { textRequest = it },
                            onSignatureNeeded = { page, at ->
                                if (tools.signature == null) signatureAt = page to at else placeSignature(page, at)
                            },
                        )
                        if (tools.editing && !dockToolbar) {
                            EditToolbar(state, Modifier.align(Alignment.BottomCenter).padding(12.dp))
                        } else {
                            PageIndicator(session, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
                        }
                    }
                    if (state.assistant.open && sidePanel) {
                        VerticalDivider()
                        AssistantPanel(state, Modifier.width(400.dp).fillMaxHeight())
                    }
                }
            }
            if (state.assistant.open && !sidePanel) {
                AssistantPanel(state, Modifier.fillMaxSize())
            }
        }
    }

    textRequest?.let { req ->
        val existing = req.existing
        TextDialog(
            initialText = existing?.text ?: "",
            initialSize = existing?.size ?: tools.textSize,
            initialColor = existing?.argb ?: tools.penColor,
            isNew = existing == null,
            onDone = { text, size, color ->
                if (existing != null) session.marks.remove(existing)
                if (text.isNotBlank()) {
                    val pageSize = session.pageSizes[req.page]
                    session.marks.add(TextMark(req.page, req.x, req.y, text, size, color, pageSize.width, pageSize.height))
                }
                tools.textSize = size
                tools.penColor = color
                textRequest = null
            },
            onDismiss = { textRequest = null },
        )
    }

    signatureAt?.let { (page, at) ->
        SignatureDialog(
            onDone = { sig ->
                tools.signature = sig
                placeSignature(page, at)
                signatureAt = null
            },
            onDismiss = { signatureAt = null },
        )
    }

    if (askPassword) {
        SetPasswordDialog(
            onDone = { pwd -> askPassword = false; state.setPassword(pwd) },
            onDismiss = { askPassword = false },
        )
    }
}

@Composable
private fun EditToggle(active: Boolean, compact: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (active) colors.primary else colors.primaryContainer,
        contentColor = if (active) colors.onPrimary else colors.onPrimaryContainer,
        modifier = Modifier.padding(horizontal = 4.dp),
    ) {
        Row(
            Modifier.padding(horizontal = if (compact) 8.dp else 12.dp, vertical = if (compact) 8.dp else 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                NougatIcons.Pen,
                contentDescription = if (compact) (if (active) "Terminer la modification" else "Modifier") else null,
                modifier = Modifier.size(18.dp),
            )
            if (!compact) {
                Spacer(Modifier.width(6.dp))
                Text(if (active) "Terminé" else "Modifier", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun OverflowMenu(state: ViewerState, session: DocumentSession, onOpenFile: () -> Unit, onProtect: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Plus d'actions") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            @Composable
            fun item(label: String, icon: ImageVector, action: () -> Unit) = DropdownMenuItem(
                text = { Text(label) },
                leadingIcon = { Icon(icon, contentDescription = null) },
                onClick = { open = false; action() },
            )
            item("Ouvrir un autre PDF", NougatIcons.Folder) { state.runAfterConfirm(onOpenFile) }
            item("Enregistrer", NougatIcons.Save, state::save)
            item("Enregistrer sous…", NougatIcons.Save, state::saveAs)
            if (state.files.canShare) item("Partager", Icons.Filled.Share, state::share)
            if (state.files.canPrint) item("Imprimer", NougatIcons.Print, state::print)
            HorizontalDivider()
            item("Organiser les pages", NougatIcons.Pages) { state.pagesOpen = true }
            item("Ajouter un PDF à la fin", Icons.Filled.Add, state::appendPdf)
            if (session.password == null) {
                item("Protéger par mot de passe", Icons.Filled.Lock, onProtect)
            } else {
                item("Retirer le mot de passe", Icons.Filled.Lock) { state.setPassword(null) }
            }
        }
    }
}

/**
 * La barre d'outils d'edition : flottante en bas de l'ecran (telephone), ou fixee sous la barre
 * du haut ([docked], ordinateur : la barre des taches de Windows ne peut pas la cacher).
 */
@Composable
private fun EditToolbar(state: ViewerState, modifier: Modifier, docked: Boolean = false) {
    val tools = state.tools
    var showColors by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme

    val hint: @Composable () -> Unit = {
        if (tools.tool == Tool.EditText && state.session?.editingBlock == null) {
            Surface(
                shape = RoundedCornerShape(50),
                color = colors.inverseSurface.copy(alpha = 0.9f),
                contentColor = colors.inverseOnSurface,
                modifier = Modifier.padding(vertical = 6.dp),
            ) {
                Text(
                    "Touchez une ligne encadrée pour modifier son texte",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
    val colorRow: @Composable () -> Unit = {
        if (showColors && tools.tool in setOf(Tool.Pen, Tool.Highlighter, Tool.Text)) {
            Surface(shape = RoundedCornerShape(50), tonalElevation = 3.dp, shadowElevation = 6.dp, modifier = Modifier.padding(vertical = 6.dp)) {
                Box(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    ColorRow(
                        if (tools.tool == Tool.Highlighter) InkColors.highlighter else InkColors.pen,
                        tools.color,
                    ) { tools.setColor(it); showColors = false }
                }
            }
        }
    }
    val buttons: @Composable () -> Unit = {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolButton(NougatIcons.Hand, Tool.Pan, tools.tool) { tools.tool = it }
            ToolButton(NougatIcons.EditText, Tool.EditText, tools.tool) { tools.tool = it }
            ToolButton(NougatIcons.Pen, Tool.Pen, tools.tool) { tools.tool = it }
            ToolButton(NougatIcons.Highlighter, Tool.Highlighter, tools.tool) { tools.tool = it }
            ToolButton(NougatIcons.Text, Tool.Text, tools.tool) { tools.tool = it }
            ToolButton(NougatIcons.Whiteout, Tool.Whiteout, tools.tool) { tools.tool = it }
            ToolButton(NougatIcons.Signature, Tool.Signature, tools.tool) { tools.tool = it }
            ToolButton(NougatIcons.Eraser, Tool.Eraser, tools.tool) { tools.tool = it }
            Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(28.dp).background(colors.outlineVariant))
            // Pastille de couleur
            Box(
                Modifier
                    .padding(horizontal = 6.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color(tools.color))
                    .clickable { showColors = !showColors },
            )
            IconButton(onClick = state::undo, enabled = state.canUndo) {
                Icon(NougatIcons.Undo, contentDescription = "Annuler")
            }
        }
    }

    if (docked) {
        Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(color = colors.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { buttons() }
            }
            HorizontalDivider()
            colorRow()
            hint()
        }
    } else {
        Column(modifier.widthIn(max = 760.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            hint()
            colorRow()
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = colors.surfaceContainerLowest,
                tonalElevation = 3.dp,
                shadowElevation = 8.dp,
            ) { buttons() }
        }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, tool: Tool, current: Tool, onSelect: (Tool) -> Unit) {
    val selected = tool == current
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) colors.primaryContainer else Color.Transparent)
            .clickable { onSelect(tool) }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = tool.label, tint = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Text(tool.label, style = MaterialTheme.typography.labelSmall, color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant)
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
private fun PageList(
    session: DocumentSession,
    state: ViewerState,
    inlineEditor: Boolean,
    onTextRequest: (TextRequest) -> Unit,
    onSignatureNeeded: (Int, NPoint) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
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
            session.updateZoom(session.zoom * zoomChange)
        }

        Box(
            Modifier
                .fillMaxSize()
                .ctrlWheelZoom { factor -> session.updateZoom(session.zoom * factor) }
                .transformable(transform, canPan = { false })
                .horizontalScroll(hScroll, enabled = contentWidth > viewportWidth),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.width(contentWidth).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(top = PagePadding, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(PagePadding),
            ) {
                items(session.pageCount, key = { it }) { index ->
                    PageView(session, state, index, pageWidth, inlineEditor, onTextRequest, onSignatureNeeded)
                }
            }
        }
    }
}

@Composable
private fun PageView(
    session: DocumentSession,
    state: ViewerState,
    index: Int,
    width: Dp,
    inlineEditor: Boolean,
    onTextRequest: (TextRequest) -> Unit,
    onSignatureNeeded: (Int, NPoint) -> Unit,
) {
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
            .shadow(2.dp, RoundedCornerShape(2.dp))
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
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(32.dp), color = MaterialTheme.colorScheme.primary)
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

        EditOverlay(
            session = session,
            tools = state.tools,
            page = index,
            pageSize = pageSize,
            onTextRequest = onTextRequest,
            onSignatureNeeded = { at -> onSignatureNeeded(index, at) },
            onReplaceText = state::replaceText,
            onMessage = state::showToast,
            inlineEditor = inlineEditor,
            onCommitPending = state::commitPendingTextEditLater,
        )
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
        modifier = modifier.clip(RoundedCornerShape(50)).clickable { askPage = true },
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
