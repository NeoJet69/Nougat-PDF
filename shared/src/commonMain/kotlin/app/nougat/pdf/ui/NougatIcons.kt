package app.nougat.pdf.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Les quelques icones absentes du jeu de base de Material. */
object NougatIcons {

    val Folder: ImageVector by lazy {
        materialIcon("Folder") {
            moveTo(10f, 4f); horizontalLineTo(4f)
            curveTo(2.9f, 4f, 2f, 4.9f, 2f, 6f)
            verticalLineToRelative(12f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
            horizontalLineToRelative(16f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            verticalLineTo(8f)
            curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
            horizontalLineToRelative(-8f)
            close()
        }
    }

    val Remove: ImageVector by lazy {
        materialIcon("Remove") {
            moveTo(19f, 13f); horizontalLineTo(5f); verticalLineToRelative(-2f); horizontalLineToRelative(14f); close()
        }
    }

    val FitWidth: ImageVector by lazy {
        materialIcon("FitWidth") {
            // Deux fleches horizontales opposees
            moveTo(3f, 11f); horizontalLineTo(21f); verticalLineToRelative(2f); horizontalLineTo(3f); close()
            moveTo(3f, 12f); lineTo(7f, 8f); verticalLineToRelative(8f); close()
            moveTo(21f, 12f); lineTo(17f, 16f); verticalLineToRelative(-8f); close()
        }
    }

    private fun materialIcon(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).path(fill = SolidColor(Color.Black), pathBuilder = block).build()

    /** Nougat, le chat, en logo. */
    val NougatLogo: ImageVector by lazy {
        ImageVector.Builder(
            name = "Nougat",
            defaultWidth = 108.dp,
            defaultHeight = 108.dp,
            viewportWidth = 108f,
            viewportHeight = 108f,
        ).apply {
            // Oreilles
            path(fill = SolidColor(NougatColors.CaramelDark)) {
                moveTo(20f, 52f); lineTo(26f, 12f); lineTo(52f, 34f); close()
                moveTo(88f, 52f); lineTo(82f, 12f); lineTo(56f, 34f); close()
            }
            path(fill = SolidColor(NougatColors.Pink)) {
                moveTo(28f, 44f); lineTo(31f, 22f); lineTo(45f, 34f); close()
                moveTo(80f, 44f); lineTo(77f, 22f); lineTo(63f, 34f); close()
            }
            // Tete
            path(fill = SolidColor(NougatColors.Caramel)) {
                ellipse(54f, 62f, 38f, 32f)
            }
            // Museau creme
            path(fill = SolidColor(NougatColors.Cream)) {
                ellipse(54f, 76f, 18f, 13f)
            }
            // Yeux
            path(fill = SolidColor(NougatColors.Cocoa)) {
                ellipse(40f, 60f, 5f, 6.5f)
                ellipse(68f, 60f, 5f, 6.5f)
            }
            path(fill = SolidColor(Color.White)) {
                ellipse(41.6f, 57.8f, 1.6f, 1.6f)
                ellipse(69.6f, 57.8f, 1.6f, 1.6f)
            }
            // Nez
            path(fill = SolidColor(NougatColors.Pink)) {
                moveTo(49f, 70f); lineTo(59f, 70f); lineTo(54f, 76f); close()
            }
            // Bouche et moustaches
            path(
                stroke = SolidColor(NougatColors.Cocoa),
                strokeLineWidth = 2f,
            ) {
                moveTo(54f, 76f); lineTo(54f, 80f)
                moveTo(54f, 80f); curveTo(51f, 84f, 47f, 83f, 46f, 80f)
                moveTo(54f, 80f); curveTo(57f, 84f, 61f, 83f, 62f, 80f)
                moveTo(36f, 74f); lineTo(14f, 70f)
                moveTo(36f, 78f); lineTo(15f, 80f)
                moveTo(72f, 74f); lineTo(94f, 70f)
                moveTo(72f, 78f); lineTo(93f, 80f)
            }
        }.build()
    }

    private fun PathBuilder.ellipse(cx: Float, cy: Float, rx: Float, ry: Float) {
        moveTo(cx - rx, cy)
        arcTo(rx, ry, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx + rx, y1 = cy)
        arcTo(rx, ry, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = cx - rx, y1 = cy)
        close()
    }
}
