package app.nougat.pdf.engine

import kotlin.math.abs
import kotlin.math.max

/*
 * Markdown : un PDF devient un texte Markdown (titres, listes, tableaux, paragraphes), et un texte
 * Markdown devient un PDF mis en page (A4, polices standard).
 */

// ---- PDF vers Markdown ---------------------------------------------------------------------------

/** Le texte d'une page : ses lignes et la taille de la page affichee (points). */
class PageLines(val blocks: List<TextBlock>, val width: Float, val height: Float)

object PdfToMarkdown {
    private val BULLET = Regex("^[•●▪◦■□➢►\\-–—*]\\s+")
    private val NUMBER_ONLY = Regex("^\\s*(?:page\\s*)?\\d+(?:\\s*(?:/|sur|of|von|de|di)\\s*\\d+)?\\s*$", RegexOption.IGNORE_CASE)

    fun convert(pages: List<PageLines>): String {
        val sizes = pages.flatMap { p -> p.blocks.flatMap { b -> List(b.text.length.coerceAtMost(200)) { b.fontSize } } }.sorted()
        val body = if (sizes.isEmpty()) 11f else sizes[sizes.size / 2]
        val out = StringBuilder()
        for (page in pages) {
            for (part in page(page, body)) {
                if (out.isNotEmpty()) out.append("\n\n")
                out.append(part)
            }
        }
        return out.toString().trim() + "\n"
    }

    /** Les morceaux de Markdown d'une page, dans l'ordre de lecture. */
    private fun page(page: PageLines, body: Float): List<String> {
        val h = page.height
        // 1. Les lignes de la page (morceaux sur la meme ligne de base), de haut en bas.
        val rows = ArrayList<MutableList<TextBlock>>()
        for (b in page.blocks.sortedBy { it.rect.bottom }) {
            val row = rows.lastOrNull()
            val lh = (b.rect.bottom - b.rect.top) * h
            if (row != null && abs(row.first().rect.bottom - b.rect.bottom) * h <= lh * 0.35f) row.add(b) else rows.add(mutableListOf(b))
        }
        rows.forEach { r -> r.sortBy { it.rect.left } }
        // 2. Tableaux : au moins deux lignes de suite avec le meme nombre de cases (2 ou plus).
        val parts = ArrayList<Pair<Float, String>>()
        val loose = ArrayList<TextBlock>()
        var i = 0
        while (i < rows.size) {
            val cells = rows[i].size
            var j = i
            while (j < rows.size && rows[j].size == cells && cells >= 2) j++
            if (j - i >= 2) {
                val table = rows.subList(i, j)
                val md = StringBuilder()
                table.forEachIndexed { k, row ->
                    md.append("| ").append(row.joinToString(" | ") { escapeCell(it.text) }).append(" |")
                    if (k == 0) md.append("\n|").append(" --- |".repeat(cells))
                    if (k < table.size - 1) md.append('\n')
                }
                parts.add(table.first().first().rect.top to md.toString())
                i = j
            } else {
                loose.addAll(rows[i])
                i++
            }
        }
        // 3. Le reste en paragraphes : titres d'apres la taille, listes d'apres la puce.
        val ordered = page.blocks.filter { it in loose }
        for (p in Paragraphs.group(0, ordered, page.width, page.height)) {
            val text = p.text.trim()
            if (text.isEmpty() || NUMBER_ONLY.matches(text)) continue
            val md = when {
                p.fontSize >= body * 1.6f && text.length < 150 -> "# $text"
                p.fontSize >= body * 1.3f && text.length < 150 -> "## $text"
                (p.fontSize >= body * 1.12f || (p.bold && p.lines.size == 1)) && text.length < 120 -> "### $text"
                BULLET.containsMatchIn(text) -> "- " + text.replace(BULLET, "")
                else -> text
            }
            parts.add(p.rect.top to md)
        }
        return parts.sortedBy { it.first }.map { it.second }
    }

    private fun escapeCell(s: String) = s.replace("|", "\\|").trim()
}

// ---- Markdown vers PDF ---------------------------------------------------------------------------

/** Un morceau de texte dans une police. */
class MdSpan(val text: String, val font: FontKind)

