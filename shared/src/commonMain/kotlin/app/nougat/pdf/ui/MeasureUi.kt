package app.nougat.pdf.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nougat.pdf.engine.MeasureKind
import app.nougat.pdf.engine.Measurement
import app.nougat.pdf.engine.Measures
import app.nougat.pdf.engine.NPoint
import app.nougat.pdf.engine.PageSize
import app.nougat.pdf.i18n.Str
import app.nougat.pdf.i18n.tr
import app.nougat.pdf.viewer.MEASURE_INK
import app.nougat.pdf.viewer.ViewerState
import app.nougat.pdf.viewer.labelAnchor

/*
 * Metre sur plan : la barre (echelle, longueur ou surface, totaux), les mesures dessinees sur les
 * pages, et les fenetres pour choisir l'echelle ou l'etalonner sur une cote connue.
 */

private val MeasureColor = Color(MEASURE_INK)
private val CurrentColor = Color(0xFF1E5BD8)

@Composable
fun MeasureBar(state: ViewerState, compact: Boolean, modifier: Modifier) {
    val m = state.measure
    if (!m.active) return
    val p = LocalNougatPalette.current
    val hint = when {
        m.calibrating -> tr(Str.MeasureHintCalibrate)
        m.metersPerPoint == null -> tr(Str.MeasureHintScale)
        m.kind == MeasureKind.Area -> tr(Str.MeasureHintArea)
        else -> tr(Str.MeasureHintLength)
    }
    val scale = m.ratio?.let { if (m.calibrated) tr(Str.MeasureCalibratedScale, it) else tr(Str.MeasureScale, it) } ?: tr(Str.MeasureNoScale)
    Island(modifier.then(if (compact) Modifier.fillMaxWidth() else Modifier.widthIn(max = 760.dp)), shape = RoundedCornerShape(24.dp), padding = 8.dp) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(50)).background(MeasureColor), contentAlignment = Alignment.Center) {
                    Icon(NougatIcons.Measure, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
                }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text(tr(Str.MeasureTitle), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(hint, style = MaterialTheme.typography.labelSmall, color = p.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { m.scaleDialog = true }) { Text(scale, maxLines = 1) }
                IslandButton(Icons.Filled.Close, tr(Str.Close), onClick = m::stop, size = 36.dp, iconSize = 18.dp)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = m.kind == MeasureKind.Length && !m.calibrating, onClick = { m.cancelCalibration(); m.pick(MeasureKind.Length) }, label = { Text(tr(Str.MeasureLength)) })
                FilterChip(selected = m.kind == MeasureKind.Area && !m.calibrating, onClick = { m.cancelCalibration(); m.pick(MeasureKind.Area) }, label = { Text(tr(Str.MeasureArea)) })
                Spacer(Modifier.weight(1f))
                if (m.canUndo) IslandButton(NougatIcons.Undo, tr(Str.MeasureUndo), onClick = m::undo, size = 36.dp, iconSize = 18.dp)
                if (m.current != null) TextButton(onClick = m::finish, enabled = m.current?.complete == true) { Text(tr(Str.MeasureFinish)) }
            }
            val totals = state.measureTotals()
            if (totals != null && m.all.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tr(Str.MeasureTotals, totals.first, totals.second),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                        maxLines = 2,
                    )
                    TextButton(onClick = state::copyMeasures) { Text(tr(Str.MeasureCopy)) }
                    Box(
                        Modifier.clip(RoundedCornerShape(50)).background(MeasureColor)
                            .clickable(onClick = state::addMeasuresToPlan)
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                    ) {
                        Text(tr(Str.MeasureAddToPlan), style = MaterialTheme.typography.labelLarge, color = Color.White)
                    }
                }
            }
        }
    }
}

