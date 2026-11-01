package app.nougat.pdf.engine

import kotlin.math.abs
import kotlin.math.max

/*
 * Modifier le texte deja present dans un PDF.
 *
 * Un PDF ne contient pas de paragraphes, seulement des instructions « dessine ces lettres ici »
 * (operateurs Tj, TJ, ' et " du flux de contenu). Pour changer un texte :
 *  1. la plateforme analyse la page : chaque lettre visible est reliee a l'instruction qui la
 *     dessine (numero d'operateur, numero de chaine, octets) — voir [PageTextAnalysis] ;
 *  2. les lettres sont regroupees en lignes de meme style ([groupTextBlocks]) ;
 *  3. [TextEditPlanner] calcule les nouvelles instructions : les lettres de la ligne sont retirees,
 *     le nouveau texte est insere au meme endroit, et des decalages gardent tout le reste immobile ;
 *  4. la plateforme reecrit le flux de contenu.
 */

/** Repere d'une lettre dans le flux de contenu : operateur, chaine dans l'operateur, lettre dans la chaine. */
data class GlyphKey(val op: Int, val segment: Int, val glyph: Int)

/** Une ligne de texte de la page, modifiable d'un seul bloc. */
data class TextBlock(
    val page: Int,
    val text: String,
    /** Encombrement, en fractions de la page affichee. */
    val rect: NormRect,
    /** Taille du texte, en points. */
    val fontSize: Float,
    /** Faux pour un texte qu'on ne sait pas modifier (dans un formulaire, invisible...). */
    val editable: Boolean,
    val glyphs: List<GlyphKey>,
)

/** Resultat d'une modification de texte. */
enum class TextEditResult {
    /** Le texte a ete reecrit avec la police d'origine. */
    SameFont,

    /** La police embarquee etait incomplete : la meme police, installee sur l'ordinateur, a ete utilisee. */
    InstalledFont,

    /** La police d'origine ne contenait pas ces caracteres : une police standard proche a ete utilisee. */
    SimilarFont,
}

class TextEditException(message: String) : Exception(message)

// ---- Ce que la plateforme fournit ----------------------------------------------------------------

/** Une lettre visible, reliee a l'instruction qui la dessine. */
class AnalyzedGlyph(
    /** Null si la lettre n'est pas dessinee directement par la page (formulaire, motif...). */
    val key: GlyphKey?,
    val unicode: String,
    val box: NormRect,
    /** Taille en points. */
    val size: Float,
    val editable: Boolean,
    /** Police et taille : deux lettres de style different ne vont pas dans la meme ligne. */
    val style: String,
)

/** Un element du tableau d'un operateur de texte : une chaine de lettres ou un decalage. */
sealed interface TJElement {
    class Str(val segment: Int, val bytes: ByteArray, val glyphs: List<SegmentGlyph>) : TJElement
    class Num(val value: Float) : TJElement
}

/** Une lettre d'une chaine : ses octets et son avancee (espace de texte, mise a l'echelle horizontale comprise). */
class SegmentGlyph(val start: Int, val end: Int, val advance: Float)

/** Un operateur d'affichage de texte (Tj, TJ, ' ou ") avec son etat de texte. */
class AnalyzedTextOp(
    val index: Int,
    val name: String,
    /** Nom de la police dans les ressources de la page (pour la remettre apres un changement). */
    val fontResource: String?,
    val fontSize: Float,
    /** Mise a l'echelle horizontale (1 = 100 %). */
    val hScale: Float,
    val charSpacing: Float,
    val wordSpacing: Float,
    /** Pour l'operateur " : espacement des mots et des lettres qu'il fixe. */
    val quoteSpacing: Pair<Float, Float>?,
    val elements: List<TJElement>,
)

/** L'analyse d'une page : les lettres dans l'ordre du flux, et les operateurs de texte. */
class PageTextAnalysis(val glyphs: List<AnalyzedGlyph>, val ops: Map<Int, AnalyzedTextOp>)