sealed interface MdBlock {
    class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock
    class Paragraph(val spans: List<MdSpan>) : MdBlock
    class ListItem(val marker: String, val depth: Int, val spans: List<MdSpan>) : MdBlock
    class Quote(val spans: List<MdSpan>) : MdBlock
    class Code(val lines: List<String>) : MdBlock
    class Table(val rows: List<List<List<MdSpan>>>) : MdBlock
    data object Rule : MdBlock
}

object MarkdownParser {
    private val HEADING = Regex("^(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val BULLET = Regex("^(\\s*)[-*+]\\s+(.*)$")
    private val NUMBERED = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
    private val RULE = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
    private val TABLE_SEPARATOR = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    fun parse(text: String): List<MdBlock> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = ArrayList<MdBlock>()
        val paragraph = ArrayList<String>()
        fun flush() {
            if (paragraph.isNotEmpty()) {
                blocks.add(MdBlock.Paragraph(inline(paragraph.joinToString(" ") { it.trim() })))
                paragraph.clear()
            }
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.trimStart().startsWith("```") -> {
                    flush()
                    val code = ArrayList<String>()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) code.add(lines[i++])
                    // i est sur la ligne ``` de fin : la boucle la saute.
                    blocks.add(MdBlock.Code(code))
                }
                line.isBlank() -> flush()
                RULE.matches(line) -> { flush(); blocks.add(MdBlock.Rule) }
                HEADING.matches(line) -> {
                    flush()
                    val m = HEADING.find(line)!!
                    blocks.add(MdBlock.Heading(m.groupValues[1].length, inline(m.groupValues[2])))
                }
                line.trimStart().startsWith(">") -> {
                    flush()
                    val quote = ArrayList<String>()
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) quote.add(lines[i++].trimStart().removePrefix(">").trim())
                    blocks.add(MdBlock.Quote(inline(quote.joinToString(" "))))
                    continue
                }
                line.contains('|') && i + 1 < lines.size && TABLE_SEPARATOR.matches(lines[i + 1]) -> {
                    flush()
                    val rows = ArrayList<List<List<MdSpan>>>()
                    rows.add(cells(line))
                    i += 2
                    while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) rows.add(cells(lines[i++]))
                    blocks.add(MdBlock.Table(rows))
                    continue
                }
                BULLET.matches(line) -> {
                    flush()
                    val m = BULLET.find(line)!!
                    blocks.add(MdBlock.ListItem("•", m.groupValues[1].length / 2, inline(m.groupValues[2])))
                }
                NUMBERED.matches(line) -> {
                    flush()
                    val m = NUMBERED.find(line)!!
                    blocks.add(MdBlock.ListItem(m.groupValues[2] + ".", m.groupValues[1].length / 2, inline(m.groupValues[3])))
                }
                else -> paragraph.add(line)
            }
            i++
        }
        flush()
        return blocks
    }

    private fun cells(line: String): List<List<MdSpan>> =
        line.trim().removePrefix("|").removeSuffix("|").split(Regex("(?<!\\\\)\\|")).map { inline(it.replace("\\|", "|").trim()) }

    /** Gras (** ou __), italique (* ou _), code (`), liens [texte](adresse) -> « texte (adresse) ». */
    fun inline(text: String): List<MdSpan> {
        val withLinks = Regex("\\[([^\\]]+)]\\(([^)]+)\\)").replace(text) { m ->
            val label = m.groupValues[1]
            val url = m.groupValues[2]
            if (label == url) label else "$label ($url)"
        }
        val spans = ArrayList<MdSpan>()
        var bold = false
        var italic = false
        val sb = StringBuilder()
        fun font() = when {
            bold && italic -> FontKind.BoldItalic
            bold -> FontKind.Bold
            italic -> FontKind.Italic
            else -> FontKind.Regular
        }
        fun flush(kind: FontKind = font()) {
            if (sb.isNotEmpty()) {
                spans.add(MdSpan(sb.toString(), kind))
                sb.clear()
            }
        }
        var i = 0
        val s = withLinks
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length -> { sb.append(s[i + 1]); i += 2; continue }
                c == '`' -> {
                    val end = s.indexOf('`', i + 1)
                    if (end > i) {
                        flush()
                        spans.add(MdSpan(s.substring(i + 1, end), FontKind.Mono))
                        i = end + 1
                        continue
                    }
                    sb.append(c)
                }
                (c == '*' || c == '_') && i + 1 < s.length && s[i + 1] == c -> { flush(); bold = !bold; i += 2; continue }
                (c == '*' || c == '_') && (italic || (i + 1 < s.length && !s[i + 1].isWhitespace())) &&
                    !(c == '_' && i > 0 && s[i - 1].isLetterOrDigit()) -> { flush(); italic = !italic }
                else -> sb.append(c)
            }
            i++
        }
        flush()
        return spans
    }
}

