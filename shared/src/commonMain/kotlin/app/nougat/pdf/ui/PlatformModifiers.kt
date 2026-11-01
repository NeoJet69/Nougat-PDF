package app.nougat.pdf.ui

import androidx.compose.ui.Modifier

/** Ctrl + molette de la souris pour zoomer (ordinateur uniquement). */
expect fun Modifier.ctrlWheelZoom(onZoom: (factor: Float) -> Unit): Modifier
