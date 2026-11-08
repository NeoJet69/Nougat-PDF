package app.nougat.pdf.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
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
import app.nougat.pdf.viewer.DockMode
import app.nougat.pdf.viewer.DocumentSession
import app.nougat.pdf.viewer.InkColors
import app.nougat.pdf.viewer.Tool
import app.nougat.pdf.viewer.ViewerState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt
import app.nougat.pdf.i18n.Str
import app.nougat.pdf.i18n.tr
import app.nougat.pdf.i18n.trn
import app.nougat.pdf.i18n.I18n
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.style.TextDirection

/** Ilot du document le plus etroit qu'on accepte avant de deplacer la navigation sous la page. */
private val MinDocumentIsland = 220.dp

/** Largeur d'une page a 100 % : la largeur disponible, sans depasser cette valeur. */
private val MaxPageWidth = 900.dp
private val PageGap = 22.dp

/** Au-dela, l'image est agrandie au lieu d'etre recalculee (memoire). */
private const val MaxRenderPixels = 5_000_000f

/**
 * L'ecran de lecture « Caramel Glass » : le document sur une toile chaude, des ilots flottants
 * en haut, le dock des modes en bas, les miniatures a gauche et Claude a droite (ecran large).
 */
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
    var askPage by remember { mutableStateOf(false) }

    fun placeSignature(page: Int, at: NPoint) {
        val sig = tools.signature ?: return
        val size = session.pageSizes[page]
        val w = 0.3f
        val h = w * size.width / sig.aspectRatio / size.height
        val left = (at.x - w / 2).coerceIn(0f, 1f - w)
        val top = (at.y - h / 2).coerceIn(0f, (1f - h).coerceAtLeast(0f))
        session.marks.add(SignatureMark(page, NormRect(left, top, left + w, top + h), sig.strokes, InkColors.Blue))
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 600.dp
        val wide = maxWidth >= 900.dp
        val showRail = maxWidth >= 1100.dp
        val sideAssistant = wide && state.assistant.open
        val topSpace = if (compact) 76.dp else 84.dp
        val railWidth = if (showRail) 112.dp else 0.dp
        val assistantWidth = if (sideAssistant) 424.dp else 0.dp

        // Ilots du haut : le document a gauche, les actions a droite et, au-dessus de la page, la
        // navigation (ou la recherche) si elle tient sans rien chevaucher ; sinon elle passe dessous.
        val pageCenter = railWidth + (maxWidth - railWidth - assistantWidth) / 2
        val rightEdge = maxWidth - 12.dp - assistantWidth
        val centerHalf = if (session.searchOpen) 280.dp else 180.dp
        // Telephone : « Traduire » est en haut, a cote de la loupe (le dock n'a plus de place).
        val baseActions = 12.dp + 48.dp + (if (state.canUndo) 48.dp else 0.dp) + if (compact) 48.dp else 0.dp
        val fullActions = baseActions + if (state.modified) 194.dp else 0.dp
        val iconActions = baseActions + if (state.modified) 48.dp else 0.dp
        fun centerFits(actions: Dp) = !compact &&
            pageCenter - centerHalf - 24.dp >= MinDocumentIsland &&
            pageCenter + centerHalf + 12.dp <= rightEdge - actions
        val compactActions = compact || (!centerFits(fullActions) && centerFits(iconActions))
        val actionsWidth = if (compactActions) iconActions else fullActions
        val centerOnTop = centerFits(actionsWidth)
        val navInDock = session.searchOpen || !centerOnTop
        val searchBelow = session.searchOpen && !centerOnTop
        val documentMax = if (centerOnTop) pageCenter - centerHalf - 24.dp else rightEdge - actionsWidth - 24.dp

        // Le document (toujours de gauche a droite, meme en arabe : ce sont les pages du PDF)
        Box(Modifier.fillMaxSize().padding(start = railWidth, end = assistantWidth)) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                PageList(
                    session = session,
                    state = state,
                    inlineEditor = platform.isDesktop,
                    topPadding = topSpace + (if (searchBelow) 64.dp else 0.dp) + when {
                        state.censor.reviewing -> if (compact) 150.dp else 140.dp
                        state.translation.active -> if (compact) 116.dp else 58.dp
                        else -> 0.dp
                    },
                    bottomPadding = 170.dp,
                    onTextRequest = { textRequest = it },
                    onSignatureNeeded = { page, at ->
                        if (tools.signature == null) signatureAt = page to at else placeSignature(page, at)
                    },
                )
            }
        }

        // Miniatures (grand ecran)
        if (showRail) {
            ThumbnailRail(session, Modifier.align(Alignment.CenterStart).padding(start = 14.dp, top = topSpace + 8.dp, bottom = 120.dp).width(92.dp))
        }

        // Ilots du haut
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp + assistantWidth),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            DocumentIsland(
                state, session,
                showMascot = !compact && documentMax >= 360.dp,
                onOpenFile = onOpenFile,
                onProtect = { askPassword = true },
                modifier = Modifier.weight(1f, fill = false).widthIn(max = documentMax.coerceAtLeast(MinDocumentIsland)),
            )
            Spacer(Modifier.width(12.dp))
            ActionsIsland(state, session, compactActions, translateHere = compact, modifier = Modifier)
        }
        Box(
            Modifier.fillMaxWidth()
                .padding(start = railWidth + 12.dp, end = assistantWidth + 12.dp, top = if (searchBelow) 76.dp else 12.dp),
        ) {
            if (session.searchOpen) {
                SearchIsland(session, Modifier.align(Alignment.TopCenter).widthIn(max = 560.dp).then(if (compact) Modifier.fillMaxWidth() else Modifier))
            } else if (centerOnTop) {
                NavigationIsland(session, onAskPage = { askPage = true }, modifier = Modifier.align(Alignment.TopCenter))
            }
        }

        // Censure : les trouvailles a verifier, puis confirmer.
        if (state.censor.reviewing) {
            CensorBar(
                state = state,
                compact = compact,
                modifier = Modifier.align(Alignment.TopCenter)
                    .padding(start = railWidth + 12.dp, end = assistantWidth + 12.dp, top = topSpace + if (searchBelow) 70.dp else 6.dp),
            )
        } else if (state.translation.active) {
            // Traduction affichee : le bandeau (original ou traduction, enregistrer la copie traduite).
            TranslationBar(
                state = state,
                compact = compact,
                phone = state.ocrOnPhone,
                modifier = Modifier.align(Alignment.TopCenter)
                    .padding(start = railWidth + 12.dp, end = assistantWidth + 12.dp, top = topSpace + if (searchBelow) 70.dp else 6.dp),
            )
        }

        // Document scanne : proposer la reconnaissance du texte.
        if (state.canOcr && session.ocrPages > 0 && !session.ocrBannerClosed && !tools.editing && !state.translation.active && !state.censor.reviewing) {
            ScanBanner(
                pages = session.ocrPages,
                compact = compact,
                onRecognize = { state.ocrDialog = true },
                onClose = { session.ocrBannerClosed = true },
                modifier = Modifier.align(Alignment.TopCenter)
                    .padding(start = railWidth + 12.dp, end = assistantWidth + 12.dp, top = topSpace + if (searchBelow) 70.dp else 6.dp),
            )
        }

        // Le dock et le plateau d'outils du mode
        ViewerDock(
            state = state,
            session = session,
            compact = compact,
            navInDock = navInDock,
            onAskPage = { askPage = true },
            onChangeSignature = { tools.signature = null; state.showToast(tr(Str.TapToDrawNewSignature)) },
            modifier = Modifier.align(Alignment.BottomCenter).padding(start = railWidth, end = assistantWidth, bottom = 14.dp),
        )

        // Claude : carte flottante a droite (grand ecran) ou plein ecran (telephone)
        if (state.assistant.open) {
            if (wide) {
                Box(Modifier.align(Alignment.CenterEnd).padding(top = 12.dp, bottom = 12.dp, end = 12.dp).width(400.dp).fillMaxHeight()) {
                    AssistantPanel(state, Modifier.fillMaxSize())
                }
            } else {
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

    if (askPage) {
        GoToPageDialog(
            pageCount = session.pageCount,
            onGo = { session.goToPage(it - 1); askPage = false },
            onDismiss = { askPage = false },
        )
    }
}

// ---- Ilots du haut -------------------------------------------------------------------------------

/** En haut a gauche : fermer, le chat, le nom du document, le menu du document. */
@Composable
private fun DocumentIsland(
    state: ViewerState,
    session: DocumentSession,
    showMascot: Boolean,
    onOpenFile: () -> Unit,
    onProtect: () -> Unit,
    modifier: Modifier,
) {
    val p = LocalNougatPalette.current
    Island(modifier) {
        IslandButton(Icons.Filled.Close, tr(Str.CloseDocument), onClick = { state.runAfterConfirm(state::closeDocument) })
        if (showMascot) {
            NougatMascot(Modifier.size(34.dp), animated = false)
            Spacer(Modifier.width(8.dp))
        }
        // Le nom retrecit (avec « … ») si la place manque : l'ilot ne deborde jamais sur les autres.
        Column(Modifier.weight(1f, fill = false).widthIn(max = 300.dp).padding(end = 4.dp)) {
            Text(session.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val primary = MaterialTheme.colorScheme.primary
            Text(
                buildAnnotatedString {
                    // « modifié » d'abord : sur un petit ecran, c'est le nombre de pages qui est coupe.
                    if (state.modified) {
                        withStyle(SpanStyle(color = primary)) { append(tr(Str.Modified) + "  •  ") }
                    }
                    withStyle(SpanStyle(color = p.muted)) {
                        append(trn(Str.PagesCount, session.pageCount))
                    }
                },
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DocumentMenu(state, session, onOpenFile, onProtect)
    }
}

@Composable
private fun DocumentMenu(state: ViewerState, session: DocumentSession, onOpenFile: () -> Unit, onProtect: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IslandButton(Icons.Filled.MoreVert, tr(Str.DocumentMenu), onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = RoundedCornerShape(20.dp)) {
            @Composable
            fun item(label: String, icon: ImageVector, action: () -> Unit) = DropdownMenuItem(
                text = { Text(label, style = MaterialTheme.typography.bodyLarge) },
                leadingIcon = { Icon(icon, contentDescription = null) },
                onClick = { open = false; action() },
                modifier = Modifier.height(52.dp),
            )
            item(tr(Str.OpenAnotherPdf), NougatIcons.Folder) { state.runAfterConfirm(onOpenFile) }
            item(tr(Str.Save), NougatIcons.Save, state::save)
            item(tr(Str.SaveAs), NougatIcons.Save, state::saveAs)
            if (state.files.canShare) item(tr(Str.Share), Icons.Filled.Share, state::share)
            if (state.files.canPrint) item(tr(Str.Print), NougatIcons.Print, state::print)
            HorizontalDivider()
            item(tr(Str.OrganizePages), NougatIcons.Pages) { state.pagesOpen = true }
            item(tr(Str.AppendPdf), Icons.Filled.Add, state::appendPdf)
            item(tr(Str.AppendImages), NougatIcons.Image, state::appendImages)
            if (state.canScan) item(tr(Str.ScanAppend), NougatIcons.Camera, state::appendScan)
            if (state.canOcr) item(tr(Str.RecognizeText), NougatIcons.Scan) { state.ocrDialog = true }
            item(tr(Str.TranslateTitle), NougatIcons.Translate) { state.translation.dialogOpen = true }
            item(tr(Str.Stamp), NougatIcons.Numbers) { state.stampOpen = true }
            item(tr(Str.Compress), NougatIcons.Compress) { state.compressOpen = true }
            item(tr(Str.ExportMarkdown), NougatIcons.Markdown, state::exportMarkdown)
            item(tr(Str.Censor), NougatIcons.Censor) { state.censor.dialogOpen = true }
            item(tr(Str.Finalize), NougatIcons.Shield) { state.finalizeOpen = true }
            if (session.password == null) {
                item(tr(Str.ProtectWithPassword), Icons.Filled.Lock, onProtect)
            } else {
                item(tr(Str.RemovePassword), Icons.Filled.Lock) { state.setPassword(null) }
            }
            HorizontalDivider()
            item(tr(Str.ThemeLabel, themeLabel(state.themeMode)), NougatIcons.Contrast, state::cycleTheme)
            item(tr(Str.LanguageLabel, I18n.lang.nativeName), NougatIcons.Language) { state.languageOpen = true }
            item(tr(Str.About), NougatIcons.Sparkle) { state.aboutOpen = true }
        }
    }
}

fun themeLabel(mode: ThemeMode) = when (mode) {
    ThemeMode.Auto -> tr(Str.ThemeAuto)
    ThemeMode.Light -> tr(Str.ThemeLight)
    ThemeMode.Dark -> tr(Str.ThemeDark)
}

/** En haut au centre (grand ecran) : pages et zoom. */
@Composable
private fun NavigationIsland(session: DocumentSession, onAskPage: () -> Unit, modifier: Modifier) {
    Island(modifier) {
        IslandButton(NougatIcons.ArrowLeft, tr(Str.PreviousPage), onClick = session::previousPage, enabled = session.visiblePage > 0)
        Box(
            Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onAskPage).padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            // Chiffres toujours de gauche a droite (sinon « 2 / 1 » en arabe).
            Text("${session.visiblePage + 1} / ${session.pageCount}", style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Ltr))
        }
        IslandButton(NougatIcons.ArrowRight, tr(Str.NextPage), onClick = session::nextPage, enabled = session.visiblePage < session.pageCount - 1)
        IslandDivider()
        IslandButton(NougatIcons.Remove, tr(Str.ZoomOut), onClick = session::zoomOut)
        Box(
            Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = session::resetZoom).padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Text("${(session.zoom * 100).roundToInt()} %", style = MaterialTheme.typography.titleSmall.copy(textDirection = TextDirection.Ltr))
        }
        IslandButton(Icons.Filled.Add, tr(Str.ZoomIn), onClick = session::zoomIn)
    }
}

