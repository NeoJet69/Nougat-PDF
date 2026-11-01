package app.nougat.pdf.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * « Caramel Glass » : la palette « Original » d'ArtisanGo (orange #EA7D50 / #CC542C) sur une
 * toile chaude, des ilots flottants arrondis et des pages posees comme du papier sur un bureau.
 */
object NougatColors {
    val Orange = Color(0xFFEA7D50)          // original_primary (nuit)
    val OrangeBrand = Color(0xFFCC542C)     // primary_brand (jour)
    val OrangeLight = Color(0xFFF4A07A)
    val OrangeContainerNight = Color(0xFF7A4128)
    val Night = Color(0xFF121212)           // original_bg
    val OnOrangeNight = Color(0xFF1A1A1A)   // original_text_on_primary

    val StatusOk = Color(0xFF81C784)
    val StatusWarning = Color(0xFFFFB74D)
    val StatusBad = Color(0xFFFF8A80)

    // Nougat, le chat
    val Caramel = Color(0xFFB0703A)
    val CaramelDark = Color(0xFF8A5226)
    val Cream = Color(0xFFFFF8F0)
    val Cocoa = Color(0xFF3B2A20)
    val Pink = Color(0xFFE89AA4)

    // Recherche
    val Highlight = Color(0x66FFD54F)
    val HighlightCurrent = Color(0x99FF8F00)
}

/** Le degrade signature : boutons principaux, mode actif du dock, bulles de l'utilisateur. */
val NougatGradient: Brush = Brush.linearGradient(listOf(Color(0xFFF39A6B), Color(0xFFE5703F), Color(0xFFCC542C)))

/** Couleurs propres a Nougat (en plus du schema Material). */
@Immutable
data class NougatPalette(
    val dark: Boolean,
    val canvasTop: Color,
    val canvasBottom: Color,
    val glow: Color,
    val glow2: Color,
    val island: Color,
    val islandBorder: Color,
    val shadow: Color,
    val paperShadow: Color,
    val muted: Color,
)

private val LightPalette = NougatPalette(
    dark = false,
    canvasTop = Color(0xFFFBF6F1),
    canvasBottom = Color(0xFFF1E7DE),
    glow = Color(0x33F39A6B),
    glow2 = Color(0x22FFC7A6),
    island = Color(0xF2FFFFFF),
    islandBorder = Color(0x1A8A5226),
    shadow = Color(0x40733A1C),
    paperShadow = Color(0x55593322),
    muted = Color(0xFF8C7B71),
)

private val DarkPalette = NougatPalette(
    dark = true,
    canvasTop = Color(0xFF1A1513),
    canvasBottom = Color(0xFF0F0C0B),
    glow = Color(0x40EA7D50),
    glow2 = Color(0x1F7A4128),
    island = Color(0xEB241E1B),
    islandBorder = Color(0x26FFFFFF),
    shadow = Color(0x99000000),
    paperShadow = Color(0xCC000000),
    muted = Color(0xFFA89A92),
)

val LocalNougatPalette = staticCompositionLocalOf { LightPalette }

private val Light = lightColorScheme(
    primary = NougatColors.OrangeBrand,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE3D6),
    onPrimaryContainer = Color(0xFF3A1406),
    secondary = Color(0xFF6F5F57),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF1E6DF),
    onSecondaryContainer = Color(0xFF2A1F1A),
    background = Color(0xFFFBF6F1),
    onBackground = Color(0xFF231A15),
    surface = Color(0xFFFFFBF8),
    onSurface = Color(0xFF231A15),
    surfaceVariant = Color(0xFFF1E6DF),
    onSurfaceVariant = Color(0xFF6F5F57),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFCF7F3),
    surfaceContainer = Color(0xFFF7F0EA),
    surfaceContainerHigh = Color(0xFFF2E9E2),
    surfaceContainerHighest = Color(0xFFECE1D9),
    outline = Color(0xFFCBB8AC),
    outlineVariant = Color(0xFFE8DBD2),
    error = Color(0xFFC62828),
    inverseSurface = Color(0xFF2E2420),
    inverseOnSurface = Color(0xFFFBEFE8),
)

private val Dark = darkColorScheme(
    primary = NougatColors.Orange,
    onPrimary = NougatColors.OnOrangeNight,
    primaryContainer = NougatColors.OrangeContainerNight,
    onPrimaryContainer = Color(0xFFFFDBCC),
    secondary = Color(0xFFC9B8AE),
    onSecondary = Color(0xFF1A1A1A),
    secondaryContainer = Color(0xFF3A302B),
    onSecondaryContainer = Color(0xFFF1E3DA),
    background = Color(0xFF141110),
    onBackground = Color(0xFFF5ECE6),
    surface = Color(0xFF1C1715),
    onSurface = Color(0xFFF5ECE6),
    surfaceVariant = Color(0xFF332A26),
    onSurfaceVariant = Color(0xFFC9B8AE),
    surfaceContainerLowest = Color(0xFF0E0B0A),
    surfaceContainerLow = Color(0xFF191412),
    surfaceContainer = Color(0xFF211B18),
    surfaceContainerHigh = Color(0xFF2A231F),
    surfaceContainerHighest = Color(0xFF342B27),
    outline = Color(0xFF6E5F57),
    outlineVariant = Color(0xFF3A302B),
    error = NougatColors.StatusBad,
    inverseSurface = Color(0xFFF5ECE6),
    inverseOnSurface = Color(0xFF231A15),
)

private val NougatShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

/** Textes un peu plus grands et plus affirmes que Material par defaut. */
private val NougatTypography: Typography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
        bodyLarge = bodyLarge.copy(fontSize = 16.sp),
        bodyMedium = bodyMedium.copy(fontSize = 15.sp),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
        labelMedium = labelMedium.copy(fontWeight = FontWeight.Medium, fontSize = 13.sp),
    )
}

/** Theme choisi par l'utilisateur. */
enum class ThemeMode { Auto, Light, Dark }

@Composable
fun NougatTheme(mode: ThemeMode = ThemeMode.Auto, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.Auto -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val scheme = if (dark) Dark else Light
    CompositionLocalProvider(LocalNougatPalette provides if (dark) DarkPalette else LightPalette) {
        MaterialTheme(colorScheme = scheme, shapes = NougatShapes, typography = NougatTypography) {
            // Sans « Surface », le texte serait noir par defaut : illisible en theme sombre.
            CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
        }
    }
}
