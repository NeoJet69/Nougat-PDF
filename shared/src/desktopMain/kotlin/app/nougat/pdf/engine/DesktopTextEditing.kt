package app.nougat.pdf.engine

import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSBase
import org.apache.pdfbox.cos.COSFloat
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSNumber
import org.apache.pdfbox.cos.COSString
import org.apache.pdfbox.pdfparser.PDFStreamParser
import org.apache.pdfbox.pdfwriter.ContentStreamWriter
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.common.PDStream
import org.apache.pdfbox.pdmodel.font.FontMappers
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.PDType3Font
import org.apache.pdfbox.pdmodel.font.PDVectorFont
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.apache.pdfbox.util.Matrix
import org.apache.pdfbox.util.Vector
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import app.nougat.pdf.i18n.Str

/** Analyse d'une page avec PDFBox : les lettres, les operateurs de texte et leurs polices. */
internal class DesktopPageAnalysis(val analysis: PageTextAnalysis, val fonts: Map<Int, PDFont>)

private val TEXT_OPERATORS = setOf("Tj", "TJ", "'", "\"")

internal fun analyzePageText(doc: PDDocument, pageIndex: Int, displayWidth: Float, displayHeight: Float): DesktopPageAnalysis {
    val capture = CaptureStripper(displayWidth, displayHeight)
    capture.startPage = pageIndex + 1
    capture.endPage = pageIndex + 1
    capture.getText(doc)
    return capture.result()
}

internal fun pageTextBlocks(doc: PDDocument, pageIndex: Int): List<TextBlock> {
    val page = doc.getPage(pageIndex)
    val box = page.cropBox
    val rotated = page.rotation % 180 != 0
    val w = if (rotated) box.height else box.width
    val h = if (rotated) box.width else box.height
    return groupTextBlocks(pageIndex, analyzePageText(doc, pageIndex, w, h).analysis, w, h)
}

/** Remplace le texte d'une ligne de la page (voir [EditableDoc.replaceText]). */
internal fun replaceTextWithPdfBox(doc: PDDocument, block: TextBlock, newText: String): TextEditResult {
    val page = doc.getPage(block.page)
    val box = page.cropBox
    val rotated = page.rotation % 180 != 0
    val w = if (rotated) box.height else box.width
    val h = if (rotated) box.width else box.height

    val result = analyzePageText(doc, block.page, w, h)
    val target = groupTextBlocks(block.page, result.analysis, w, h).findMatch(block)
        ?: throw TextEditException(Str.ErrTextNotFound)
    if (!target.editable) throw TextEditException(Str.ErrTextNotEditable)

    val anchorOp = result.analysis.ops[target.glyphs.first().op]
        ?: throw TextEditException(Str.ErrTextOpNotFound)
    val font = result.fonts[anchorOp.index] ?: throw TextEditException(Str.ErrFontNotFound)
    val text = newText.replace('\n', ' ').replace('\t', ' ')

    val same = sameFontReplacement(font, text, anchorOp)
    val installed = if (same == null) installedFontReplacement(doc, page, font, text, anchorOp) else null
    val replacement = same ?: installed ?: otherFontReplacement(page, font, text, anchorOp)
    val plan = TextEditPlanner.plan(result.analysis, target, replacement)
    rewriteContent(doc, page, plan)
    return when {
        same != null -> TextEditResult.SameFont
        installed != null -> TextEditResult.InstalledFont
        else -> TextEditResult.SimilarFont
    }
}

/**
 * La police d'origine est embarquee partiellement, mais elle est peut-etre installee sur
 * l'ordinateur (Arial, Calibri, Times New Roman...) : on l'embarque alors en entier pour
 * ecrire le nouveau texte a l'identique.
 */
private fun installedFontReplacement(doc: PDDocument, page: PDPage, original: PDFont, text: String, op: AnalyzedTextOp): Replacement.OtherFont? {
    if (op.fontResource == null) return null
    val baseName = original.name?.substringAfter('+')?.takeIf { it.isNotBlank() } ?: return null
    val mapping = runCatching { FontMappers.instance().getTrueTypeFont(baseName, original.fontDescriptor) }.getOrNull()
        ?: return null
    if (mapping.isFallback) return null
    val ttf = mapping.font ?: return null
    val font = runCatching { PDType0Font.load(doc, ttf, true) }.getOrNull() ?: return null
    val bytes = ByteArrayOutputStream()
    var width = 0f
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        val ch = String(Character.toChars(cp))
        i += ch.length
        val encoded = runCatching { font.encode(ch) }.getOrNull() ?: return null
        val code = runCatching { font.readCode(ByteArrayInputStream(encoded)) }.getOrNull() ?: return null
        if (!runCatching { font.hasGlyph(code) }.getOrDefault(false)) return null
        font.addToSubset(cp)
        width += (font.getWidth(code) / 1000f * op.fontSize + op.charSpacing) * op.hScale
        bytes.write(encoded)
    }
    val resources = page.resources ?: PDResources().also { page.resources = it }
    return Replacement.OtherFont(resources.add(font).name, bytes.toByteArray(), width)
}

