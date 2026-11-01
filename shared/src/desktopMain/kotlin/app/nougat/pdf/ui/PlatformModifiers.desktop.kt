package app.nougat.pdf.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput

@OptIn(ExperimentalComposeUiApi::class)
actual fun Modifier.ctrlWheelZoom(onZoom: (factor: Float, position: androidx.compose.ui.geometry.Offset) -> Unit): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                // Passe « Initial » : on voit la molette avant la liste, qui sinon defilerait.
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Scroll && event.keyboardModifiers.isCtrlPressed) {
                    val dy = event.changes.sumOf { it.scrollDelta.y.toDouble() }
                    val position = event.changes.firstOrNull()?.position ?: androidx.compose.ui.geometry.Offset.Zero
                    if (dy != 0.0) onZoom(if (dy < 0) 1.1f else 1f / 1.1f, position)
                    event.changes.forEach { it.consume() }
                }
            }
        }
    }
