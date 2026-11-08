package app.nougat.pdf.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.nougat.pdf.engine.translationLanguage
import app.nougat.pdf.i18n.Str
import app.nougat.pdf.i18n.tr
import app.nougat.pdf.viewer.ViewerState

/* Lecture a voix haute : la barre de commandes, et le paragraphe lu surligne sur sa page. */

private val ReadColor = Color(0xFF2E7D32)

@Composable
fun ReadAloudBar(state: ViewerState, compact: Boolean, modifier: Modifier) {
    val r = state.readAloud
    if (!r.active) return
    val p = LocalNougatPalette.current
    val language = translationLanguage(r.language)?.nativeName ?: r.language
    val where = tr(Str.ReadAloudWhere, r.page + 1, r.index + 1) + if (r.translated) " · " + tr(Str.ReadAloudTranslated, language) else ""
    val speed = when (r.rate) {
        1.25f -> "1,25×"
        0.8f -> "0,8×"
        else -> "1×"
    }
    Island(modifier.then(if (compact) Modifier.fillMaxWidth() else Modifier.widthIn(max = 640.dp)), shape = RoundedCornerShape(24.dp), padding = 8.dp) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(50)).background(ReadColor), contentAlignment = Alignment.Center) {
            Icon(NougatIcons.Speaker, contentDescription = null, tint = Color.White, modifier = Modifier.size(19.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(tr(Str.ReadAloudTitle), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(where, style = MaterialTheme.typography.labelSmall, color = p.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IslandButton(NougatIcons.SkipPrevious, tr(Str.ReadAloudPrevious), onClick = state::previousParagraph, size = 40.dp, iconSize = 20.dp)
            if (r.playing) {
                IslandButton(NougatIcons.Pause, tr(Str.ReadAloudPause), onClick = state::pauseReadAloud, size = 44.dp, iconSize = 24.dp, active = true)
            } else {
                IslandButton(Icons.Filled.PlayArrow, tr(Str.ReadAloudPlay), onClick = state::resumeReadAloud, size = 44.dp, iconSize = 24.dp, active = true)
            }
            IslandButton(NougatIcons.SkipNext, tr(Str.ReadAloudNext), onClick = state::nextParagraph, size = 40.dp, iconSize = 20.dp)
            TextButton(onClick = state::cycleReadSpeed) { Text(tr(Str.ReadAloudSpeed, speed), maxLines = 1) }
            IslandButton(Icons.Filled.Close, tr(Str.Close), onClick = state::stopReadAloud, size = 36.dp, iconSize = 18.dp)
        }
    }
}

/** Le paragraphe en cours de lecture, surligne sur la page [page]. */
@Composable
fun ReadAloudHighlight(state: ViewerState, page: Int) {
    val r = state.readAloud
    if (!r.active || r.page != page || r.rects.isEmpty()) return
    val rects = r.rects
    Canvas(Modifier.fillMaxSize()) {
        for (rect in rects) {
            val pad = 3f
            drawRoundRect(
                color = ReadColor.copy(alpha = 0.18f),
                topLeft = Offset(rect.left * size.width - pad, rect.top * size.height - pad),
                size = Size((rect.right - rect.left) * size.width + 2 * pad, (rect.bottom - rect.top) * size.height + 2 * pad),
                cornerRadius = CornerRadius(4f),
            )
        }
    }
}