/** Le nouveau texte dans la police d'origine, ou null si elle n'a pas toutes les lettres necessaires. */
private fun sameFontReplacement(font: PDFont, text: String, op: AnalyzedTextOp): Replacement.SameFont? {
    if (font is PDType3Font) return null
    val scale = op.fontSize * op.hScale
    val spaceWidth = runCatching { font.spaceWidth }.getOrDefault(0f).takeIf { it > 0f } ?: 250f
    // Les espaces deviennent des decalages : beaucoup de polices embarquees n'ont pas de glyphe d'espace.
    val spaceAdvance = (spaceWidth / 1000f * op.fontSize + op.charSpacing + op.wordSpacing) * op.hScale
    val elements = ArrayList<Any>()
    var width = 0f
    for (part in splitWordsAndSpaces(text)) {
        if (part == " ") {
            elements.add(-spaceAdvance * 1000f / scale)
            width += spaceAdvance
            continue
        }
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < part.length) {
            val cp = part.codePointAt(i)
            val ch = String(Character.toChars(cp))
            i += ch.length
            val encoded = runCatching { font.encode(ch) }.getOrNull() ?: return null
            if (encoded.isEmpty()) return null
            val code = runCatching { font.readCode(ByteArrayInputStream(encoded)) }.getOrNull() ?: return null
            if (font is PDVectorFont) {
                // Police embarquee partiellement : la lettre peut manquer, ou n'etre qu'une forme vide.
                if (!runCatching { font.hasGlyph(code) }.getOrDefault(false)) return null
                val bounds = runCatching { font.getPath(code).bounds2D }.getOrNull() ?: return null
                if (bounds.isEmpty) return null
            }
            val glyphWidth = runCatching { font.getWidth(code) }.getOrDefault(0f)
            if (glyphWidth <= 0f) return null
            width += (glyphWidth / 1000f * op.fontSize + op.charSpacing) * op.hScale
            bytes.write(encoded)
        }
        elements.add(bytes.toByteArray())
    }
    return Replacement.SameFont(elements, width)
}

/** Le nouveau texte dans la police standard la plus proche, ajoutee aux ressources de la page. */
private fun otherFontReplacement(page: PDPage, original: PDFont, text: String, op: AnalyzedTextOp): Replacement.OtherFont {
    if (op.fontResource == null) throw TextEditException(Str.ErrOriginalFontNotFound)
    val descriptor = original.fontDescriptor
    val choice = closestStandardFont(
        fontName = original.name,
        bold = descriptor?.let { it.isForceBold || it.fontWeight >= 600f } ?: false,
        italic = descriptor?.isItalic ?: false,
        serif = descriptor?.isSerif ?: false,
        monospace = descriptor?.isFixedPitch ?: false,
    )
    val font = PDType1Font(standardName(choice))
    val resources = page.resources ?: PDResources().also { page.resources = it }
    val name = resources.add(font).name
    val safe = buildString {
        for (c in text) append(if (runCatching { font.encode(c.toString()) }.isSuccess) c else '?')
    }
    val bytes = font.encode(safe)
    var width = 0f
    for (b in bytes) {
        val code = b.toInt() and 0xFF
        width += (font.getWidth(code) / 1000f * op.fontSize + op.charSpacing + (if (code == 32) op.wordSpacing else 0f)) * op.hScale
    }
    return Replacement.OtherFont(name, bytes, width)
}

private fun standardName(font: StandardFont): Standard14Fonts.FontName = when (font) {
    StandardFont.Helvetica -> Standard14Fonts.FontName.HELVETICA
    StandardFont.HelveticaBold -> Standard14Fonts.FontName.HELVETICA_BOLD
    StandardFont.HelveticaOblique -> Standard14Fonts.FontName.HELVETICA_OBLIQUE
    StandardFont.HelveticaBoldOblique -> Standard14Fonts.FontName.HELVETICA_BOLD_OBLIQUE
    StandardFont.TimesRoman -> Standard14Fonts.FontName.TIMES_ROMAN
    StandardFont.TimesBold -> Standard14Fonts.FontName.TIMES_BOLD
    StandardFont.TimesItalic -> Standard14Fonts.FontName.TIMES_ITALIC
    StandardFont.TimesBoldItalic -> Standard14Fonts.FontName.TIMES_BOLD_ITALIC
    StandardFont.Courier -> Standard14Fonts.FontName.COURIER
    StandardFont.CourierBold -> Standard14Fonts.FontName.COURIER_BOLD
    StandardFont.CourierOblique -> Standard14Fonts.FontName.COURIER_OBLIQUE
    StandardFont.CourierBoldOblique -> Standard14Fonts.FontName.COURIER_BOLD_OBLIQUE
}

