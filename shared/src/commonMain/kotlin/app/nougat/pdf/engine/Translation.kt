package app.nougat.pdf.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * Traduire un document en gardant sa mise en page (comme DeepL ou Google Traduction pour les
 * documents). On ne traduit pas ligne par ligne, ce qui donnerait des phrases coupees : les lignes
 * de la page sont d'abord regroupees en paragraphes ([Paragraphs]). Chaque paragraphe traduit est
 * ensuite pose a la place de l'original, dans le meme cadre, un peu plus petit s'il est plus long
 * ([TextFitter]). L'original n'est jamais modifie : la traduction s'affiche par-dessus la page,
 * ou s'enregistre dans une copie.
 */

/** Une langue proposee : code (celui de ML Kit, BCP-47), nom dans la langue, nom anglais (pour Claude). */
data class TranslationLanguage(
    val code: String,
    val nativeName: String,
    val englishName: String,
    /** Ecriture de droite a gauche. */
    val rtl: Boolean = false,
)

/** Les langues du traducteur : celles de Nougat, puis d'autres langues courantes en Europe et en Asie. */
val TRANSLATION_LANGUAGES: List<TranslationLanguage> = listOf(
    TranslationLanguage("fr", "Français", "French"),
    TranslationLanguage("en", "English", "English"),
    TranslationLanguage("de", "Deutsch", "German"),
    TranslationLanguage("it", "Italiano", "Italian"),
    TranslationLanguage("es", "Español", "Spanish"),
    TranslationLanguage("pt", "Português", "Portuguese"),
    TranslationLanguage("nl", "Nederlands", "Dutch"),
    TranslationLanguage("pl", "Polski", "Polish"),
    TranslationLanguage("tr", "Türkçe", "Turkish"),
    TranslationLanguage("ru", "Русский", "Russian"),
    TranslationLanguage("uk", "Українська", "Ukrainian"),
    TranslationLanguage("ar", "العربية", "Arabic", rtl = true),
    TranslationLanguage("zh", "中文", "Simplified Chinese"),
    TranslationLanguage("sv", "Svenska", "Swedish"),
    TranslationLanguage("el", "Ελληνικά", "Greek"),
    TranslationLanguage("ro", "Română", "Romanian"),
    TranslationLanguage("ja", "日本語", "Japanese"),
    TranslationLanguage("ko", "한국어", "Korean"),
    TranslationLanguage("cs", "Čeština", "Czech"),
    TranslationLanguage("sk", "Slovenčina", "Slovak"),
    TranslationLanguage("hu", "Magyar", "Hungarian"),
    TranslationLanguage("hr", "Hrvatski", "Croatian"),
    TranslationLanguage("sl", "Slovenščina", "Slovenian"),
    TranslationLanguage("bg", "Български", "Bulgarian"),
    TranslationLanguage("sq", "Shqip", "Albanian"),
    TranslationLanguage("da", "Dansk", "Danish"),
    TranslationLanguage("no", "Norsk", "Norwegian"),
    TranslationLanguage("fi", "Suomi", "Finnish"),
    TranslationLanguage("ca", "Català", "Catalan"),
    TranslationLanguage("vi", "Tiếng Việt", "Vietnamese"),
    TranslationLanguage("id", "Bahasa Indonesia", "Indonesian"),
)

fun translationLanguage(code: String?): TranslationLanguage? = TRANSLATION_LANGUAGES.firstOrNull { it.code == code }

/** La langue vers laquelle traduire par defaut : celle de l'app (le dialecte prend sa langue). */
fun defaultTranslationTarget(appLanguage: String): String = when (appLanguage) {
    "fr-CA", "oc", "br", "co", "miaou" -> "fr"
    "gsw", "als", "rm" -> "de"
    else -> appLanguage.takeIf { translationLanguage(it) != null } ?: "en"
}

// ---- Paragraphes -----------------------------------------------------------------------------------

enum class ParagraphAlign { Start, Center, End }

