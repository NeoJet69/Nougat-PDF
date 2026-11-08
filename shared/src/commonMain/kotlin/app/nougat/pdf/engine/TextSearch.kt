package app.nougat.pdf.engine

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Une occurrence trouvee : la page et les rectangles a surligner (un par ligne). */
data class SearchHit(val page: Int, val rects: List<NormRect>)

/** Assemble le texte d'une page caractere par caractere, sans doubles espaces. */
class PageTextBuilder {
    private val text = StringBuilder()
    private val boxes = ArrayList<NormRect?>()

    fun addGlyph(unicode: String, box: NormRect) {
        for (c in unicode) {
            if (c.isWhitespace()) addSpace() else {
                text.append(c)
                boxes.add(box)
            }
        }
    }

    /** Separation entre deux mots ou deux lignes. */
    fun addSpace() {
        if (text.isNotEmpty() && text.last() != ' ') {
            text.append(' ')
            boxes.add(null)
        }
    }

    fun build(): PageText = PageText(text.toString(), boxes.toList())
}

/**
 * Recherche sans tenir compte des majuscules ni des accents :
 * « ecole » trouve « École ».
 */
object TextSearch {

    fun find(page: Int, pageText: PageText, query: String): List<SearchHit> {
        val needle = normalize(query.trim().replace(Regex("\\s+"), " "))
        if (needle.isEmpty()) return emptyList()
        val haystack = normalize(pageText.text)

        val hits = ArrayList<SearchHit>()
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) break
            hits.add(SearchHit(page, mergeByLine(pageText.boxes.subList(at, at + needle.length))))
            from = at + needle.length
        }
        return hits
    }

    /** Meme longueur que l'entree : chaque caractere est remplace par sa forme simple. */
    fun normalize(s: String): String = buildString(s.length) {
        for (c in s) append(simplify(c))
    }

    private fun simplify(c: Char): Char {
        val lower = c.lowercaseChar()
        return ACCENTS[lower] ?: lower
    }

    private val ACCENTS: Map<Char, Char> = buildMap {
        fun map(chars: String, to: Char) = chars.forEach { put(it, to) }
        map("àáâãäåā", 'a')
        map("çćč", 'c')
        map("èéêëēė", 'e')
        map("ìíîïī", 'i')
        map("ñń", 'n')
        map("òóôõöøō", 'o')
        map("ùúûüū", 'u')
        map("ýÿ", 'y')
        map("’‘`´", '\'')
        map(" ", ' ')
    }

    /** Regroupe les cases des lettres en un rectangle par ligne. */
    internal fun mergeByLine(boxes: List<NormRect?>): List<NormRect> {
        val lines = ArrayList<NormRect>()
        var current: NormRect? = null
        for (b in boxes) {
            if (b == null) continue
            val c = current
            current = if (c != null && sameLine(c, b)) {
                NormRect(min(c.left, b.left), min(c.top, b.top), max(c.right, b.right), max(c.bottom, b.bottom))
            } else {
                if (c != null) lines.add(c)
                b
            }
        }
        current?.let(lines::add)
        return lines
    }

    private fun sameLine(a: NormRect, b: NormRect): Boolean {
        val h = max(a.bottom - a.top, b.bottom - b.top)
        return abs(a.top - b.top) < h * 0.5f
    }
}
