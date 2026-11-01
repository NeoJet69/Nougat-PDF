package app.nougat.pdf.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.nougat.pdf.engine.NPoint

enum class Tool(val label: String) {
    Pan("Naviguer"),
    Pen("Stylo"),
    Highlighter("Surligneur"),
    Text("Texte"),
    Whiteout("Masquer"),
    Signature("Signature"),
    Eraser("Gomme"),
}

/** Les couleurs proposees, en ARGB. */
object InkColors {
    const val Black = 0xFF1A1A1A.toInt()
    const val Blue = 0xFF1E5BD8.toInt()
    const val Red = 0xFFD32F2F.toInt()
    const val Green = 0xFF2E7D32.toInt()
    const val Orange = 0xFFCC542C.toInt()
    const val Yellow = 0xFFFFE14D.toInt()
    const val Pink = 0xFFFF7EB6.toInt()
    const val White = 0xFFFFFFFF.toInt()

    val pen = listOf(Black, Blue, Red, Green, Orange)
    val highlighter = listOf(Yellow, Green or 0x00408040, Pink, 0xFF6EC6FF.toInt())
}

/** Une signature dessinee : ses traits en fractions de son cadre, et le rapport largeur/hauteur du cadre. */
data class Signature(val strokes: List<List<NPoint>>, val aspectRatio: Float)

/** L'outil choisi et ses reglages. */
class EditTools {
    /** Mode edition affiche (barre d'outils visible). */
    var editing by mutableStateOf(false)
    var tool by mutableStateOf(Tool.Pen)
    var penColor by mutableIntStateOf(InkColors.Blue)
    var highlighterColor by mutableIntStateOf(InkColors.Yellow)
    var penWidth by mutableStateOf(2f)
    var textSize by mutableStateOf(14f)
    var signature: Signature? by mutableStateOf(null)

    val color: Int get() = if (tool == Tool.Highlighter) highlighterColor else penColor

    fun setColor(argb: Int) {
        if (tool == Tool.Highlighter) highlighterColor = argb else penColor = argb
    }
}