/** Un paragraphe de la page : les lignes qu'il regroupe, son cadre et son texte. */
class TextParagraph(
    val page: Int,
    /** Les lignes d'origine (pour les retrouver et les retirer en ecrivant la traduction). */
    val blocks: List<TextBlock>,
    /** Le cadre de chaque ligne visible, dans l'ordre. */
    val lines: List<NormRect>,
    /** Le cadre du paragraphe, en fractions de la page affichee. */
    val rect: NormRect,
    val text: String,
    /** Taille du texte, en points. */
    val fontSize: Float,
    /** Ecart entre deux lignes de base, en multiple de [fontSize] (1,2 pour une seule ligne). */
    val lineSpacing: Float,
    val fontName: String?,
    val scanned: Boolean,
    val align: ParagraphAlign,
    /** Jusqu'ou une traduction plus longue peut deborder a droite (fraction de la page). */
    val maxRight: Float = rect.right,
) {
    /**
     * Largeur disponible pour la traduction (fraction de la page) : celle du paragraphe, un peu plus
     * pour une ligne seule (« Facture » -> « Rechnung ») s'il n'y a rien juste a cote.
     */
    val layoutWidth: Float
        get() {
            val width = rect.right - rect.left
            return if (lines.size == 1) max(width, min(maxRight - rect.left, width * 1.4f)) else width
        }

    companion object {
        /** Une ligne seule, vue comme un paragraphe (pour l'effacer). */
        fun of(block: TextBlock) = TextParagraph(
            block.page, listOf(block), listOf(block.rect), block.rect, block.text, block.fontSize, 1.2f,
            block.fontName, block.scanned, ParagraphAlign.Start,
        )
    }

    val bold: Boolean get() = fontName.orEmpty().lowercase().let { n -> listOf("bold", "black", "heavy", "semibold", "demi").any { it in n } }
    val italic: Boolean get() = fontName.orEmpty().lowercase().let { "italic" in it || "oblique" in it }
    val standardFont: StandardFont get() = closestStandardFont(fontName)
}

/** Un paragraphe et sa traduction. */
class TranslatedParagraph(val paragraph: TextParagraph, val text: String)

object Paragraphs {
    private val LIST_START = Regex("^([•●▪◦■□➢►✓✔\\-–—*]|\\(?\\d{1,3}[.)]|\\(?[a-zA-Z][.)])\\s")
    /** « Client : », « Date: », « N° de TVA : » : une ligne d'un formulaire ou d'une adresse. */
    private val LABEL_START = Regex("^[\\p{L}][\\p{L}°'’. -]{0,24}\\s?:\\s")
    private val URL_ONLY = Regex("^(https?://|www\\.)\\S+$|^\\S+@\\S+\\.\\S+$", RegexOption.IGNORE_CASE)

    /**
     * Regroupe les lignes [blocks] d'une page de [pageWidth] x [pageHeight] points en paragraphes.
     * Une ligne coupee par un changement de style (un mot en gras) est d'abord recollee.
     */
    fun group(page: Int, blocks: List<TextBlock>, pageWidth: Float, pageHeight: Float): List<TextParagraph> {
        val lines = visualLines(blocks, pageWidth, pageHeight)
        val result = ArrayList<TextParagraph>()
        var current = ArrayList<VisualLine>()
        fun close() {
            if (current.isNotEmpty()) result.add(paragraphOf(page, current, pageWidth, pageHeight))
            current = ArrayList()
        }
        for (line in lines) {
            if (current.isNotEmpty() && !continuesParagraph(current, line, pageWidth, pageHeight)) close()
            current.add(line)
        }
        close()
        // La place libre a droite de chaque paragraphe : jusqu'au texte suivant sur les memes lignes,
        // sinon jusqu'a la marge de droite (aussi large que celle de gauche).
        val margin = (1f - (blocks.maxOfOrNull { it.rect.right } ?: 1f)).coerceAtLeast(blocks.minOfOrNull { it.rect.left } ?: 0f)
        return result.map { p ->
            val gap = p.fontSize * 0.6f / pageWidth
            val neighbour = blocks.filter { b ->
                b !in p.blocks && b.rect.top < p.rect.bottom && b.rect.bottom > p.rect.top && b.rect.left >= p.rect.right - gap / 2
            }.minOfOrNull { it.rect.left - gap }
            val limit = (neighbour ?: (1f - margin)).coerceAtLeast(p.rect.right)
            TextParagraph(p.page, p.blocks, p.lines, p.rect, p.text, p.fontSize, p.lineSpacing, p.fontName, p.scanned, p.align, limit)
        }
    }

