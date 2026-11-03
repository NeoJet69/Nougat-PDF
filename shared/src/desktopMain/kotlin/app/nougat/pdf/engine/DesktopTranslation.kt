package app.nougat.pdf.engine

import app.nougat.pdf.i18n.Str
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.FontMappers
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.util.Matrix
import kotlin.math.max

/*
 * Copie traduite, version ordinateur (PDFBox) : le texte d'origine des paragraphes est retire du
 * flux de la page (ou recouvert de la couleur du papier sur un scan), puis la traduction est ecrite
 * dans le cadre du paragraphe, dans la couleur de l'encre d'origine et une police proche.
 */

/** Resolution du rendu qui sert a lire les couleurs du papier et de l'encre. */
private const val COLOR_DPI = 110f

/** Les polices des traductions d'un document : chaque police n'est embarquee qu'une fois. */
internal class TranslationFonts(private val doc: PDDocument) {
    private val loaded = HashMap<String, PDFont?>()

    /** Une police qui sait ecrire [text] ([shaped] : ce qui sera vraiment ecrit), proche du style de [p]. */
    fun fontFor(text: String, shaped: String, p: TextParagraph, language: String): PDFont {
        val style = p.standardFont
        val std = PDType1Font(standardName(style))
        if (encodable(std, shaped)) return std
        for (name in candidates(language, text, style)) {
            val font = load(name) ?: continue
            if (encodable(font, shaped)) return font
        }
        throw TextEditException(Str.TranslateNoFont)
    }

    private fun load(psName: String): PDFont? = loaded.getOrPut(psName) {
        val mapping = runCatching { FontMappers.instance().getTrueTypeFont(psName, null) }.getOrNull()
        if (mapping == null || mapping.isFallback || mapping.font == null) null
        else runCatching { PDType0Font.load(doc, mapping.font, true) }.getOrNull()
    }

    private fun encodable(font: PDFont, text: String): Boolean = runCatching {
        for (ch in text.filterNot { it.isWhitespace() }) {
            val bytes = font.encode(ch.toString())
            if (bytes.isEmpty()) return false
        }
        true
    }.getOrDefault(false)

    private fun candidates(language: String, text: String, style: StandardFont): List<String> {
        val bold = style.name.contains("Bold")
        val serif = style.name.startsWith("Times")
        val mono = style.name.startsWith("Courier")
        val cjk = when {
            language == "ja" || text.any { it.code in 0x3040..0x30FF } -> JAPANESE
            language == "ko" || text.any { it.code in 0xAC00..0xD7AF } -> KOREAN
            language == "zh" || text.any { isCjk(it) } -> CHINESE
            else -> emptyList()
        }
        val arabic = if (ArabicShaping.hasArabic(text)) ARABIC else emptyList()
        val latin = when {
            mono -> MONO
            serif && bold -> SERIF_BOLD + SERIF
            serif -> SERIF
            bold -> SANS_BOLD + SANS
            else -> SANS
        }
        return (cjk + arabic + latin + SANS + UNIVERSAL).distinct()
    }

    private companion object {
        val SANS = listOf("ArialMT", "Arial", "SegoeUI", "LiberationSans", "Arimo", "DejaVuSans", "NotoSans-Regular", "Tahoma", "Verdana", "FreeSans")
        val SANS_BOLD = listOf("Arial-BoldMT", "SegoeUI-Bold", "LiberationSans-Bold", "Arimo-Bold", "DejaVuSans-Bold", "NotoSans-Bold", "Tahoma-Bold", "Verdana-Bold")
        val SERIF = listOf("TimesNewRomanPSMT", "LiberationSerif", "Tinos", "DejaVuSerif", "NotoSerif-Regular", "Georgia", "Cambria", "FreeSerif")
        val SERIF_BOLD = listOf("TimesNewRomanPS-BoldMT", "LiberationSerif-Bold", "Tinos-Bold", "DejaVuSerif-Bold", "NotoSerif-Bold", "Georgia-Bold")
        val MONO = listOf("CourierNewPSMT", "LiberationMono", "Cousine", "DejaVuSansMono", "Consolas")
        val ARABIC = listOf("ArialMT", "Tahoma", "SegoeUI", "TimesNewRomanPSMT", "DejaVuSans", "NotoNaskhArabic-Regular", "NotoSansArabic-Regular", "FreeSerif")
        val CHINESE = listOf("MicrosoftYaHei", "SimSun", "SimHei", "DengXian-Regular", "NSimSun", "NotoSansSC-Regular", "NotoSansCJKsc-Regular", "WenQuanYiZenHei", "DroidSansFallbackFull", "ArialUnicodeMS")
        val JAPANESE = listOf("YuGothic-Regular", "Meiryo", "MS-Gothic", "MSGothic", "MS-UIGothic", "NotoSansJP-Regular", "NotoSansCJKjp-Regular") + CHINESE
        val KOREAN = listOf("MalgunGothic", "MalgunGothicRegular", "Gulim", "Batang", "NotoSansKR-Regular", "NotoSansCJKkr-Regular") + CHINESE
        val UNIVERSAL = listOf("ArialUnicodeMS", "SegoeUISymbol", "DejaVuSans", "FreeSans")
    }
}

