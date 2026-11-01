package app.nougat.pdf.ui

import androidx.compose.foundation.Canvas
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
) {
    val marks = session.marks.filter { it.page == page }
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