    /** Vrai si le texte contient quelque chose a traduire (pas seulement des chiffres, une adresse web...). */
    fun needsTranslation(text: String): Boolean {
        val t = text.trim()
        val letters = t.count { it.isLetter() }
        if (letters < 2) return false
        // Un sigle seul (CHF, TVA, ID) reste tel quel.
        if (letters <= 3 && t.none { it.isLowerCase() }) return false
        return !URL_ONLY.matches(t)
    }

    private class VisualLine(val blocks: MutableList<TextBlock>, var rect: NormRect, val text: StringBuilder, var size: Float, val scanned: Boolean) {
        val fontName: String? get() = blocks.maxByOrNull { it.text.length }?.fontName
    }

    private fun visualLines(blocks: List<TextBlock>, w: Float, h: Float): List<VisualLine> {
        val lines = ArrayList<VisualLine>()
        for (b in blocks) {
            val last = lines.lastOrNull()
            if (last != null && last.scanned == b.scanned) {
                val lh = max(last.rect.bottom - last.rect.top, b.rect.bottom - b.rect.top) * h
                val gap = (b.rect.left - last.rect.right) * w
                if (abs(b.rect.bottom - last.rect.bottom) * h <= lh * 0.35f && gap >= -lh * 0.3f && gap <= lh * 1.0f) {
                    if (gap > lh * 0.12f && last.text.isNotEmpty() && last.text.last() != ' ' && !b.text.startsWith(" ")) last.text.append(' ')
                    last.text.append(b.text)
                    last.blocks.add(b)
                    last.rect = union(last.rect, b.rect)
                    last.size = max(last.size, b.fontSize)
                    continue
                }
            }
            lines.add(VisualLine(mutableListOf(b), b.rect, StringBuilder(b.text), b.fontSize, b.scanned))
        }
        return lines
    }

    private fun continuesParagraph(para: List<VisualLine>, b: VisualLine, w: Float, h: Float): Boolean {
        val a = para.last()
        if (a.scanned != b.scanned) return false
        if (max(a.size, b.size) > min(a.size, b.size) * 1.25f) return false
        val s = max(a.size, b.size).coerceAtLeast(1f)
        // Ligne suivante, juste dessous.
        val pitch = (b.rect.bottom - a.rect.bottom) * h
        if (pitch < s * 0.6f || pitch > s * 1.8f) return false
        if (para.size >= 2) {
            val established = (a.rect.bottom - para[para.size - 2].rect.bottom) * h
            if (pitch > established * 1.35f + 0.5f) return false
        }
        // Meme colonne.
        if (b.rect.left >= a.rect.right || b.rect.right <= a.rect.left) return false
        val left = para.minOf { it.rect.left }
        val right = para.maxOf { it.rect.right }
        val alignedLeft = abs(b.rect.left - left) * w <= s * 3f
        val centered = abs((b.rect.left + b.rect.right) - (a.rect.left + a.rect.right)) / 2f * w <= s
        val alignedRight = abs(b.rect.right - a.rect.right) * w <= s * 0.6f
        if (!alignedLeft && !centered && !alignedRight) return false
        // Une puce, un numero ou une etiquette (« Date : ») commence un nouveau paragraphe.
        val next = b.text.toString()
        if (LIST_START.containsMatchIn(next) || LABEL_START.containsMatchIn(next)) return false
        // La ligne d'avant s'arrete bien avant la suivante, qui commence par une majuscule : retour a
        // la ligne voulu (adresse, liste sans puce), pas un paragraphe qui continue.
        val prevText = a.text.toString().trimEnd()
        val startsUpper = next.firstOrNull()?.let { it.isUpperCase() || it.isDigit() } == true
        if (startsUpper && (b.rect.right - a.rect.right) * w > s * 4f && prevText.lastOrNull() !in listOf(',', '-', null)) return false
        // La ligne d'avant finit une phrase bien avant le bord : c'etait la fin du paragraphe.
        val prev = a.text.toString().trimEnd()
        if (prev.isNotEmpty() && prev.last() in ".!?:;" && (right - a.rect.right) * w > s * 4f && !centered) return false
        return true
    }