/** En haut a droite : chercher, enregistrer. */
@Composable
private fun ActionsIsland(state: ViewerState, session: DocumentSession, compact: Boolean, translateHere: Boolean, modifier: Modifier) {
    Island(modifier) {
        IslandButton(Icons.Filled.Search, tr(Str.Search), onClick = session::openSearch, active = session.searchOpen)
        if (translateHere) {
            IslandButton(
                NougatIcons.Translate, tr(Str.Translate),
                onClick = { if (state.translation.active) state.translation.stop() else state.translation.dialogOpen = true },
                active = state.translation.active,
            )
        }
        if (state.canUndo) IslandButton(NougatIcons.Undo, tr(Str.Undo), onClick = state::undo)
        if (state.modified) {
            if (compact) {
                IslandButton(NougatIcons.Save, tr(Str.Save), onClick = state::save, tint = MaterialTheme.colorScheme.primary)
            } else {
                Spacer(Modifier.width(4.dp))
                GradientButton(tr(Str.Save), onClick = state::save, icon = NougatIcons.Save, height = 44.dp)
            }
        }
    }
}

@Composable
fun IslandDivider() {
    Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(28.dp).background(LocalNougatPalette.current.islandBorder))
}

@Composable
private fun SearchIsland(session: DocumentSession, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val colors = MaterialTheme.colorScheme
    Island(modifier) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = colors.primary, modifier = Modifier.padding(start = 10.dp, end = 8.dp).size(22.dp))
        Box(Modifier.weight(1f, fill = false).widthIn(min = 160.dp, max = 320.dp)) {
            if (session.query.isEmpty()) {
                Text(tr(Str.SearchInDocument), style = MaterialTheme.typography.bodyLarge, color = LocalNougatPalette.current.muted)
            }
            BasicTextField(
                value = session.query,
                onValueChange = session::onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { session.onSearchSubmit() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { e ->
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
        }
        val status = when {
            session.query.isBlank() -> ""
            session.hits.isNotEmpty() -> "${session.currentHit + 1}/${session.hits.size}" + if (session.searching) "…" else ""
            session.searching -> "…"
            session.searchDone -> "0"
            else -> ""
        }
        if (status.isNotEmpty()) {
            Text(status, style = MaterialTheme.typography.labelLarge.copy(textDirection = TextDirection.Ltr), color = LocalNougatPalette.current.muted, modifier = Modifier.padding(horizontal = 8.dp))
        }
        IslandButton(Icons.Filled.KeyboardArrowUp, tr(Str.PreviousResult), onClick = session::previousHit, enabled = session.hits.isNotEmpty(), size = 40.dp)
        IslandButton(Icons.Filled.KeyboardArrowDown, tr(Str.NextResult), onClick = session::nextHit, enabled = session.hits.isNotEmpty(), size = 40.dp)
        IslandButton(Icons.Filled.Close, tr(Str.CloseSearch), onClick = session::closeSearch, size = 40.dp)
    }
}

// ---- Le dock -------------------------------------------------------------------------------------

@Composable
private fun ViewerDock(
    state: ViewerState,
    session: DocumentSession,
    compact: Boolean,
    /** La navigation n'a pas de place en haut : en mode Lire, elle s'affiche au-dessus du dock. */
    navInDock: Boolean,
    onAskPage: () -> Unit,
    onChangeSignature: () -> Unit,
    modifier: Modifier,
) {
    val tools = state.tools
    val mode = tools.mode
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedVisibility(
            visible = mode != DockMode.Read || navInDock,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
        ) {
            Box(Modifier.padding(bottom = 10.dp)) {
                when (mode) {
                    DockMode.Read -> if (compact) PagePill(session, onAskPage) else NavigationIsland(session, onAskPage, Modifier)
                    DockMode.Edit -> if (session.selectedBlocks.isNotEmpty()) {
                        SelectionTray(
                            count = session.selectedBlocks.size,
                            onDelete = state::deleteSelectedText,
                            onClear = session::clearSelection,
                        )
                    } else {
                        HintTray(
                            when {
                                session.editingBlock != null -> tr(if (compact) Str.HintEditingCompact else Str.HintEditing)
                                compact -> tr(Str.HintTapLineCompact)
                                else -> tr(Str.HintTapLine)
                            },
                            compact,
                            subtitle = if (session.editingBlock != null) null else tr(if (state.ocrOnPhone) Str.SelectHintPhone else Str.SelectHintDesktop),
                        )
                    }
                    DockMode.Sign -> SignTray(hasSignature = tools.signature != null, onChange = onChangeSignature)
                    DockMode.Annotate -> AnnotateTray(state, compact)
                }
            }
        }
        Island(shape = RoundedCornerShape(30.dp), padding = 6.dp, scrollable = true) {
            DockItem(NougatIcons.Eye, tr(Str.DockRead), mode == DockMode.Read, { tools.enter(DockMode.Read) }, compact)
            DockItem(NougatIcons.EditText, tr(Str.DockEdit), mode == DockMode.Edit, { tools.enter(DockMode.Edit) }, compact)
            DockItem(NougatIcons.Pen, tr(Str.DockAnnotate), mode == DockMode.Annotate, { tools.enter(DockMode.Annotate) }, compact)
            DockItem(NougatIcons.Signature, tr(Str.DockSign), mode == DockMode.Sign, { tools.enter(DockMode.Sign) }, compact)
            DockItem(NougatIcons.Pages, tr(Str.DockPages), false, { state.pagesOpen = true }, compact)
            if (!compact) {
                DockItem(
                    NougatIcons.Translate, tr(Str.Translate), state.translation.active,
                    { if (state.translation.active) state.translation.stop() else state.translation.dialogOpen = true },
                    compact,
                )
            }
            if (state.assistantAvailable) {
                IslandDivider()
                DockItem(NougatIcons.Sparkle, "Claude", state.assistant.open, { state.assistant.open = !state.assistant.open }, compact)
            }
        }
    }
}