// ---- Ce que le calcul produit --------------------------------------------------------------------

/** Le nouveau texte, deja encode. [width] : son avancee totale dans l'espace de texte. */
sealed interface Replacement {
    val width: Float

    /** Avec la police d'origine : [elements] contient des chaines (ByteArray) et des decalages (Float). */
    class SameFont(val elements: List<Any>, override val width: Float) : Replacement

    /** Avec une autre police, ajoutee aux ressources de la page sous le nom [resource]. */
    class OtherFont(val resource: String, val bytes: ByteArray, override val width: Float) : Replacement
}

/** Une instruction a ecrire dans le flux de contenu. */
sealed interface ContentOp {
    /** Operateur TJ : [elements] = chaines (ByteArray) et decalages (Float). */
    data class ShowArray(val elements: List<Any>) : ContentOp

    /** Operateur Tf. */
    data class SetFont(val resource: String, val size: Float) : ContentOp

    /** Autre operateur sans chaine (T*, Tw, Tc). */
    data class Simple(val operator: String, val operands: List<Float>) : ContentOp
}

// ---- Regroupement en lignes ----------------------------------------------------------------------

/** Regroupe les lettres en lignes : meme style, meme ligne de base, sans grand trou. */
fun groupTextBlocks(page: Int, analysis: PageTextAnalysis, pageWidth: Float, pageHeight: Float): List<TextBlock> {
    val blocks = ArrayList<TextBlock>()
    var current = ArrayList<AnalyzedGlyph>()
    fun close() {
        if (current.isNotEmpty()) {
            makeBlock(page, current, pageWidth, pageHeight)?.let(blocks::add)
            current = ArrayList()
        }
    }
    for (g in analysis.glyphs) {
        val prev = current.lastOrNull()
        if (prev != null && !continuesLine(prev, g, pageWidth, pageHeight)) close()
        current.add(g)
    }
    close()
    return blocks
}

private fun continuesLine(a: AnalyzedGlyph, b: AnalyzedGlyph, w: Float, h: Float): Boolean {
    if (a.style != b.style || a.editable != b.editable) return false
    val lineHeight = max((a.box.bottom - a.box.top) * h, (b.box.bottom - b.box.top) * h).coerceAtLeast(1f)
    if (abs(a.box.bottom - b.box.bottom) * h > lineHeight * 0.4f) return false // autre ligne
    val gap = (b.box.left - a.box.right) * w
    if (gap > lineHeight * 1.6f) return false // colonne, tabulation
    if (gap < -lineHeight * 0.8f) return false // retour en arriere
    return true
}

private fun makeBlock(page: Int, glyphs: List<AnalyzedGlyph>, w: Float, h: Float): TextBlock? {
    val text = StringBuilder()
    var prev: AnalyzedGlyph? = null
    for (g in glyphs) {
        if (prev != null) {
            // Beaucoup de PDF n'ont pas de vrais espaces : un ecart suffisant en tient lieu.
            val gap = (g.box.left - prev.box.right) * w
            val lineHeight = (prev.box.bottom - prev.box.top) * h
            if (gap > lineHeight * 0.18f && text.isNotEmpty() && text.last() != ' ') text.append(' ')
        }
        for (c in g.unicode) {
            if (c.isWhitespace()) {
                if (text.isNotEmpty() && text.last() != ' ') text.append(' ')
            } else {
                text.append(c)
            }
        }
        prev = g
    }
    val content = text.toString().trim()
    if (content.isEmpty()) return null
    val keys = glyphs.mapNotNull { it.key }
    return TextBlock(
        page = page,
        text = content,
        rect = NormRect(
            glyphs.minOf { it.box.left }, glyphs.minOf { it.box.top },
            glyphs.maxOf { it.box.right }, glyphs.maxOf { it.box.bottom },
        ),
        fontSize = glyphs.maxOf { it.size },
        editable = glyphs.all { it.editable } && keys.size == glyphs.size,
        glyphs = keys,
    )
}

