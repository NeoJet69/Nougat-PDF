package app.nougat.pdf.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Couleurs caramel et creme, comme le chat. */
object NougatColors {
    val Caramel = Color(0xFFB0703A)
    val CaramelDark = Color(0xFF8A5226)
    val Cream = Color(0xFFFFF8F0)
    val Cocoa = Color(0xFF3B2A20)
    val Pink = Color(0xFFE89AA4)
    val Highlight = Color(0x66FFD54F)
    val HighlightCurrent = Color(0x99FF8F00)
}

private val Light = lightColorScheme(
    primary = NougatColors.CaramelDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDCC2),
    onPrimaryContainer = Color(0xFF331200),
    secondary = Color(0xFF755846),
    background = NougatColors.Cream,
    onBackground = NougatColors.Cocoa,
    surface = NougatColors.Cream,
    onSurface = NougatColors.Cocoa,
    surfaceVariant = Color(0xFFF3DFD2),
    surfaceContainer = Color(0xFFF7ECE4),
    surfaceContainerHigh = Color(0xFFF1E5DD),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB77C),
    onPrimary = Color(0xFF4F2500),
    primaryContainer = Color(0xFF6E3A0F),
    onPrimaryContainer = Color(0xFFFFDCC2),
    secondary = Color(0xFFE4BFA8),
    background = Color(0xFF1C1612),
    onBackground = Color(0xFFEFE0D7),
    surface = Color(0xFF1C1612),
    onSurface = Color(0xFFEFE0D7),
    surfaceVariant = Color(0xFF52443B),
    surfaceContainer = Color(0xFF29211C),
    surfaceContainerHigh = Color(0xFF332A24),
)

@Composable
fun NougatTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
