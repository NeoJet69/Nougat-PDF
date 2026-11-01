package app.nougat.pdf.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput

@OptIn(ExperimentalComposeUiApi::class)
actual fun Modifier.ctrlWheelZoom(onZoom: (factor: Float) -> Unit): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                // Passe « Initial » : on voit la molette avant la liste, qui sinon defilerait.
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Scroll && event.keyboardModifiers.isCtrlPressed) {
                    val dy = event.changes.sumOf { it.scrollDelta.y.toDouble() }
                    if (dy != 0.0) onZoom(if (dy < 0) 1.1f else 1f / 1.1f)
                    event.changes.forEach { it.consume() }
                }
            }
        }
    }
