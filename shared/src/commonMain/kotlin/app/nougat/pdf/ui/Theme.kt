package app.nougat.pdf.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Palette « Original » d'ArtisanGo : un orange chaud sur des gris neutres.
 * Le noir porte le texte sur l'orange de nuit (meilleur contraste que le blanc).
 */
object NougatColors {
    val Orange = Color(0xFFEA7D50)          // original_primary (nuit)
    val OrangeBrand = Color(0xFFCC542C)     // primary_brand (jour)
    val OrangeContainerNight = Color(0xFF7A4128)
    val Night = Color(0xFF121212)           // original_bg
    val OnOrangeNight = Color(0xFF1A1A1A)   // original_text_on_primary

    val StatusOk = Color(0xFF81C784)
    val StatusWarning = Color(0xFFFFB74D)
    val StatusBad = Color(0xFFFF8A80)

    // Logo de Nougat (le chat)
    val Caramel = Color(0xFFB0703A)
    val CaramelDark = Color(0xFF8A5226)
    val Cream = Color(0xFFFFF8F0)
    val Cocoa = Color(0xFF3B2A20)
    val Pink = Color(0xFFE89AA4)

    // Recherche
    val Highlight = Color(0x66FFD54F)
    val HighlightCurrent = Color(0x99FF8F00)
}

private val Light = lightColorScheme(
    primary = NougatColors.OrangeBrand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE3D6),
    onPrimaryContainer = Color(0xFF3A1406),
    secondary = Color(0xFF5F5F5F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDEDED),
    onSecondaryContainer = Color(0xFF1A1A1A),
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFFAFAFA),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFEDEDED),
    onSurfaceVariant = Color(0xFF5F5F5F),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F7F7),
    surfaceContainer = Color(0xFFF2F2F2),
    surfaceContainerHigh = Color(0xFFECECEC),
    surfaceContainerHighest = Color(0xFFE6E6E6),
    outline = Color(0xFFBDBDBD),
    outlineVariant = Color(0xFFE0E0E0),
    error = Color(0xFFC62828),
)

private val Dark = darkColorScheme(
    primary = NougatColors.Orange,
    onPrimary = NougatColors.OnOrangeNight,
    primaryContainer = NougatColors.OrangeContainerNight,
    onPrimaryContainer = Color(0xFFFFDBCC),
    secondary = Color(0xFFBDBDBD),
    onSecondary = Color(0xFF1A1A1A),
    secondaryContainer = Color(0xFF2C2C2C),
    onSecondaryContainer = Color(0xFFEDEDED),
    background = NougatColors.Night,
    onBackground = Color.White,
    surface = NougatColors.Night,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFBDBDBD),
    surfaceContainerLowest = Color(0xFF0D0D0D),
    surfaceContainerLow = Color(0xFF181818),
    surfaceContainer = Color(0xFF1E1E1E),
    surfaceContainerHigh = Color(0xFF242424),
    surfaceContainerHighest = Color(0xFF2E2E2E),
    outline = Color(0xFF616161),
    outlineVariant = Color(0xFF333333),
    error = NougatColors.StatusBad,
)

private val NougatShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun NougatTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        shapes = NougatShapes,
        content = content,
    )
}