/** Ecrit la traduction des paragraphes de la page [pageIndex] (voir [EditableDoc.writeTranslation]). */
internal fun writeTranslationWithPdfBox(doc: PDDocument, pageIndex: Int, items: List<TranslatedParagraph>, language: String, fonts: TranslationFonts) {
    if (items.isEmpty()) return
    val page = doc.getPage(pageIndex)
    val box = page.cropBox
    val rotated = page.rotation % 180 != 0
    val w = if (rotated) box.height else box.width
    val h = if (rotated) box.width else box.height
    val rtl = translationLanguage(language)?.rtl == true

    // 1. Les couleurs, lues sur la page telle qu'elle est (avant de retirer le texte).
    val image = PDFRenderer(doc).renderImageWithDPI(pageIndex, COLOR_DPI, ImageType.RGB)
    val iw = image.width
    val ih = image.height
    val pixels = image.getRGB(0, 0, iw, ih, null, 0, iw)
    fun colors(r: NormRect) = PageColors.sample(
        pixels, iw, ih,
        (r.left * iw).toInt(), (r.top * ih).toInt(), (r.right * iw).toInt() + 1, (r.bottom * ih).toInt() + 1,
    )

    // 2. Le texte d'origine : retire s'il est dessine par la page, sinon recouvert.
    val analysis = analyzePageText(doc, pageIndex, w, h).analysis
    val current = groupTextBlocks(pageIndex, analysis, w, h)
    val removable = ArrayList<TextBlock>()
    class Plan(val item: TranslatedParagraph, val paper: Int, val ink: Int, val covers: List<NormRect>)
    val plans = items.map { item ->
        val p = item.paragraph
        val covers = ArrayList<NormRect>()
        for (line in p.blocks) {
            val match = current.findMatch(line)
            if (match != null && match.editable && !match.scanned) removable.add(match) else covers.add(line.rect)
        }
        val (paper, ink) = colors(p.rect)
        // Un scan : tout le cadre du paragraphe prend la couleur du papier (un seul aplat, plus propre).
        Plan(item, paper, ink, if (p.scanned && covers.isNotEmpty()) listOf(p.rect) else covers)
    }
    if (removable.isNotEmpty()) rewriteContent(doc, page, TextEditPlanner.planRemoval(analysis, removable))

    // 3. La traduction.
    val geometry = PageGeometry(box.lowerLeftX, box.lowerLeftY, box.width, box.height, normalizeRotation(page.rotation))
    val m = geometry.displayToPdfMatrix()
    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
        cs.transform(Matrix(m[0], m[1], m[2], m[3], m[4], m[5]))
        for (plan in plans) {
            val p = plan.item.paragraph
            val pad = p.fontSize * 0.12f
            if (plan.covers.isNotEmpty()) {
                cs.setNonStrokingColor(red(plan.paper), green(plan.paper), blue(plan.paper))
                for (r in plan.covers) {
                    cs.addRect(r.left * w - pad, h - (r.bottom * h + pad), (r.right - r.left) * w + 2 * pad, (r.bottom - r.top) * h + 2 * pad)
                }
                cs.fill()
            }
            drawParagraph(cs, plan.item, plan.ink, w, h, rtl, language, fonts)
        }
    }
}

private fun drawParagraph(
    cs: PDPageContentStream,
    item: TranslatedParagraph,
    ink: Int,
    w: Float,
    h: Float,
    rtl: Boolean,
    language: String,
    fonts: TranslationFonts,
) {
    val p = item.paragraph
    val text = item.text.replace(Regex("\\s+"), " ").trim()
    if (text.isEmpty()) return
    val font = fonts.fontFor(text, BidiText.visual(text, rtl), p, language)
    fun width(s: String, size: Float): Float =
        runCatching { font.getStringWidth(BidiText.visual(s, rtl)) / 1000f * size }.getOrDefault(s.length * size * 0.55f)

    val boxW = p.layoutWidth * w
    val firstLine = p.lines.first()
    // Ligne de base de la premiere ligne : le cadre d'une ligne va de 1,1 hauteur au-dessus a 0,3 dessous.
    val firstBaseline = firstLine.bottom * h - (firstLine.bottom - firstLine.top) * h * (0.3f / 1.4f)
    val ascent = (firstBaseline - p.rect.top * h).coerceAtLeast(p.fontSize * 0.6f)
    val boxH = max((p.rect.bottom - p.rect.top) * h, p.fontSize * (1f + (p.lines.size - 1) * p.lineSpacing))
    val fitted = TextFitter.fit(text, boxW * 1.02f, boxH, p.fontSize, max(3f, p.fontSize * 0.4f), max(1.1f, p.lineSpacing), ::width)
    val scale = fitted.size / p.fontSize

    cs.beginText()
    cs.setFont(font, fitted.size)
    cs.setNonStrokingColor(red(ink), green(ink), blue(ink))
    var baseline = p.rect.top * h + ascent * scale
    val align = when {
        p.align == ParagraphAlign.Center -> ParagraphAlign.Center
        rtl -> if (p.align == ParagraphAlign.End) ParagraphAlign.Start else ParagraphAlign.End
        else -> p.align
    }
    var lastX = 0f
    var lastY = 0f
    for (line in fitted.lines) {
        val visual = BidiText.visual(line, rtl)
        val lineW = width(line, fitted.size)
        val x = when (align) {
            ParagraphAlign.Start -> p.rect.left * w
            ParagraphAlign.Center -> (p.rect.left + p.rect.right) / 2f * w - lineW / 2f
            ParagraphAlign.End -> p.rect.right * w - lineW
        }
        val y = h - baseline
        cs.newLineAtOffset(x - lastX, y - lastY)
        lastX = x
        lastY = y
        cs.showText(visual)
        baseline += fitted.lineHeight
    }
    cs.endText()
}

private fun red(rgb: Int) = ((rgb shr 16) and 0xFF) / 255f
private fun green(rgb: Int) = ((rgb shr 8) and 0xFF) / 255f
private fun blue(rgb: Int) = (rgb and 0xFF) / 255f