@Composable
private fun PagePill(session: DocumentSession, onAskPage: () -> Unit) {
    Island(shape = RoundedCornerShape(50), padding = 0.dp, modifier = Modifier.clickable(onClick = onAskPage)) {
        Text(
            tr(Str.PagePill, session.visiblePage + 1, session.pageCount),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}

/** « Document scanne : le texte n'est pas encore reconnu » + bouton. */
@Composable
private fun ScanBanner(pages: Int, compact: Boolean, onRecognize: () -> Unit, onClose: () -> Unit, modifier: Modifier) {
    if (compact) {
        // Telephone : le texte sur toute la largeur, le bouton dessous.
        Island(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), padding = 8.dp) {
            GradientBadge(NougatIcons.Scan, Modifier.align(Alignment.Top), size = 36.dp, iconSize = 20.dp)
            Column(Modifier.weight(1f).padding(start = 10.dp, top = 2.dp)) {
                Text(trn(Str.ScanBanner, pages), style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                GradientButton(tr(Str.ScanBannerButton), onClick = onRecognize, height = 38.dp)
            }
            IslandButton(Icons.Filled.Close, tr(Str.Close), onClick = onClose, Modifier.align(Alignment.Top), size = 36.dp, iconSize = 18.dp)
        }
        return
    }
    Island(modifier.widthIn(max = 640.dp), shape = RoundedCornerShape(26.dp), padding = 6.dp) {
        GradientBadge(NougatIcons.Scan, size = 36.dp, iconSize = 20.dp)
        Text(
            trn(Str.ScanBanner, pages),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f, fill = false).padding(horizontal = 10.dp),
        )
        GradientButton(tr(Str.ScanBannerButton), onClick = onRecognize, height = 40.dp)
        IslandButton(Icons.Filled.Close, tr(Str.Close), onClick = onClose, size = 40.dp, iconSize = 18.dp)
    }
}

@Composable
private fun HintTray(text: String, compact: Boolean, subtitle: String? = null) {
    Island(shape = RoundedCornerShape(if (subtitle != null) 24.dp else 50.dp), padding = 4.dp) {
        GradientBadge(NougatIcons.EditText, size = 32.dp, iconSize = 18.dp)
        Column(Modifier.padding(horizontal = 12.dp)) {
            Text(text, style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = LocalNougatPalette.current.muted)
            }
        }
    }
}