    private fun paragraphOf(page: Int, lines: List<VisualLine>, w: Float, h: Float): TextParagraph {
        val text = StringBuilder()
        for (line in lines) {
            val t = line.text.toString().trim()
            if (text.isEmpty()) {
                text.append(t)
                continue
            }
            val end = text.last()
            val start = t.firstOrNull() ?: continue
            when {
                // Mot coupe en fin de ligne : « traduc- » + « tion ».
                end == '-' && text.length >= 2 && text[text.length - 2].isLetter() && start.isLowerCase() -> {
                    text.setLength(text.length - 1)
                    text.append(t)
                }
                isCjk(end) && isCjk(start) -> text.append(t)
                else -> text.append(' ').append(t)
            }
        }
        val sizes = lines.map { it.size }.sorted()
        val size = sizes[sizes.size / 2]
        val spacing = if (lines.size >= 2) {
            val pitches = lines.zipWithNext { a, b -> (b.rect.bottom - a.rect.bottom) * h }.sorted()
            (pitches[pitches.size / 2] / size.coerceAtLeast(1f)).coerceIn(1.0f, 2.0f)
        } else 1.2f
        val lefts = lines.map { it.rect.left }
        val rights = lines.map { it.rect.right }
        val centers = lines.map { (it.rect.left + it.rect.right) / 2f }
        val align = when {
            lines.size < 2 -> ParagraphAlign.Start
            (lefts.max() - lefts.min()) * w <= size * 1.5f -> ParagraphAlign.Start
            (centers.max() - centers.min()) * w <= size -> ParagraphAlign.Center
            (rights.max() - rights.min()) * w <= size * 0.6f -> ParagraphAlign.End
            else -> ParagraphAlign.Start
        }
        var rect = lines.first().rect
        for (l in lines) rect = union(rect, l.rect)
        val fontName = lines.flatMap { it.blocks }.maxByOrNull { it.text.length }?.fontName
        return TextParagraph(
            page = page,
            blocks = lines.flatMap { it.blocks },
            lines = lines.map { it.rect },
            rect = rect,
            text = text.toString(),
            fontSize = size,
            lineSpacing = spacing,
            fontName = fontName,
            scanned = lines.all { it.scanned },
            align = align,
        )
    }

    private fun union(a: NormRect, b: NormRect) =
        NormRect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))
}

/** Ideogramme chinois ou japonais, kana : ces ecritures n'ont pas d'espace entre les mots. */
fun isCjk(c: Char): Boolean {
    val code = c.code
    return code in 0x3040..0x30FF || code in 0x3400..0x4DBF || code in 0x4E00..0x9FFF ||
        code in 0xF900..0xFAFF || code in 0x3000..0x303F || code in 0xFF00..0xFFEF
}

// ---- Mise en page du texte traduit -----------------------------------------------------------------

/** Le texte coupe en lignes, a la taille [size] (points), une ligne tous les [lineHeight] points. */
class FittedText(val lines: List<String>, val size: Float, val lineHeight: Float)

object TextFitter {
    /** Ponctuation qui ne commence jamais une ligne en chinois et en japonais. */
    private const val CJK_CLOSING = "、。，．！？：；）」』】〕〉》’”・ー…"