/** Reecrit le flux de contenu de la page en remplacant les operateurs de [plan]. */
private fun rewriteContent(doc: PDDocument, page: PDPage, plan: Map<Int, List<ContentOp>>) {
    val parser = PDFStreamParser(page)
    val tokens = ArrayList<Any>()
    while (true) tokens.add(parser.parseNextToken() ?: break)

    val out = ArrayList<Any>(tokens.size + 16)
    var opIndex = -1
    var operandsStart = 0
    for ((i, token) in tokens.withIndex()) {
        if (token !is Operator) continue
        opIndex++
        val replacement = plan[opIndex]
        if (replacement == null) {
            out.addAll(tokens.subList(operandsStart, i + 1))
        } else {
            for (op in replacement) out.addAll(toTokens(op))
        }
        operandsStart = i + 1
    }
    out.addAll(tokens.subList(operandsStart, tokens.size))

    val stream = PDStream(doc)
    stream.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(out) }
    page.setContents(stream)
}

private fun toTokens(op: ContentOp): List<Any> = when (op) {
    is ContentOp.ShowArray -> {
        val array = COSArray()
        for (e in op.elements) {
            when (e) {
                is ByteArray -> array.add(COSString(e))
                is Float -> array.add(COSFloat(e))
                else -> error("Element inattendu : $e")
            }
        }
        listOf(array, Operator.getOperator("TJ"))
    }
    is ContentOp.SetFont -> listOf(COSName.getPDFName(op.resource), COSFloat(op.size), Operator.getOperator("Tf"))
    is ContentOp.Simple -> op.operands.map { COSFloat(it) } + Operator.getOperator(op.operator)
}

/**
 * Parcourt la page comme l'extraction de texte, en notant pour chaque lettre l'operateur
 * qui la dessine (son numero dans le flux), la chaine et les octets.
 */
private class CaptureStripper(private val pageWidth: Float, private val pageHeight: Float) : PDFTextStripper() {
    private val glyphs = ArrayList<AnalyzedGlyph>()
    private val builders = LinkedHashMap<Int, OpBuilder>()
    private val fonts = HashMap<Int, PDFont>()

    private var opIndex = -1
    private var depth = 0
    private var topName = ""
    private var topOperands: List<COSBase> = emptyList()
    private var segment = -1
    private var ranges: List<IntRange> = emptyList()
    private var glyphIndex = -1
    private var pendingKey: GlyphKey? = null
    private var pendingEditable = false
    private var fontResource: String? = null
    private val fontStack = ArrayList<String?>()

    fun result(): DesktopPageAnalysis {
        val ops = HashMap<Int, AnalyzedTextOp>()
        val broken = HashSet<Int>()
        for ((index, b) in builders) {
            val op = b.build()
            if (op == null) broken.add(index) else ops[index] = op
        }
        val finalGlyphs = if (broken.isEmpty()) glyphs else glyphs.map { g ->
            if (g.key != null && g.key.op in broken) AnalyzedGlyph(g.key, g.unicode, g.box, g.size, false, g.style) else g
        }
        return DesktopPageAnalysis(PageTextAnalysis(finalGlyphs, ops), fonts)
    }

    override fun processOperator(operator: Operator, operands: List<COSBase>) {
        if (depth == 0) {
            opIndex++
            topName = operator.name
            // Copie : PDFBox reutilise (et vide) la liste apres chaque operateur.
            topOperands = operands.toList()
            segment = -1
            when (operator.name) {
                "q" -> fontStack.add(fontResource)
                "Q" -> if (fontStack.isNotEmpty()) fontResource = fontStack.removeAt(fontStack.lastIndex)
                "Tf" -> fontResource = (operands.firstOrNull() as? COSName)?.name
            }
        }
        depth++
        try {
            super.processOperator(operator, operands)
        } finally {
            depth--
        }
    }

    override fun showText(string: ByteArray) {
        if (topName in TEXT_OPERATORS) {
            segment++
            val state = graphicsState.textState
            val font = state.font
            ranges = decodeRanges(font, string)
            glyphIndex = -1
            val builder = builders.getOrPut(opIndex) {
                if (font != null) fonts[opIndex] = font
                OpBuilder(
                    index = opIndex,
                    name = topName,
                    operands = topOperands,
                    fontResource = fontResource,
                    fontSize = state.fontSize,
                    hScale = state.horizontalScaling / 100f,
                    charSpacing = state.characterSpacing,
                    wordSpacing = state.wordSpacing,
                    usable = font != null && !font.isVertical && state.fontSize * state.horizontalScaling != 0f &&
                        state.renderingMode != RenderingMode.NEITHER,
                )
            }
            builder.startSegment(segment, string)
            pendingEditable = builder.usable
        }
        super.showText(string)
    }