/** Retrouve, dans une nouvelle analyse de la meme page, la ligne qui correspond a [target]. */
fun List<TextBlock>.findMatch(target: TextBlock): TextBlock? {
    fun distance(a: NormRect, b: NormRect) =
        abs(a.left - b.left) + abs(a.top - b.top) + abs(a.right - b.right) + abs(a.bottom - b.bottom)
    return filter { it.page == target.page }
        .minByOrNull { distance(it.rect, target.rect) + if (it.text == target.text) 0f else 1f }
        ?.takeIf { distance(it.rect, target.rect) < 0.02f }
}

// ---- Choix d'une police de remplacement --------------------------------------------------------

enum class StandardFont {
    Helvetica, HelveticaBold, HelveticaOblique, HelveticaBoldOblique,
    TimesRoman, TimesBold, TimesItalic, TimesBoldItalic,
    Courier, CourierBold, CourierOblique, CourierBoldOblique,
}

/** La police standard la plus proche d'une police, d'apres son nom et ses caracteristiques. */
fun closestStandardFont(
    fontName: String?,
    bold: Boolean = false,
    italic: Boolean = false,
    serif: Boolean = false,
    monospace: Boolean = false,
): StandardFont {
    val name = (fontName ?: "").substringAfter('+').lowercase()
    val isBold = bold || listOf("bold", "black", "heavy", "semibold", "demi").any { it in name }
    val isItalic = italic || "italic" in name || "oblique" in name
    val isMono = monospace || listOf("courier", "mono", "consol", "typewriter").any { it in name }
    val isSerif = !isMono && (serif || listOf("times", "serif", "georgia", "garamond", "cambria", "book", "minion", "palatino")
        .any { it in name } && "sans" !in name)
    return when {
        isMono -> when {
            isBold && isItalic -> StandardFont.CourierBoldOblique
            isBold -> StandardFont.CourierBold
            isItalic -> StandardFont.CourierOblique
            else -> StandardFont.Courier
        }
        isSerif -> when {
            isBold && isItalic -> StandardFont.TimesBoldItalic
            isBold -> StandardFont.TimesBold
            isItalic -> StandardFont.TimesItalic
            else -> StandardFont.TimesRoman
        }
        else -> when {
            isBold && isItalic -> StandardFont.HelveticaBoldOblique
            isBold -> StandardFont.HelveticaBold
            isItalic -> StandardFont.HelveticaOblique
            else -> StandardFont.Helvetica
        }
    }
}

// ---- Calcul des nouvelles instructions -----------------------------------------------------------

object TextEditPlanner {

    /**
     * Les nouvelles instructions de chaque operateur touche (les autres ne changent pas).
     * Les lettres de [block] disparaissent ; [replacement] prend la place de la premiere ;
     * un decalage apres chaque lettre retiree garde la suite exactement au meme endroit.
     */
    fun plan(analysis: PageTextAnalysis, block: TextBlock, replacement: Replacement): Map<Int, List<ContentOp>> {
        val anchor = block.glyphs.firstOrNull() ?: throw TextEditException("Ligne vide")
        val removed = removedGlyphs(analysis, block.glyphs)
        val touched = removed.map { it.op }.distinct().sorted()
        val result = LinkedHashMap<Int, List<ContentOp>>()
        for (index in touched) {
            val op = analysis.ops[index] ?: throw TextEditException("Instruction de texte introuvable")
            result[index] = rewriteOp(op, removed, anchor, replacement)
        }
        return result
    }

    /**
     * Les lettres a retirer : celles de la ligne, plus celles qui se trouveraient entre deux d'entre
     * elles dans une meme chaine (lettres sans equivalent Unicode, donc invisibles pour l'analyse).
     */
    private fun removedGlyphs(analysis: PageTextAnalysis, glyphs: List<GlyphKey>): Set<GlyphKey> {
        val result = HashSet<GlyphKey>(glyphs)
        for ((segmentKey, keys) in glyphs.groupBy { it.op to it.segment }) {
            val first = keys.minOf { it.glyph }
            val last = keys.maxOf { it.glyph }
            for (i in first..last) result.add(GlyphKey(segmentKey.first, segmentKey.second, i))
        }
        return result
    }