    /**
     * La plus grande taille (au plus [maxSize], au moins [minSize]) a laquelle [text] tient dans
     * [width] x [height] points. [measure] : largeur d'un texte a une taille donnee.
     */
    fun fit(
        text: String,
        width: Float,
        height: Float,
        maxSize: Float,
        minSize: Float,
        lineSpacing: Float,
        measure: (String, Float) -> Float,
    ): FittedText {
        var size = maxSize
        while (true) {
            val (lines, brokenWord) = wrapLines(text, width) { measure(it, size) }
            val needed = size * (1f + (lines.size - 1) * lineSpacing)
            // Un mot coupe au milieu : on prefere ecrire plus petit, tant que c'est possible.
            if ((needed <= height * 1.02f && !brokenWord) || size <= minSize) return FittedText(lines, size, size * lineSpacing)
            size = max(minSize, size * 0.94f)
        }
    }

    /** Coupe [text] en lignes d'au plus [width] (mesurees par [measure]), entre les mots. */
    fun wrap(text: String, width: Float, measure: (String) -> Float): List<String> = wrapLines(text, width, measure).first

    /** Les lignes, et vrai si un mot trop long a du etre coupe. */
    private fun wrapLines(text: String, width: Float, measure: (String) -> Float): Pair<List<String>, Boolean> {
        var broken = false
        val lines = ArrayList<String>()
        var line = ""
        for ((token, spaced) in tokens(text)) {
            val candidate = if (line.isEmpty()) token else line + (if (spaced) " " else "") + token
            if (measure(candidate) <= width) {
                line = candidate
                continue
            }
            if (line.isNotEmpty()) lines.add(line)
            line = ""
            if (measure(token) <= width) {
                line = token
                continue
            }
            // Mot plus long que la ligne : coupe la ou il faut.
            broken = true
            var piece = ""
            for (c in token) {
                if (piece.isNotEmpty() && measure(piece + c) > width) {
                    lines.add(piece)
                    piece = ""
                }
                piece += c
            }
            line = piece
        }
        if (line.isNotEmpty()) lines.add(line)
        return lines.ifEmpty { listOf("") } to broken
    }

    /** Les mots (et les ideogrammes, un par un), chacun avec « precede d'un espace ». */
    private fun tokens(text: String): List<Pair<String, Boolean>> {
        val result = ArrayList<Pair<String, Boolean>>()
        val word = StringBuilder()
        var spaceBefore = false
        fun flush() {
            if (word.isNotEmpty()) {
                result.add(word.toString() to spaceBefore)
                word.clear()
                spaceBefore = false
            }
        }
        for (c in text) {
            when {
                c.isWhitespace() -> {
                    flush()
                    if (result.isNotEmpty()) spaceBefore = true
                }
                c in CJK_CLOSING && word.isEmpty() && result.isNotEmpty() && !spaceBefore -> {
                    val (last, sp) = result.removeAt(result.lastIndex)
                    result.add(last + c to sp)
                }
                isCjk(c) && c !in CJK_CLOSING -> {
                    flush()
                    result.add(c.toString() to spaceBefore)
                    spaceBefore = false
                }
                else -> word.append(c)
            }
        }
        flush()
        return result
    }
}

// ---- Couleurs ----------------------------------------------------------------------------------------

object PageColors {
    /**
     * Couleur du papier autour du rectangle [left, top, right, bottom] (pixels) d'une image ARGB, et
     * couleur de l'encre a l'interieur. Renvoie (papier, encre), en RGB opaque.
     */
    fun sample(pixels: IntArray, width: Int, height: Int, left: Int, top: Int, right: Int, bottom: Int): Pair<Int, Int> {
        val l = left.coerceIn(0, width - 1)
        val t = top.coerceIn(0, height - 1)
        val r = right.coerceIn(l + 1, width)
        val b = bottom.coerceIn(t + 1, height)
        val margin = max(2, (b - t) / 6)
        val ring = ArrayList<Int>()
        for (y in max(0, t - margin) until min(height, b + margin)) {
            for (x in max(0, l - margin) until min(width, r + margin)) {
                if (y in t until b && x in l until r) continue
                ring.add(pixels[y * width + x])
            }
        }
        // Rectangle collant au bord de l'image : on prend les pixels clairs de l'interieur.
        val inside = ArrayList<Int>()
        val step = if ((r - l) * (b - t) > 60_000) 2 else 1
        for (y in t until b step step) for (x in l until r step step) inside.add(pixels[y * width + x])
        val paper = median(ring.ifEmpty { inside.sortedByDescending { luminance(it) }.take(max(1, inside.size / 2)) })
        val distances = inside.map { it to distance(it, paper) }.filter { it.second > 60 }
        val ink = if (distances.size < 4) {
            if (luminance(paper) > 140) 0x202020 else 0xF0F0F0
        } else {
            median(distances.sortedByDescending { it.second }.take(max(3, distances.size / 4)).map { it.first })
        }
        return (paper and 0xFFFFFF) to (ink and 0xFFFFFF)
    }

