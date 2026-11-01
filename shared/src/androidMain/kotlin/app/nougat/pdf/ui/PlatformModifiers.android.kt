package app.nougat.pdf.ui

import androidx.compose.ui.Modifier

/** Sur Android, on zoome en pincant l'ecran : rien a faire ici. */
actual fun Modifier.ctrlWheelZoom(onZoom: (factor: Float) -> Unit): Modifier = this