    private fun rewriteOp(op: AnalyzedTextOp, removed: Set<GlyphKey>, anchor: GlyphKey, replacement: Replacement): List<ContentOp> {
        val out = ArrayList<ContentOp>()
        when (op.name) {
            "'" -> out += ContentOp.Simple("T*", emptyList())
            "\"" -> {
                val (aw, ac) = op.quoteSpacing ?: (0f to 0f)
                out += ContentOp.Simple("Tw", listOf(aw))
                out += ContentOp.Simple("Tc", listOf(ac))
                out += ContentOp.Simple("T*", emptyList())
            }
        }
        val scale = op.fontSize * op.hScale
        if (scale == 0f) throw TextEditException("Taille de texte nulle")

        var current = ArrayList<Any>()
        val kept = ByteBuffer()
        var pending = 0f // avancee retiree pas encore compensee
        var lastRemoved = false

        fun flushKept() {
            if (kept.size > 0) current.add(kept.take())
        }
        fun flushPending() {
            if (pending != 0f) {
                flushKept()
                current.add(-pending * 1000f / scale)
                pending = 0f
            }
        }

        for (element in op.elements) {
            when (element) {
                is TJElement.Num -> {
                    if (lastRemoved) {
                        pending += -element.value / 1000f * scale
                    } else {
                        flushKept()
                        current.add(element.value)
                    }
                }
                is TJElement.Str -> {
                    element.glyphs.forEachIndexed { i, g ->
                        val key = GlyphKey(op.index, element.segment, i)
                        if (key in removed) {
                            flushKept()
                            if (key == anchor) {
                                when (replacement) {
                                    is Replacement.SameFont -> current.addAll(replacement.elements)
                                    is Replacement.OtherFont -> {
                                        val original = op.fontResource
                                            ?: throw TextEditException("Police d'origine introuvable")
                                        if (current.isNotEmpty()) out += ContentOp.ShowArray(current)
                                        current = ArrayList()
                                        out += ContentOp.SetFont(replacement.resource, op.fontSize)
                                        out += ContentOp.ShowArray(listOf(replacement.bytes))
                                        out += ContentOp.SetFont(original, op.fontSize)
                                    }
                                }
                                pending -= replacement.width
                            }
                            pending += g.advance
                            lastRemoved = true
                        } else {
                            flushPending()
                            kept.add(element.bytes, g.start, g.end)
                            lastRemoved = false
                        }
                    }
                    flushKept()
                }
            }
        }
        flushPending()
        if (current.isNotEmpty()) out += ContentOp.ShowArray(current)
        return out
    }
}

/** Petit tampon d'octets (le code commun n'a pas ByteArrayOutputStream). */
private class ByteBuffer {
    private var data = ByteArray(32)
    var size = 0
        private set

    fun add(src: ByteArray, from: Int, to: Int) {
        val n = to - from
        if (size + n > data.size) data = data.copyOf(max(data.size * 2, size + n))
        src.copyInto(data, size, from, to)
        size += n
    }

    fun take(): ByteArray {
        val result = data.copyOf(size)
        size = 0
        return result
    }
}

/** Decoupe un texte en espaces et en mots (pour encoder les espaces en decalages). */
fun splitWordsAndSpaces(text: String): List<String> {
    val parts = ArrayList<String>()
    val sb = StringBuilder()
    for (c in text) {
        if (c == ' ') {
            if (sb.isNotEmpty()) { parts.add(sb.toString()); sb.clear() }
            parts.add(" ")
        } else sb.append(c)
    }
    if (sb.isNotEmpty()) parts.add(sb.toString())
    return parts
}