    fun luminance(rgb: Int): Int = ((rgb shr 16 and 0xFF) * 299 + (rgb shr 8 and 0xFF) * 587 + (rgb and 0xFF) * 114) / 1000

    private fun distance(a: Int, b: Int): Int =
        abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) + abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) + abs((a and 0xFF) - (b and 0xFF))

    private fun median(colors: List<Int>): Int {
        if (colors.isEmpty()) return 0xFFFFFF
        fun channel(shift: Int) = colors.map { it shr shift and 0xFF }.sorted()[colors.size / 2]
        return (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}

// ---- Ecriture arabe ------------------------------------------------------------------------------

/**
 * Lettres arabes liees : dans un PDF, chaque lettre doit etre ecrite sous sa forme (isolee, finale,
 * initiale, mediane), car le lecteur de PDF ne les relie pas lui-meme. On utilise les formes de
 * presentation d'Unicode (U+FE70 a U+FEFF), presentes dans les polices courantes (Arial, Tahoma...).
 */
object ArabicShaping {
    // Lettre -> formes isolee, finale, initiale, mediane (0 : la lettre ne se lie pas a la suivante).
    private val FORMS: Map<Char, IntArray> = buildMap {
        fun r(c: Int, iso: Int) = put(c.toChar(), intArrayOf(iso, iso + 1, 0, 0))
        fun d(c: Int, iso: Int) = put(c.toChar(), intArrayOf(iso, iso + 1, iso + 2, iso + 3))
        put('ء', intArrayOf(0xFE80, 0xFE80, 0, 0))
        r(0x0622, 0xFE81); r(0x0623, 0xFE83); r(0x0624, 0xFE85); r(0x0625, 0xFE87)
        d(0x0626, 0xFE89); r(0x0627, 0xFE8D); d(0x0628, 0xFE8F); r(0x0629, 0xFE93)
        d(0x062A, 0xFE95); d(0x062B, 0xFE99); d(0x062C, 0xFE9D); d(0x062D, 0xFEA1)
        d(0x062E, 0xFEA5); r(0x062F, 0xFEA9); r(0x0630, 0xFEAB); r(0x0631, 0xFEAD)
        r(0x0632, 0xFEAF); d(0x0633, 0xFEB1); d(0x0634, 0xFEB5); d(0x0635, 0xFEB9)
        d(0x0636, 0xFEBD); d(0x0637, 0xFEC1); d(0x0638, 0xFEC5); d(0x0639, 0xFEC9)
        d(0x063A, 0xFECD); d(0x0641, 0xFED1); d(0x0642, 0xFED5); d(0x0643, 0xFED9)
        d(0x0644, 0xFEDD); d(0x0645, 0xFEE1); d(0x0646, 0xFEE5); d(0x0647, 0xFEE9)
        r(0x0648, 0xFEED); r(0x0649, 0xFEEF); d(0x064A, 0xFEF1)
        // Persan et ourdou.
        put('پ', intArrayOf(0xFB56, 0xFB57, 0xFB58, 0xFB59)); put('چ', intArrayOf(0xFB7A, 0xFB7B, 0xFB7C, 0xFB7D))
        put('ژ', intArrayOf(0xFB8A, 0xFB8B, 0, 0)); put('ک', intArrayOf(0xFB8E, 0xFB8F, 0xFB90, 0xFB91))
        put('گ', intArrayOf(0xFB92, 0xFB93, 0xFB94, 0xFB95)); put('ی', intArrayOf(0xFBFC, 0xFBFD, 0xFBFE, 0xFBFF))
    }

    /** Lam + alef : une seule forme (isolee, finale). */
    private val LAM_ALEF = mapOf('آ' to 0xFEF5, 'أ' to 0xFEF7, 'إ' to 0xFEF9, 'ا' to 0xFEFB)
    private const val LAM = 'ل'
    private const val TATWEEL = 'ـ'

    /** Voyelles et signes poses sur les lettres : ils ne coupent pas la liaison. */
    private fun transparent(c: Char) = c.code in 0x064B..0x065F || c.code == 0x0670 || c.code in 0x06D6..0x06ED

    private fun joinsNext(c: Char?) = c == TATWEEL || (c != null && (FORMS[c]?.get(2) ?: 0) != 0)
    private fun joinsPrev(c: Char?) = c == TATWEEL || (c != null && c in FORMS && c != '\u0621')

    fun hasArabic(text: String) = text.any { it.code in 0x0600..0x06FF || it.code in 0xFB50..0xFEFF }

    /** Le texte (dans l'ordre logique) avec chaque lettre arabe sous sa forme liee. */
    fun shape(text: String): String {
        if (!hasArabic(text)) return text
        val out = StringBuilder(text.length)
        var i = 0
        fun prevLetter(at: Int): Char? {
            var j = at - 1
            while (j >= 0 && transparent(text[j])) j--
            return if (j >= 0) text[j] else null
        }
        fun nextIndex(at: Int): Int {
            var j = at + 1
            while (j < text.length && transparent(text[j])) j++
            return j
        }
        while (i < text.length) {
            val c = text[i]
            val forms = FORMS[c]
            if (forms == null) {
                out.append(c)
                i++
                continue
            }
            val prev = prevLetter(i)
            val linkedBefore = joinsNext(prev)
            val n = nextIndex(i)
            val next = text.getOrNull(n)
            if (c == LAM && next != null && next in LAM_ALEF) {
                out.append((LAM_ALEF.getValue(next) + if (linkedBefore) 1 else 0).toChar())
                // Les signes entre lam et alef sont gardes.
                for (k in i + 1 until n) out.append(text[k])
                i = n + 1
                continue
            }
            val linkedAfter = forms[2] != 0 && joinsPrev(next)
            val form = when {
                linkedBefore && linkedAfter -> forms[3]
                linkedBefore -> forms[1]
                linkedAfter -> forms[2]
                else -> forms[0]
            }
            out.append(form.toChar())
            i++
        }
        return out.toString()
    }
}

// ---- Traduction sur l'appareil ----------------------------------------------------------------------

/** Le traducteur de l'appareil (ML Kit sur Android : gratuit, sans Internet une fois la langue la). */
interface DeviceTranslator {
    /** Les langues qu'il sait traduire (codes de [TRANSLATION_LANGUAGES]). */
    val languages: Set<String>

    /** La langue de [text], ou null si elle n'est pas reconnue. */
    suspend fun detect(text: String): String?

    /** Vrai si la langue est deja sur l'appareil. */
    suspend fun ready(code: String): Boolean

    /** Telecharge les langues qui manquent (environ 30 Mo chacune, une seule fois). */
    suspend fun prepare(codes: List<String>)

    /** Traduit chaque texte de [source] vers [target]. */
    suspend fun translate(texts: List<String>, source: String, target: String): List<String>

    /**
     * Fait relire la traduction [draft] de [original] par l'IA de l'appareil (Gemini Nano), pour un
     * texte plus naturel. Null si elle ne peut pas (pas disponible, reponse douteuse).
     */
    suspend fun polish(original: String, draft: String, source: String, target: String): String? = null
}
