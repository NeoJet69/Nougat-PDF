package app.nougat.pdf.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.nougat.pdf.engine.TextBlock
import kotlin.math.roundToInt
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import app.nougat.pdf.engine.BoxMark
import app.nougat.pdf.engine.InkMark
import app.nougat.pdf.engine.Mark
import app.nougat.pdf.engine.NPoint
import app.nougat.pdf.engine.NormRect
import app.nougat.pdf.engine.PageSize
import app.nougat.pdf.engine.SignatureMark
import app.nougat.pdf.engine.TextMark
import app.nougat.pdf.viewer.DocumentSession
import app.nougat.pdf.viewer.EditTools
import app.nougat.pdf.viewer.InkColors
import app.nougat.pdf.viewer.Tool
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import app.nougat.pdf.i18n.Str
import app.nougat.pdf.i18n.tr

/** Demande d'ecrire un texte a un endroit de la page (nouveau ou existant). */
data class TextRequest(val page: Int, val x: Float, val y: Float, val existing: TextMark? = null)

/**
 * Calque pose sur une page : dessine les annotations en attente et, en mode edition,
 * recoit les gestes de l'outil choisi.
 */
@Composable
fun EditOverlay(
    session: DocumentSession,
    tools: EditTools,
    page: Int,
    pageSize: PageSize,
    onTextRequest: (TextRequest) -> Unit,
    onSignatureNeeded: (NPoint) -> Unit,
    onReplaceText: (TextBlock, String) -> Unit,
    onMessage: (String) -> Unit,
    /** Ordinateur : champ pose sur la ligne. Telephone : fenetre avec un grand bouton Valider. */
    inlineEditor: Boolean,
    /** Applique la ligne en cours de modification (toucher ailleurs ne doit pas perdre le texte). */
    onCommitPending: () -> Unit,
) {
    val marks = session.marks.filter { it.page == page }
    // Outil « Éditer » : les lignes de texte de la page, chargees a la demande.
    val showBlocks = tools.editing && tools.tool == Tool.EditText
    val blocks by produceState(emptyList<TextBlock>(), page, showBlocks) {
        if (showBlocks) value = session.textBlocks(page)
    }
    val currentBlocks by rememberUpdatedState(blocks)
    val outline = MaterialTheme.colorScheme.primary
    val drawing = remember { mutableStateListOf<NPoint>() }
    var boxStart by remember { mutableStateOf<NPoint?>(null) }
    var boxEnd by remember { mutableStateOf<NPoint?>(null) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val active = tools.editing && tools.tool != Tool.Pan
    val tool = tools.tool

    var modifier = Modifier.fillMaxSize()
    if (active) {
        modifier = when (tool) {
            Tool.Pen, Tool.Highlighter -> modifier.pointerInput(tool, page) {
                fun norm(o: Offset) = NPoint((o.x / size.width).coerceIn(0f, 1f), (o.y / size.height).coerceIn(0f, 1f))
                detectDragGestures(
                    onDragStart = { drawing.clear(); drawing.add(norm(it)) },
                    onDrag = { change, _ -> change.consume(); drawing.add(norm(change.position)) },
                    onDragEnd = {
                        if (drawing.isNotEmpty()) {
                            val highlighter = tools.tool == Tool.Highlighter
                            session.marks.add(
                                InkMark(
                                    page = page,
                                    points = drawing.toList(),
                                    argb = tools.color,
                                    width = if (highlighter) 14f else tools.penWidth,
                                    opacity = if (highlighter) 0.4f else 1f,
                                ),
                            )
                        }
                        drawing.clear()
                    },
                    onDragCancel = { drawing.clear() },
                )
            }
            Tool.Whiteout -> modifier.pointerInput(tool, page) {
                fun norm(o: Offset) = NPoint((o.x / size.width).coerceIn(0f, 1f), (o.y / size.height).coerceIn(0f, 1f))
                detectDragGestures(
                    onDragStart = { boxStart = norm(it); boxEnd = norm(it) },
                    onDrag = { change, _ -> change.consume(); boxEnd = norm(change.position) },
                    onDragEnd = {
                        val a = boxStart; val b = boxEnd
                        if (a != null && b != null && abs(a.x - b.x) > 0.005f && abs(a.y - b.y) > 0.003f) {
                            session.marks.add(BoxMark(page, rectOf(a, b), InkColors.White))
                        }
                        boxStart = null; boxEnd = null
                    },
                    onDragCancel = { boxStart = null; boxEnd = null },
                )
            }
            Tool.Text, Tool.Signature, Tool.Eraser -> modifier.pointerInput(tool, page) {
                detectTapGestures { o ->
                    val p = NPoint(o.x / size.width, o.y / size.height)
                    when (tools.tool) {
                        Tool.Text -> {
                            val existing = session.marks.lastOrNull { it is TextMark && it.page == page && contains(it.bounds(), p, 0.01f) } as TextMark?
                            onTextRequest(TextRequest(page, existing?.x ?: p.x, existing?.y ?: p.y, existing))
                        }
                        Tool.Signature -> onSignatureNeeded(p)
                        Tool.Eraser -> {
                            val hit = session.marks.lastOrNull { it.page == page && hits(it, p) }
                            if (hit != null) session.marks.remove(hit)
                        }
                        else -> Unit
                    }
                }
            }
            Tool.EditText -> modifier.pointerInput(tool, page) {
                detectTapGestures { o ->
                    if (session.editingBlock != null) {
                        onCommitPending()
                        return@detectTapGestures
                    }
                    // Tolerance pensee pour un doigt : la ligne sous le doigt (a 10 dp pres),
                    // sinon la plus proche a moins de 28 dp.
                    val hit = nearestBlock(currentBlocks, o, size.width.toFloat(), size.height.toFloat(), 10.dp.toPx(), 28.dp.toPx())
                    when {
                        hit == null -> Unit
                        !hit.editable -> onMessage(tr(Str.TextNotEditableHint))
                        else -> session.startEditing(hit)
                    }
                }
            }
            Tool.Pan -> modifier
        }
    }

    Canvas(modifier) {
        val ptToPx = size.width / pageSize.width
        for (mark in marks) drawMark(mark, ptToPx) { textMark ->
            val fontPx = textMark.size * ptToPx
            val style = TextStyle(
                color = Color(textMark.argb),
                fontSize = with(density) { fontPx.toSp() },
                lineHeight = with(density) { (fontPx * TextMark.LINE_HEIGHT).toSp() },
                fontFamily = FontFamily.SansSerif,
            )
            drawText(measurer, textMark.text, Offset(textMark.x * size.width, textMark.y * size.height), style)
        }
        // Lignes modifiables (outil « Éditer »)
        if (showBlocks) {
            val editing = session.editingBlock
            for (b in blocks) {
                if (b == editing) continue
                val r = b.rect
                drawRect(
                    color = if (b.editable) outline.copy(alpha = 0.55f) else Color.Gray.copy(alpha = 0.35f),
                    topLeft = Offset(r.left * size.width - 2f, r.top * size.height - 1f),
                    size = Size((r.right - r.left) * size.width + 4f, (r.bottom - r.top) * size.height + 2f),
                    style = Stroke(width = 1.2f),
                )
            }
        }
        // Trait en cours
        if (drawing.isNotEmpty()) {
            val highlighter = tools.tool == Tool.Highlighter
            drawInk(drawing, Color(tools.color).copy(alpha = if (highlighter) 0.4f else 1f), (if (highlighter) 14f else tools.penWidth) * ptToPx)
        }
        // Rectangle en cours
        val a = boxStart; val b = boxEnd
        if (a != null && b != null) {
            val r = rectOf(a, b)
            drawRect(Color.White, Offset(r.left * size.width, r.top * size.height), Size((r.right - r.left) * size.width, (r.bottom - r.top) * size.height))
            drawRect(
                Color(0xFFCC542C),
                Offset(r.left * size.width, r.top * size.height),
                Size((r.right - r.left) * size.width, (r.bottom - r.top) * size.height),
                style = Stroke(width = 1.5f),
            )
        }
    }

    // Ligne en cours de modification : champ de texte pose exactement dessus.
    val editing = session.editingBlock
    if (showBlocks && editing != null && editing.page == page) {
        if (inlineEditor) {
            InlineTextEditor(
                block = editing,
                pageSize = pageSize,
                onTextChange = { session.editingText = it },
                onDone = { text -> onReplaceText(editing, text) },
                onCancel = { session.editingBlock = null },
            )
        } else {
            TextEditDialog(
                block = editing,
                text = session.editingText,
                onTextChange = { session.editingText = it },
                onDone = { onReplaceText(editing, session.editingText) },
                onCancel = { session.editingBlock = null },
            )
        }
    }
}

/** Telephone : la ligne a modifier dans une fenetre, avec un grand bouton Valider. */
@Composable
private fun TextEditDialog(
    block: TextBlock,
    text: String,
    onTextChange: (String) -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(block) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(tr(Str.EditTextTitle)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onDone() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Text(
                    tr(Str.EditTextHint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { Button(onClick = onDone) { Text(tr(Str.Validate)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(tr(Str.Cancel)) } },
    )
}

/** Champ de texte pose sur la ligne a modifier, avec Annuler / Valider. */
@Composable
private fun InlineTextEditor(
    block: TextBlock,
    pageSize: PageSize,
    onTextChange: (String) -> Unit,
    onDone: (String) -> Unit,
    onCancel: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val ptToPx = widthPx / pageSize.width
        var value by remember(block) { mutableStateOf(TextFieldValue(block.text, TextRange(block.text.length))) }
        val focus = remember { FocusRequester() }
        LaunchedEffect(block) { focus.requestFocus() }
        // Meme taille que le texte d'origine, mais jamais illisible.
        val fontPx = max(block.fontSize * ptToPx, with(density) { 13.sp.toPx() })
        val left = (block.rect.left * widthPx).roundToInt()
        val top = (block.rect.top * heightPx).roundToInt()
        val minWidth = with(density) { ((block.rect.right - block.rect.left) * widthPx).toDp() } + 12.dp
        val buttonsAbove = block.rect.bottom > 0.85f
        val colors = MaterialTheme.colorScheme

        @Composable
        fun Buttons() {
            Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(
                    onClick = onCancel,
                    shape = RoundedCornerShape(50),
                    color = colors.surfaceContainerHighest,
                    contentColor = colors.onSurface,
                    shadowElevation = 3.dp,
                ) {
                    Text(tr(Str.Cancel), Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
                }
                Surface(
                    onClick = { onDone(value.text) },
                    shape = RoundedCornerShape(50),
                    color = colors.primary,
                    contentColor = colors.onPrimary,
                    shadowElevation = 3.dp,
                ) {
                    Text(tr(Str.Validate), Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        Column(
            Modifier.offset {
                IntOffset(left - 6, if (buttonsAbove) top - 4 - 44.dp.roundToPx() else top - 4)
            },
        ) {
            if (buttonsAbove) Buttons()
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Color.White,
                border = BorderStroke(2.dp, colors.primary),
                shadowElevation = 6.dp,
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        onTextChange(it.text)
                    },
                    singleLine = true,
                    textStyle = TextStyle(color = Color(0xFF1A1A1A), fontSize = with(density) { fontPx.toSp() }),
                    cursorBrush = SolidColor(colors.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onDone(value.text) }),
                    modifier = Modifier
                        .widthIn(min = minWidth)
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                        .focusRequester(focus)
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (e.key) {
                                Key.Escape -> { onCancel(); true }
                                Key.Enter, Key.NumPadEnter -> { onDone(value.text); true }
                                else -> false
                            }
                        },
                )
            }
            if (!buttonsAbove) Buttons()
        }
    }
}

private fun DrawScope.drawMark(mark: Mark, ptToPx: Float, drawTextMark: DrawScope.(TextMark) -> Unit) {
    when (mark) {
        is InkMark -> drawInk(mark.points, Color(mark.argb).copy(alpha = mark.opacity), mark.width * ptToPx)
        is BoxMark -> {
            val r = mark.rect
            drawRect(Color(mark.argb), Offset(r.left * size.width, r.top * size.height), Size((r.right - r.left) * size.width, (r.bottom - r.top) * size.height))
        }
        is TextMark -> drawTextMark(mark)
        is SignatureMark -> {
            val r = mark.rect
            for (stroke in mark.strokes) {
                drawInk(
                    stroke.map { NPoint(r.left + it.x * (r.right - r.left), r.top + it.y * (r.bottom - r.top)) },
                    Color(mark.argb),
                    mark.width * ptToPx,
                )
            }
        }
    }
}

private fun DrawScope.drawInk(points: List<NPoint>, color: Color, widthPx: Float) {
    if (points.isEmpty()) return
    val path = Path()
    path.moveTo(points[0].x * size.width, points[0].y * size.height)
    if (points.size == 1) path.lineTo(points[0].x * size.width + 0.1f, points[0].y * size.height)
    for (i in 1 until points.size) path.lineTo(points[i].x * size.width, points[i].y * size.height)
    drawPath(path, color, style = Stroke(width = max(widthPx, 1f), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun rectOf(a: NPoint, b: NPoint) = NormRect(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))

private fun contains(r: NormRect, p: NPoint, margin: Float) =
    p.x >= r.left - margin && p.x <= r.right + margin && p.y >= r.top - margin && p.y <= r.bottom + margin

/** Le point touche-t-il l'annotation ? (pour la gomme) */
private fun hits(mark: Mark, p: NPoint): Boolean = when (mark) {
    is InkMark -> mark.points.any { abs(it.x - p.x) < 0.02f && abs(it.y - p.y) < 0.015f }
    else -> contains(mark.bounds(), p, 0.01f)
}

/** Distance (en pixels) entre un point et le rectangle d'une ligne ; 0 si le point est dedans. */
private fun distanceTo(b: TextBlock, o: Offset, w: Float, h: Float): Float {
    val left = b.rect.left * w
    val right = b.rect.right * w
    val top = b.rect.top * h
    val bottom = b.rect.bottom * h
    val dx = maxOf(left - o.x, 0f, o.x - right)
    val dy = maxOf(top - o.y, 0f, o.y - bottom)
    return kotlin.math.sqrt(dx * dx + dy * dy)
}

/** La ligne touchee : la plus petite qui contient le point (avec une marge), sinon la plus proche. */
internal fun nearestBlock(blocks: List<TextBlock>, o: Offset, w: Float, h: Float, margin: Float, maxDistance: Float): TextBlock? {
    val inside = blocks.filter { distanceTo(it, o, w, h) <= margin }
    if (inside.isNotEmpty()) {
        // Plusieurs candidates : la plus proche du doigt, puis la plus petite.
        return inside.minWith(compareBy<TextBlock>({ distanceTo(it, o, w, h) }, { (it.rect.right - it.rect.left) * (it.rect.bottom - it.rect.top) }))
    }
    return blocks.minByOrNull { distanceTo(it, o, w, h) }?.takeIf { distanceTo(it, o, w, h) <= maxDistance }
}