    override fun showGlyph(textRenderingMatrix: Matrix, font: PDFont, code: Int, displacement: Vector) {
        if (topName in TEXT_OPERATORS && segment >= 0) {
            glyphIndex++
            val state = graphicsState.textState
            val range = ranges.getOrNull(glyphIndex) ?: IntRange.EMPTY
            val single = range.first == range.last
            val wordSpacing = if (single && code == 32) state.wordSpacing else 0f
            val advance = (displacement.x * state.fontSize + state.characterSpacing + wordSpacing) * state.horizontalScaling / 100f
            builders[opIndex]?.addGlyph(segment, range, advance)
            pendingKey = GlyphKey(opIndex, segment, glyphIndex)
        } else {
            pendingKey = null
        }
        super.showGlyph(textRenderingMatrix, font, code, displacement)
        pendingKey = null
    }

    override fun processTextPosition(text: TextPosition) {
        val key = pendingKey
        val x = text.xDirAdj
        val y = text.yDirAdj
        val h = text.heightDir.takeIf { it > 0f } ?: text.fontSizeInPt
        glyphs.add(
            AnalyzedGlyph(
                key = key,
                unicode = text.unicode ?: "",
                box = NormRect(
                    left = x / pageWidth,
                    top = (y - h * 1.1f) / pageHeight,
                    right = (x + text.widthDirAdj) / pageWidth,
                    bottom = (y + h * 0.3f) / pageHeight,
                ),
                size = text.fontSizeInPt,
                editable = key != null && pendingEditable,
                style = "${text.font?.name}@${(text.fontSizeInPt * 10).toInt()}",
            ),
        )
    }

    private fun decodeRanges(font: PDFont?, bytes: ByteArray): List<IntRange> {
        if (font == null) return bytes.indices.map { it..it }
        val result = ArrayList<IntRange>()
        val input = ByteArrayInputStream(bytes)
        var pos = 0
        while (input.available() > 0) {
            val before = input.available()
            runCatching { font.readCode(input) }.onFailure { return bytes.indices.map { it..it } }
            val length = before - input.available()
            if (length <= 0) break
            result.add(pos until pos + length)
            pos += length
        }
        return result
    }
}

/** Assemble un [AnalyzedTextOp] au fil des chaines et des lettres vues pendant l'analyse. */
private class OpBuilder(
    val index: Int,
    val name: String,
    val operands: List<COSBase>,
    val fontResource: String?,
    val fontSize: Float,
    val hScale: Float,
    val charSpacing: Float,
    val wordSpacing: Float,
    val usable: Boolean,
) {
    private val segments = LinkedHashMap<Int, Pair<ByteArray, MutableList<SegmentGlyph>>>()

    fun startSegment(segment: Int, bytes: ByteArray) {
        segments[segment] = bytes to ArrayList()
    }

    fun addGlyph(segment: Int, range: IntRange, advance: Float) {
        segments[segment]?.second?.add(SegmentGlyph(range.first, range.last + 1, advance))
    }

    /** Null si les chaines vues ne correspondent pas aux operandes (operateur mal forme). */
    fun build(): AnalyzedTextOp? {
        if (!usable) return null
        val elements = ArrayList<TJElement>()
        var quote: Pair<Float, Float>? = null
        var stringIndex = 0
        fun addString(s: COSString): Boolean {
            val (bytes, glyphs) = segments[stringIndex] ?: return false
            if (!bytes.contentEquals(s.bytes)) return false
            elements.add(TJElement.Str(stringIndex, bytes, glyphs))
            stringIndex++
            return true
        }
        when (name) {
            "TJ" -> {
                val array = operands.firstOrNull() as? COSArray ?: return null
                for (i in 0 until array.size()) {
                    when (val item = array.getObject(i)) {
                        is COSString -> if (!addString(item)) return null
                        is COSNumber -> elements.add(TJElement.Num(item.floatValue()))
                        else -> return null
                    }
                }
            }
            "Tj", "'" -> {
                val s = operands.firstOrNull() as? COSString ?: return null
                if (!addString(s)) return null
            }
            "\"" -> {
                if (operands.size < 3) return null
                val aw = (operands[0] as? COSNumber)?.floatValue() ?: return null
                val ac = (operands[1] as? COSNumber)?.floatValue() ?: return null
                quote = aw to ac
                val s = operands[2] as? COSString ?: return null
                if (!addString(s)) return null
            }
            else -> return null
        }
        if (stringIndex != segments.size) return null
        return AnalyzedTextOp(index, name, fontResource, fontSize, hScale, charSpacing, wordSpacing, quote, elements)
    }
}