/** Lignes choisies : combien, Effacer, annuler la selection. */
@Composable
private fun SelectionTray(count: Int, onDelete: () -> Unit, onClear: () -> Unit) {
    Island(shape = RoundedCornerShape(50), padding = 4.dp) {
        IslandButton(Icons.Filled.Close, tr(Str.ClearSelection), onClick = onClear, size = 40.dp, iconSize = 20.dp)
        Text(trn(Str.SelectedLines, count), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp))
        Box(
            Modifier.clip(RoundedCornerShape(50)).background(Color(0xFFE53935)).clickable(onClick = onDelete)
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            Text(tr(Str.DeleteSelected), style = MaterialTheme.typography.labelLarge, color = Color.White)
        }
    }
}

@Composable
private fun SignTray(hasSignature: Boolean, onChange: () -> Unit) {
    Island(shape = RoundedCornerShape(50), padding = 4.dp) {
        GradientBadge(NougatIcons.Signature, size = 32.dp, iconSize = 18.dp)
        Text(
            tr(if (hasSignature) Str.TapToPlaceSignature else Str.TapToDrawSignature),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        if (hasSignature) {
            TextButton(onClick = onChange) { Text(tr(Str.Change)) }
        }
    }
}

@Composable
private fun AnnotateTray(state: ViewerState, compact: Boolean) {
    val tools = state.tools
    val withColors = tools.tool in setOf(Tool.Pen, Tool.Highlighter, Tool.Text)
    if (compact) {
        // Telephone : les couleurs dans un petit ilot au-dessus des outils, rien n'est coupe ;
        // juste dessous, la couleur libre (teinte, profondeur, code).
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (withColors) {
                Island(shape = RoundedCornerShape(50), padding = 6.dp, scrollable = true) { ColorDots(state) }
                Spacer(Modifier.height(6.dp))
                Island(Modifier.widthIn(max = 360.dp), shape = RoundedCornerShape(22.dp), padding = 4.dp) {
                    FreeColorPicker(tools.color, tools::setColor, Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
            }
            Island(shape = RoundedCornerShape(26.dp), padding = 4.dp, scrollable = true) { TrayTools(state, withPan = true) }
        }
    } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Island(shape = RoundedCornerShape(26.dp), padding = 4.dp, scrollable = true) {
                TrayTools(state, withPan = false)
                if (withColors) {
                    IslandDivider()
                    ColorDots(state)
                }
            }
            if (withColors) {
                Spacer(Modifier.height(6.dp))
                Island(Modifier.width(420.dp), shape = RoundedCornerShape(22.dp), padding = 4.dp) {
                    FreeColorPicker(tools.color, tools::setColor, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun TrayTools(state: ViewerState, withPan: Boolean) {
    if (withPan) TrayTool(NougatIcons.Hand, Tool.Pan, state)
    TrayTool(NougatIcons.Pen, Tool.Pen, state)
    TrayTool(NougatIcons.Highlighter, Tool.Highlighter, state)
    TrayTool(NougatIcons.Text, Tool.Text, state)
    TrayTool(NougatIcons.Whiteout, Tool.Whiteout, state)
    TrayTool(NougatIcons.Eraser, Tool.Eraser, state)
}

@Composable
private fun ColorDots(state: ViewerState) {
    val tools = state.tools
    val colors = MaterialTheme.colorScheme
    val palette = if (tools.tool == Tool.Highlighter) InkColors.highlighter else InkColors.pen
    Spacer(Modifier.width(2.dp))
    for (c in palette) {
        val selected = c == tools.color
        Box(Modifier.size(38.dp).clip(CircleShape).clickable { tools.setColor(c) }, contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(if (selected) 30.dp else 24.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .border(if (selected) 3.dp else 1.dp, if (selected) colors.primary else colors.outline, CircleShape),
            )
        }
    }
    Spacer(Modifier.width(2.dp))
}

@Composable
private fun TrayTool(icon: ImageVector, tool: Tool, state: ViewerState) {
    IslandButton(icon, tool.label, onClick = { state.tools.pick(tool) }, active = state.tools.tool == tool, size = 44.dp, iconSize = 22.dp)
}

// ---- Miniatures ----------------------------------------------------------------------------------

@Composable
private fun ThumbnailRail(session: DocumentSession, modifier: Modifier) {
    val p = LocalNougatPalette.current
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier
            .shadow(20.dp, shape, ambientColor = p.shadow, spotColor = p.shadow)
            .clip(shape)
            .background(p.island)
            .border(1.dp, p.islandBorder, shape),
    ) {
        LazyColumn(
            contentPadding = PaddingValues(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(session.pageCount, key = { it }) { index -> RailThumbnail(session, index) }
        }
    }
}

@Composable
private fun RailThumbnail(session: DocumentSession, index: Int) {
    val size = session.pageSizes[index]
    val image by produceState(session.cachedThumbnail(index), index) {
        try {
            value = session.thumbnail(index, 200)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
        }
    }
    val current = session.visiblePage == index
    val colors = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { session.goToPage(index) }) {
        Box(
            Modifier
                .width(66.dp)
                .aspectRatio(size.aspectRatio)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.White)
                .border(if (current) 2.5.dp else 1.dp, if (current) colors.primary else colors.outlineVariant, RoundedCornerShape(4.dp)),
        ) {
            image?.let {
                Image(it, tr(Str.PageN, index + 1), Modifier.fillMaxSize(), contentScale = ContentScale.Fit, filterQuality = FilterQuality.Medium)
            }
        }
        Text(
            "${index + 1}",
            style = MaterialTheme.typography.labelMedium,
            color = if (current) colors.primary else LocalNougatPalette.current.muted,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

// ---- Les pages -------------------------------------------------------------------------------------

@Composable
private fun PageList(
    session: DocumentSession,
    state: ViewerState,
    inlineEditor: Boolean,
    topPadding: Dp,
    bottomPadding: Dp,
    onTextRequest: (TextRequest) -> Unit,
    onSignatureNeeded: (Int, NPoint) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewportWidth = maxWidth
        val viewportHeight = maxHeight
        val baseWidth = min(viewportWidth - 40.dp, MaxPageWidth).coerceAtLeast(120.dp)
        val pageWidth = baseWidth * session.zoom
        val contentWidth = max(viewportWidth, pageWidth + 40.dp)
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

        // Zoom autour d'un point : le contenu sous les doigts (ou la souris) reste en place.
        fun zoomAround(factor: Float, centroid: Offset) {
            val before = session.zoom
            session.updateZoom(before * factor)
            val f = session.zoom / before
            if (f == 1f) return
            listState.dispatchRawDelta((listState.firstVisibleItemScrollOffset + centroid.y) * (f - 1f))
            hScroll.dispatchRawDelta((hScroll.value + centroid.x) * (f - 1f))
        }

        Box(
            Modifier
                .fillMaxSize()
                .ctrlWheelZoom { factor, position -> zoomAround(factor, position) }
                .pinchToZoom { factor, centroid -> zoomAround(factor, centroid) }
                .horizontalScroll(hScroll, enabled = contentWidth > viewportWidth),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.width(contentWidth).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding),
                verticalArrangement = Arrangement.spacedBy(PageGap),
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
    val p = LocalNougatPalette.current

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

    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .size(width, height)
            // Du papier pose sur la toile : ombre large et douce.
            .shadow(14.dp, shape, ambientColor = p.paperShadow, spotColor = p.paperShadow)
            .clip(shape)
            .background(Color.White),
    ) {
        val img = image
        if (img != null) {
            Image(
                bitmap = img,
                contentDescription = tr(Str.PageN, index + 1),
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

        // Formulaire : en mode Lire (ou avec la main), toucher un champ pour le remplir.
        if (session.hasForm && (!state.tools.editing || state.tools.tool == Tool.Pan)) {
            FormOverlay(
                session = session,
                page = index,
                pageSize = pageSize,
                inlineEditor = inlineEditor,
                onSetValue = state::setFieldValue,
                onStartEditing = state::startFieldEditing,
                onCommitPending = state::commitPendingFieldEditLater,
                onMessage = state::showToast,
            )
        }

        // Censure : les trouvailles de la page, a garder ou non d'un toucher.
        if (state.censor.reviewing) {
            CensorOverlay(state, index)
        } else if (state.translation.active && (!state.tools.editing || state.tools.tool == Tool.Pan)) {
            // Traduction : posee sur la page (en mode Lire), la page s'affiche donc on la traduit.
            LaunchedEffect(session, state.translation.settings) { state.translation.want(session, index) }
            TranslationOverlay(state, session, index, pageSize, image)
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
private fun GoToPageDialog(pageCount: Int, onGo: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val number = text.toIntOrNull()
    val valid = number != null && number in 1..pageCount
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(Str.GoToPage)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v.filter(Char::isDigit).take(6) },
                label = { Text(tr(Str.PageRange, pageCount)) },
                singleLine = true,
                isError = text.isNotEmpty() && !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (valid) onGo(number) }),
                modifier = Modifier.focusRequester(focus).onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter) && valid) {
                        onGo(number); true
                    } else false
                },
            )
        },
        confirmButton = { TextButton(onClick = { if (valid) onGo(number) }, enabled = valid) { Text(tr(Str.Go)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(Str.Cancel)) } },
    )
}

/**
 * Pincer pour zoomer. Le geste est lu AVANT la liste des pages (passe « Initial ») : sinon la
 * liste, qui defile, le consomme et le pincement marche mal.
 */
private fun Modifier.pinchToZoom(onZoom: (factor: Float, centroid: Offset) -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.count { it.pressed } >= 2) {
                    val zoom = event.calculateZoom()
                    if (zoom != 1f && !zoom.isNaN()) onZoom(zoom, event.calculateCentroid(useCurrent = true))
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            } while (event.changes.any { it.pressed })
        }
    }
