package app.nougat.pdf.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** La toile de fond : degrade chaud et deux halos, au lieu d'un gris plat. */
fun Modifier.nougatCanvas(p: NougatPalette): Modifier = this
    .background(Brush.verticalGradient(listOf(p.canvasTop, p.canvasBottom)))
    .drawBehind {
        drawCircle(
            Brush.radialGradient(listOf(p.glow, Color.Transparent), center = Offset(size.width * 0.12f, size.height * 0.02f), radius = size.maxDimension * 0.55f),
            radius = size.maxDimension * 0.55f,
            center = Offset(size.width * 0.12f, size.height * 0.02f),
        )
        drawCircle(
            Brush.radialGradient(listOf(p.glow2, Color.Transparent), center = Offset(size.width * 0.95f, size.height * 0.9f), radius = size.maxDimension * 0.5f),
            radius = size.maxDimension * 0.5f,
            center = Offset(size.width * 0.95f, size.height * 0.9f),
        )
    }

/** Un ilot flottant : fond translucide, bord fin, ombre douce. */
@Composable
fun Island(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(26.dp),
    padding: Dp = 6.dp,
    /** Contenu trop large (telephone) : il defile a l'interieur de l'ilot. */
    scrollable: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val p = LocalNougatPalette.current
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Row(
            modifier
                .shadow(20.dp, shape, ambientColor = p.shadow, spotColor = p.shadow)
                .clip(shape)
                .background(p.island)
                .border(1.dp, p.islandBorder, shape)
                .then(if (scrollable) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
                .padding(padding),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** Grand bouton rond pour les ilots (cible de 48 dp, icone de 24 dp). */
@Composable
fun IslandButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified,
    size: Dp = 48.dp,
    iconSize: Dp = 24.dp,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (active) colors.primaryContainer else Color.Transparent)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val color = when {
            !enabled -> colors.onSurface.copy(alpha = 0.3f)
            tint != Color.Unspecified -> tint
            active -> colors.onPrimaryContainer
            else -> colors.onSurface
        }
        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(iconSize))
    }
}

/** Bouton principal : pilule au degrade orange. */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    height: Dp = 52.dp,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .height(height)
            .shadow(if (enabled) 14.dp else 0.dp, shape, ambientColor = NougatColors.Orange, spotColor = NougatColors.OrangeBrand)
            .clip(shape)
            .background(if (enabled) NougatGradient else Brush.linearGradient(listOf(Color.Gray, Color.Gray)))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}

/** Petite pastille ronde au degrade, avec une icone blanche. */
@Composable
fun GradientBadge(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 36.dp, iconSize: Dp = 20.dp) {
    Box(
        modifier.size(size).clip(CircleShape).background(NougatGradient),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(iconSize))
    }
}

/** Element du dock : grande icone + libelle ; l'element actif s'allume en degrade. */
@Composable
fun DockItem(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(20.dp)
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .clip(shape)
            .then(if (active) Modifier.background(NougatGradient) else Modifier)
            .clickable(role = Role.Tab, onClickLabel = label, onClick = onClick)
            .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        val content = if (active) Color.White else colors.onSurface
        CompositionLocalProvider(LocalContentColor provides content) {
            androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(if (compact) 24.dp else 26.dp))
                Spacer(Modifier.height(3.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (active) FontWeight.Bold else FontWeight.Medium),
                    maxLines = 1,
                )
            }
        }
    }
}

/** Bordure en pointilles qui defilent (« fourmis »), pour la zone de depot. */
@Composable
fun Modifier.marchingAnts(color: Color, radius: Dp = 28.dp): Modifier {
    val transition = rememberInfiniteTransition()
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 40f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
    )
    return this.drawBehind {
        val stroke = 2.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius(radius.toPx()),
            style = Stroke(width = stroke, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), phase)),
        )
    }
}

/** Nougat le chat, vivant : il flotte doucement et cligne des yeux. */
@Composable
fun NougatMascot(modifier: Modifier = Modifier, animated: Boolean = true) {
    val transition = rememberInfiniteTransition()
    val float by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600), RepeatMode.Reverse),
    )
    val eyes by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 4200
                1f at 0
                1f at 3800
                0.1f at 3900
                1f at 4050
            },
        ),
    )
    Canvas(modifier) {
        val dy = if (animated) float * size.height * 0.025f else 0f
        val open = if (animated) eyes else 1f
        withTransform({ translate(top = dy) }) { drawCat(open) }
    }
}

/** Le chat, dans une boite de 108 x 108 mise a l'echelle. */
private fun DrawScope.drawCat(eyesOpen: Float) {
    val k = size.minDimension / 108f
    fun o(x: Float, y: Float) = Offset(x * k, y * k)
    fun tri(a: Offset, b: Offset, c: Offset) = Path().apply { moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); close() }
    fun oval(cx: Float, cy: Float, rx: Float, ry: Float, color: Color) =
        drawOval(color, topLeft = o(cx - rx, cy - ry), size = Size(2 * rx * k, 2 * ry * k))

    // Ombre au sol
    drawOval(Color.Black.copy(alpha = 0.10f), topLeft = o(26f, 96f), size = Size(56f * k, 8f * k))
    drawPath(tri(o(20f, 52f), o(26f, 12f), o(52f, 34f)), NougatColors.CaramelDark)
    drawPath(tri(o(88f, 52f), o(82f, 12f), o(56f, 34f)), NougatColors.CaramelDark)
    drawPath(tri(o(28f, 44f), o(31f, 22f), o(45f, 34f)), NougatColors.Pink)
    drawPath(tri(o(80f, 44f), o(77f, 22f), o(63f, 34f)), NougatColors.Pink)
    oval(54f, 62f, 38f, 32f, NougatColors.Caramel)
    oval(54f, 76f, 18f, 13f, NougatColors.Cream)
    // Yeux (ils se ferment quand le chat cligne)
    val ry = 6.5f * eyesOpen.coerceIn(0.08f, 1f)
    oval(40f, 60f, 5f, ry, NougatColors.Cocoa)
    oval(68f, 60f, 5f, ry, NougatColors.Cocoa)
    if (eyesOpen > 0.5f) {
        oval(41.6f, 57.8f, 1.6f, 1.6f, Color.White)
        oval(69.6f, 57.8f, 1.6f, 1.6f, Color.White)
    }
    drawPath(tri(o(49f, 70f), o(59f, 70f), o(54f, 76f)), NougatColors.Pink)
    val stroke = Stroke(width = 2f * k, cap = StrokeCap.Round)
    val mouth = Path().apply {
        moveTo(54f * k, 76f * k); lineTo(54f * k, 80f * k)
        moveTo(54f * k, 80f * k); cubicTo(51f * k, 84f * k, 47f * k, 83f * k, 46f * k, 80f * k)
        moveTo(54f * k, 80f * k); cubicTo(57f * k, 84f * k, 61f * k, 83f * k, 62f * k, 80f * k)
        moveTo(36f * k, 74f * k); lineTo(14f * k, 70f * k)
        moveTo(36f * k, 78f * k); lineTo(15f * k, 80f * k)
        moveTo(72f * k, 74f * k); lineTo(94f * k, 70f * k)
        moveTo(72f * k, 78f * k); lineTo(93f * k, 80f * k)
    }
    drawPath(mouth, NougatColors.Cocoa, style = stroke)
}