/** Les mesures de la page, et les touchers qui posent les points. */
@Composable
fun MeasureOverlay(state: ViewerState, page: Int, pageSize: PageSize) {
    val m = state.measure
    val measurer = rememberTextMeasurer()
    val latestSize by rememberUpdatedState(pageSize)
    val onPage = m.items.filter { it.page == page }
    val current = m.current?.takeIf { it.page == page }
    val calibration = m.calibrationLine?.takeIf { it.page == page }
    val labelStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
    Canvas(
        Modifier.fillMaxSize().pointerInput(page) {
            detectTapGestures { o ->
                m.tap(page, NPoint((o.x / this.size.width).coerceIn(0f, 1f), (o.y / this.size.height).coerceIn(0f, 1f)), latestSize)
            }
        },
    ) {
        fun point(n: NPoint) = Offset(n.x * this.size.width, n.y * this.size.height)

        fun shape(item: Measurement, color: Color, dashed: Boolean = false) {
            if (item.points.isEmpty()) return
            val path = Path().apply {
                moveTo(point(item.points[0]).x, point(item.points[0]).y)
                for (i in 1 until item.points.size) point(item.points[i]).let { lineTo(it.x, it.y) }
                if (item.kind == MeasureKind.Area && item.points.size >= 3) close()
            }
            if (item.kind == MeasureKind.Area && item.points.size >= 3) drawPath(path, color.copy(alpha = 0.14f))
            drawPath(
                path, color,
                style = Stroke(width = 2.5f, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 6f)) else null),
            )
            for (pt in item.points) {
                drawCircle(Color.White, radius = 6f, center = point(pt))
                drawCircle(color, radius = 4f, center = point(pt))
            }
        }

        fun label(item: Measurement, color: Color) {
            val text = state.measureLabel(item) ?: return
            val layout = measurer.measure(text, labelStyle)
            val anchor = point(labelAnchor(item))
            val w = layout.size.width + 12f
            val h = layout.size.height + 6f
            val topLeft = Offset(anchor.x - w / 2f, anchor.y - h - 6f)
            drawRoundRect(color, topLeft, Size(w, h), CornerRadius(h / 2f))
            drawText(layout, topLeft = Offset(topLeft.x + 6f, topLeft.y + 3f))
        }

        for (item in onPage) {
            shape(item, MeasureColor)
            label(item, MeasureColor)
        }
        current?.let {
            shape(it, CurrentColor)
            if (it.complete) label(it, CurrentColor)
        }
        calibration?.let { shape(it, Color(0xFFD32F2F), dashed = true) }
    }
}

/** « Echelle du plan » : les echelles courantes, une autre, ou etalonner sur une cote. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MeasureScaleDialog(state: ViewerState) {
    val m = state.measure
    var custom by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { m.scaleDialog = false },
        icon = { Icon(NougatIcons.Measure, contentDescription = null) },
        title = { Text(tr(Str.MeasureScaleTitle)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr(Str.MeasureScaleExplain), style = MaterialTheme.typography.bodyMedium, color = LocalNougatPalette.current.muted)
                m.foundRatio?.let { found ->
                    ChoiceRow(tr(Str.MeasureFoundOnPlan, found), m.ratio == found.toLong() && !m.calibrated) { m.chooseRatio(found) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (r in Measures.COMMON_RATIOS) {
                        FilterChip(selected = m.ratio == r.toLong() && !m.calibrated, onClick = { m.chooseRatio(r) }, label = { Text("1:$r") })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = custom,
                        onValueChange = { v -> custom = v.filter(Char::isDigit).take(5) },
                        label = { Text(tr(Str.MeasureCustomRatio)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(enabled = (custom.toIntOrNull() ?: 0) > 0, onClick = { custom.toIntOrNull()?.let(m::chooseRatio) }) {
                        Text(tr(Str.MeasureApply))
                    }
                }
                TextButton(onClick = m::startCalibration) { Text(tr(Str.MeasureCalibrate)) }
            }
        },
        confirmButton = { TextButton(onClick = { m.scaleDialog = false }) { Text(tr(Str.Close)) } },
    )
}

/** La ligne d'etalonnage est tracee : sa longueur reelle. */
@Composable
fun MeasureCalibrateDialog(state: ViewerState) {
    val m = state.measure
    var value by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf(1.0) }
    val meters = Measures.parseNumber(value)?.let { it * unit }
    AlertDialog(
        onDismissRequest = m::cancelCalibration,
        icon = { Icon(NougatIcons.Measure, contentDescription = null) },
        title = { Text(tr(Str.MeasureCalibrateTitle)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr(Str.MeasureCalibrateExplain), style = MaterialTheme.typography.bodyMedium, color = LocalNougatPalette.current.muted)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { v -> value = v.filter { it.isDigit() || it == '.' || it == ',' }.take(10) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    for ((name, factor) in listOf("m" to 1.0, "cm" to 0.01, "mm" to 0.001)) {
                        FilterChip(selected = unit == factor, onClick = { unit = factor }, label = { Text(name) })
                    }
                }
            }
        },
        confirmButton = { Button(enabled = meters != null, onClick = { meters?.let(state::calibrateMeasure) }) { Text(tr(Str.MeasureApply)) } },
        dismissButton = { TextButton(onClick = m::cancelCalibration) { Text(tr(Str.Cancel)) } },
    )
}