/** Met en page un texte Markdown dans un document vide : pages A4, marges de 2 cm. */
object MarkdownLayout {
    private const val MARGIN = 56f
    private const val BODY = 11f
    private val INK = 0xFF1A1A1A.toInt()
    private val MUTED = 0xFF555555.toInt()
    private val RULE_COLOR = 0xFFBBBBBB.toInt()
    private val CODE_BG = 0xFFF1EEE9.toInt()

    /** Une ligne prete a ecrire : ses morceaux et leur taille. */
    private class Line(val runs: List<MdSpan>, val size: Float, val indent: Float, val color: Int, val marker: String? = null)

    fun render(doc: EditableDoc, blocks: List<MdBlock>) {
        val width = A4_WIDTH
        val height = A4_HEIGHT
        var page = -1
        var y = 0f
        // Les dessins de chaque page, faits dans un seul passage par page.
        val pending = ArrayList<(PageCanvas) -> Unit>()
        fun flushPage() {
            if (page >= 0 && pending.isNotEmpty()) {
                val ops = pending.toList()
                doc.draw(page) { canvas -> ops.forEach { it(canvas) } }
            }
            pending.clear()
        }
        fun newPage() {
            flushPage()
            page++
            doc.insertBlankPage(page, width, height)
            y = height - MARGIN
        }
        fun ensure(space: Float) {
            if (page < 0 || y - space < MARGIN) newPage()
        }
        // Mesurer demande une page : on mesure avec la premiere.
        lateinit var measure: (String, Float, FontKind) -> Float
        newPage()
        doc.draw(0) { canvas -> measure = { t, s, f -> canvas.styledWidth(t, s, f) } }

        fun wrap(spans: List<MdSpan>, size: Float, maxWidth: Float): List<List<MdSpan>> {
            val words = ArrayList<MdSpan>()
            for (sp in spans) {
                val parts = sp.text.split(' ')
                parts.forEachIndexed { k, w ->
                    if (w.isNotEmpty()) words.add(MdSpan(w, sp.font))
                    if (k < parts.size - 1) words.add(MdSpan(" ", sp.font))
                }
            }
            val lines = ArrayList<List<MdSpan>>()
            var line = ArrayList<MdSpan>()
            var lineWidth = 0f
            for (w in words) {
                val ww = measure(w.text, size, w.font)
                if (w.text == " ") {
                    if (line.isNotEmpty()) { line.add(w); lineWidth += ww }
                    continue
                }
                if (lineWidth + ww > maxWidth && line.isNotEmpty()) {
                    while (line.isNotEmpty() && line.last().text == " ") line.removeAt(line.lastIndex)
                    lines.add(line)
                    line = ArrayList()
                    lineWidth = 0f
                }
                line.add(w)
                lineWidth += ww
            }
            while (line.isNotEmpty() && line.last().text == " ") line.removeAt(line.lastIndex)
            if (line.isNotEmpty()) lines.add(line)
            return lines
        }

        fun writeLines(spans: List<MdSpan>, size: Float, indent: Float, color: Int, marker: String? = null, gapAfter: Float = size * 0.6f) {
            val lines = wrap(spans, size, width - 2 * MARGIN - indent)
            val lh = size * 1.38f
            lines.forEachIndexed { k, runs ->
                ensure(lh)
                y -= lh
                val baseline = y + size * 0.3f
                val x0 = MARGIN + indent
                val mk = if (k == 0) marker else null
                val runsCopy = runs.toList()
                pending.add { canvas ->
                    if (mk != null) canvas.styledText(x0 - measure(mk, size, FontKind.Regular) - 6f, baseline, mk, size, color, FontKind.Regular)
                    var x = x0
                    for (r in runsCopy) {
                        canvas.styledText(x, baseline, r.text, size, color, r.font)
                        x += measure(r.text, size, r.font)
                    }
                }
            }
            y -= gapAfter
        }

        for (block in blocks) {
            when (block) {
                is MdBlock.Heading -> {
                    val size = when (block.level) { 1 -> 22f; 2 -> 17f; 3 -> 14f; else -> 12f }
                    ensure(size * 3f)
                    y -= size * 0.5f
                    writeLines(block.spans.map { MdSpan(it.text, if (it.font == FontKind.Mono) FontKind.Mono else FontKind.Bold) }, size, 0f, INK, gapAfter = size * 0.4f)
                }
                is MdBlock.Paragraph -> writeLines(block.spans, BODY, 0f, INK)
                is MdBlock.ListItem -> writeLines(block.spans, BODY, 18f + block.depth * 16f, INK, block.marker, gapAfter = 3f)
                is MdBlock.Quote -> {
                    val top = y
                    writeLines(block.spans.map { MdSpan(it.text, if (it.font == FontKind.Bold) FontKind.BoldItalic else FontKind.Italic) }, BODY, 16f, MUTED)
                    val bottom = y + BODY * 0.6f
                    if (top > bottom) pending.add { it.fillRect(MARGIN + 2f, bottom, 3f, top - bottom, RULE_COLOR) }
                }
                is MdBlock.Code -> {
                    val size = 9.5f
                    val lh = size * 1.35f
                    for (codeLine in block.lines.ifEmpty { listOf("") }) {
                        ensure(lh)
                        y -= lh
                        val baseline = y + size * 0.3f
                        val top = y
                        pending.add { canvas ->
                            canvas.fillRect(MARGIN, top - 2f, width - 2 * MARGIN, lh + 0.5f, CODE_BG)
                            canvas.styledText(MARGIN + 6f, baseline, codeLine.replace('\t', ' '), size, INK, FontKind.Mono)
                        }
                    }
                    y -= BODY * 0.6f
                }
                is MdBlock.Rule -> {
                    ensure(16f)
                    y -= 8f
                    val ly = y
                    pending.add { it.stroke(floatArrayOf(MARGIN, ly, width - MARGIN, ly), RULE_COLOR, 0.8f) }
                    y -= 8f
                }
                is MdBlock.Table -> {
                    val cols = max(1, block.rows.maxOf { it.size })
                    val colWidth = (width - 2 * MARGIN) / cols
                    val size = 10f
                    val lh = size * 1.35f
                    block.rows.forEachIndexed { r, row ->
                        val wrapped = (0 until cols).map { c -> wrap(row.getOrNull(c).orEmpty(), size, colWidth - 8f) }
                        val rowHeight = max(1, wrapped.maxOf { it.size }) * lh + 6f
                        ensure(rowHeight)
                        val top = y
                        y -= rowHeight
                        val bottom = y
                        val header = r == 0
                        pending.add { canvas ->
                            if (header) canvas.fillRect(MARGIN, bottom, width - 2 * MARGIN, rowHeight, CODE_BG)
                            canvas.stroke(floatArrayOf(MARGIN, bottom, width - MARGIN, bottom), RULE_COLOR, 0.6f)
                            wrapped.forEachIndexed { c, lines ->
                                lines.forEachIndexed { k, runs ->
                                    var x = MARGIN + c * colWidth + 4f
                                    val baseline = top - 3f - (k + 1) * lh + size * 0.3f
                                    for (run in runs) {
                                        val font = if (header && run.font == FontKind.Regular) FontKind.Bold else run.font
                                        canvas.styledText(x, baseline, run.text, size, INK, font)
                                        x += measure(run.text, size, font)
                                    }
                                }
                            }
                        }
                    }
                    y -= BODY * 0.6f
                }
            }
        }
        flushPage()
    }
}
