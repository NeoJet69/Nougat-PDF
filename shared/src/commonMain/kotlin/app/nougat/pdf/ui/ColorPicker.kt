package app.nougat.pdf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nougat.pdf.i18n.Str
import app.nougat.pdf.i18n.tr
import kotlin.math.max
import kotlin.math.min

/*
 * Couleur libre (comme dans ArtisanGo) : un curseur de teinte, un curseur de profondeur, et le
 * code « #RRGGBB ». La teinte seule ne donnerait que des couleurs vives ; la profondeur va du
 * pastel au profond en passant par le vif, ce qui donne aussi le bleu marine, le bordeaux, le vert
 * sapin... Les curseurs se replacent d'apres la couleur choisie (pastille, code).
 */
internal object FreeColor {
    private const val PIVOT = 45f
    private const val S_LIGHT = 0.25f
    private const val S_PIVOT = 0.70f
    private const val S_DEEP = 1.00f
    private const val V_LIGHT = 1.00f
    private const val V_PIVOT = 0.92f
    private const val V_DEEP = 0.30f

    /** Teinte (0..360) et profondeur (0..100) vers une couleur ARGB opaque. */
    fun fromSliders(hue: Float, depth: Float): Int {
        val p = depth.coerceIn(0f, 100f)
        val s: Float
        val v: Float
        if (p <= PIVOT) {
            val t = p / PIVOT
            s = S_LIGHT + (S_PIVOT - S_LIGHT) * t
            v = V_LIGHT + (V_PIVOT - V_LIGHT) * t
        } else {
            val t = (p - PIVOT) / (100f - PIVOT)
            s = S_PIVOT + (S_DEEP - S_PIVOT) * t
            v = V_PIVOT + (V_DEEP - V_PIVOT) * t
        }
        return Color.hsv(hue.coerceIn(0f, 359.9f), s, v).toArgb()
    }

    /** Teinte (0..360), saturation et valeur (0..1). */
    fun hsv(argb: Int): FloatArray {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val h = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }.let { if (it < 0f) it + 360f else it }
        return floatArrayOf(h, if (mx == 0f) 0f else d / mx, mx)
    }

    fun depthOf(argb: Int): Float {
        val v = hsv(argb)[2]
        val raw = if (v >= V_PIVOT) (V_LIGHT - v) / (V_LIGHT - V_PIVOT) * PIVOT else PIVOT + (V_PIVOT - v) / (V_PIVOT - V_DEEP) * (100f - PIVOT)
        return raw.coerceIn(0f, 100f)
    }

    /** « #RRGGBB » (diese et casse facultatifs) vers une couleur opaque, ou null. */
    fun parse(text: String): Int? {
        val digits = text.trim().removePrefix("#")
        if (!digits.matches(Regex("[0-9A-Fa-f]{6}"))) return null
        return (digits.toLong(16).toInt()) or (0xFF shl 24)
    }

    fun hex(argb: Int): String = "#" + (argb and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')

    val spectrum: List<Color> = List(13) { Color.hsv(min(it * 30f, 359.9f), 0.85f, 0.95f) }

    fun depthGradient(hue: Float): List<Color> = List(12) { Color(fromSliders(hue, it * 100f / 11f)) }
}

/** Les deux curseurs et le code couleur, sous les pastilles. */
@Composable
internal fun FreeColorPicker(color: Int, onColor: (Int) -> Unit, modifier: Modifier = Modifier) {
    val hsv = FreeColor.hsv(color)
    // La teinte d'une couleur grise (noir, gris) est indefinie : on garde la derniere choisie.
    var grayHue by remember { mutableStateOf(18f) }
    val hue = if (hsv[1] > 0.05f) hsv[0] else grayHue
    val depth = FreeColor.depthOf(color)
    Column(modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GradientSlider(
                value = hue / 360f,
                colors = FreeColor.spectrum,
                description = tr(Str.ColorHue),
                modifier = Modifier.weight(1f),
            ) {
                val h = it * 359.9f
                grayHue = h
                onColor(FreeColor.fromSliders(h, depth))
            }
            HexField(color, onColor)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GradientSlider(
                value = depth / 100f,
                colors = FreeColor.depthGradient(hue),
                description = tr(Str.ColorDepth),
                modifier = Modifier.weight(1f),
            ) { onColor(FreeColor.fromSliders(hue, it * 100f)) }
            Box(
                Modifier.width(92.dp).height(26.dp).clip(RoundedCornerShape(50)).background(Color(color))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50)),
            )
        }
    }
}

@Composable
private fun GradientSlider(value: Float, colors: List<Color>, description: String, modifier: Modifier, onChange: (Float) -> Unit) {
    val change by rememberUpdatedState(onChange)
    BoxWithConstraints(
        modifier
            .height(30.dp)
            .semantics { contentDescription = description }
            .pointerInput(Unit) { detectTapGestures { change((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) { detectHorizontalDragGestures { c, _ -> change((c.position.x / size.width).coerceIn(0f, 1f)) } },
    ) {
        Box(Modifier.align(Alignment.Center).fillMaxWidth().height(12.dp).clip(RoundedCornerShape(50)).background(Brush.horizontalGradient(colors)))
        val x = (maxWidth - 24.dp) * value.coerceIn(0f, 1f)
        Box(
            Modifier.align(Alignment.CenterStart).offset(x = x).size(24.dp).shadow(3.dp, CircleShape).background(Color.White, CircleShape)
                .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
    }
}

@Composable
private fun HexField(color: Int, onColor: (Int) -> Unit) {
    var text by remember { mutableStateOf(FreeColor.hex(color)) }
    var focused by remember { mutableStateOf(false) }
    // La couleur change ailleurs (pastille, curseur) : le code suit, sauf pendant la saisie.
    LaunchedEffect(color) { if (!focused) text = FreeColor.hex(color) }
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = text,
        onValueChange = { v ->
            text = v.take(7)
            FreeColor.parse(text)?.let(onColor)
        },
        singleLine = true,
        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = colors.onSurface, textAlign = TextAlign.Center),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { text = FreeColor.hex(color) }),
        modifier = Modifier
            .width(92.dp)
            .semantics { contentDescription = tr(Str.ColorHex) }
            .onFocusChanged { focused = it.isFocused; if (!it.isFocused) text = FreeColor.hex(color) }
            .clip(RoundedCornerShape(10.dp))
            .border(1.dp, if (FreeColor.parse(text) == null) colors.error else colors.outline, RoundedCornerShape(10.dp))
            .padding(horizontal = 6.dp, vertical = 6.dp),
    )
}
