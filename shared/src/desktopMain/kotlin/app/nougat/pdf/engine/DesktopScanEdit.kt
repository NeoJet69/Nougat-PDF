package app.nougat.pdf.engine

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.FontMappers
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.util.Matrix
import java.awt.Color
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.image.BufferedImage
import kotlin.math.cos
import kotlin.math.sin

/*
 * Modifier une ligne d'un scan, version ordinateur : rendu par PDFBox, polices candidates dessinees
 * par Java2D (toutes celles installees sur l'ordinateur), ecriture par PDFBox. L'analyse de l'image
 * (papier, encre, police la plus ressemblante, papier reconstitue) est commune : ScanEditCore.kt.
 */

private val SANS = listOf("Arial", "Helvetica", "Liberation Sans", "Arimo", "Nimbus Sans", "Nimbus Sans L")
private val SERIF = listOf("Times New Roman", "Times", "Liberation Serif", "Tinos", "Nimbus Roman", "Nimbus Roman No9 L")
private val MONO = listOf("Courier New", "Courier", "Liberation Mono", "Cousine", "Nimbus Mono PS")
private val OTHERS = listOf(
    "Calibri", "Cambria", "Georgia", "Verdana", "Tahoma", "Segoe UI", "Trebuchet MS", "Garamond",
    "Century Gothic", "Book Antiqua", "Palatino Linotype", "Candara", "Constantia", "Corbel",
    "Franklin Gothic Medium", "Lucida Sans", "DejaVu Sans", "DejaVu Serif",
)

/** Les polices de l'ordinateur, dessinees par Java2D. */
private object Java2DRasterizer : GlyphRasterizer {
    private val frc = FontRenderContext(null, true, true)

    override val candidates: List<ScanFont> by lazy {
        val installed = runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet() }.getOrDefault(emptySet())
        val list = ArrayList<ScanFont>()
        fun addGroup(names: List<String>, std: StandardFamily, logical: String) {
            val family = names.firstOrNull { it in installed } ?: logical
            list.add(ScanFont(family, bold = false, italic = false, standard = std))
            list.add(ScanFont(family, bold = true, italic = false, standard = std))
            list.add(ScanFont(family, bold = false, italic = true, standard = std))
        }
        addGroup(SANS, StandardFamily.Sans, Font.SANS_SERIF)
        addGroup(SERIF, StandardFamily.Serif, Font.SERIF)
        addGroup(MONO, StandardFamily.Mono, Font.MONOSPACED)
        for (name in OTHERS) if (name in installed) {
            list.add(ScanFont(name, bold = false, italic = false, standard = null))
            list.add(ScanFont(name, bold = true, italic = false, standard = null))
        }
        list
    }

    fun awt(font: ScanFont, sizePx: Float): Font =
        Font(font.name, (if (font.bold) Font.BOLD else 0) or (if (font.italic) Font.ITALIC else 0), 100).deriveFont(sizePx)

    override fun bounds(font: ScanFont, text: String, sizePx: Float): FloatArray? {
        val b = awt(font, sizePx).createGlyphVector(frc, text).visualBounds
        if (b.width <= 0.0) return null
        return floatArrayOf(b.x.toFloat(), b.y.toFloat(), (b.x + b.width).toFloat(), (b.y + b.height).toFloat())
    }

    override fun render(font: ScanFont, text: String, sizePx: Float, x: Float, baseline: Float, w: Int, h: Int): BooleanArray {
        val gv = awt(font, sizePx).createGlyphVector(frc, text)
        val image = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g.color = Color.WHITE
        g.drawGlyphVector(gv, x, baseline)
        g.dispose()
        val raster = image.raster
        return BooleanArray(w * h) { i -> raster.getSample(i % w, i / w, 0) > 100 }
    }
}

private fun BufferedImage.toRgbImage(): RgbImage = RgbImage(width, height, getRGB(0, 0, width, height, null, 0, width))

private fun RgbImage.toBufferedImage(): BufferedImage =
    BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).also { it.setRGB(0, 0, width, height, pixels, 0, width) }

private fun standardFont(font: ScanFont): Standard14Fonts.FontName? {
    val std = font.standard ?: return null
    return when (std) {
        StandardFamily.Sans -> when {
            font.bold && font.italic -> Standard14Fonts.FontName.HELVETICA_BOLD_OBLIQUE
            font.bold -> Standard14Fonts.FontName.HELVETICA_BOLD
            font.italic -> Standard14Fonts.FontName.HELVETICA_OBLIQUE
            else -> Standard14Fonts.FontName.HELVETICA
        }
        StandardFamily.Serif -> when {
            font.bold && font.italic -> Standard14Fonts.FontName.TIMES_BOLD_ITALIC
            font.bold -> Standard14Fonts.FontName.TIMES_BOLD
            font.italic -> Standard14Fonts.FontName.TIMES_ITALIC
            else -> Standard14Fonts.FontName.TIMES_ROMAN
        }
        StandardFamily.Mono -> when {
            font.bold && font.italic -> Standard14Fonts.FontName.COURIER_BOLD_OBLIQUE
            font.bold -> Standard14Fonts.FontName.COURIER_BOLD
            font.italic -> Standard14Fonts.FontName.COURIER_OBLIQUE
            else -> Standard14Fonts.FontName.COURIER
        }
    }
}

/** La police PDF qui correspond a la police trouvee (standard, ou installee et embarquee). */
private fun pdfFont(doc: PDDocument, style: ScanLineStyle, text: String): PDFont {
    fun encodable(f: PDFont) = runCatching { f.encode(text); true }.getOrDefault(false)
    standardFont(style.font)?.let { std ->
        val f = PDType1Font(std)
        if (encodable(f)) return f
    }
    val ps = Java2DRasterizer.awt(style.font, 12f).psName
    val mapping = runCatching { FontMappers.instance().getTrueTypeFont(ps, null) }.getOrNull()
    if (mapping != null && !mapping.isFallback && mapping.font != null) {
        val f = runCatching { PDType0Font.load(doc, mapping.font, true) }.getOrNull()
        if (f != null && encodable(f)) return f
    }
    // Lettres hors de l'alphabet latin : une police installee qui les a (Arial, DejaVu...).
    for (name in listOf("ArialMT", "Arial", "LiberationSans", "DejaVuSans", "SegoeUI")) {
        val m = runCatching { FontMappers.instance().getTrueTypeFont(name, null) }.getOrNull() ?: continue
        if (m.isFallback || m.font == null) continue
        val f = runCatching { PDType0Font.load(doc, m.font, true) }.getOrNull() ?: continue
        if (encodable(f)) return f
    }
    return PDType1Font(standardFont(style.font) ?: Standard14Fonts.FontName.HELVETICA)
}

private fun safeText(font: PDFont, text: String): String = buildString {
    for (ch in text) append(if (runCatching { font.encode(ch.toString()) }.isSuccess) ch else '?')
}

/** Remplace une ligne de scan (voir ScanEditCore.kt). */
internal fun rewriteScannedLine(doc: PDDocument, pageIndex: Int, analysis: PageTextAnalysis, target: TextBlock, newText: String) {
    val keys = target.glyphs.toSet()
    val glyphs = analysis.glyphs.filter { it.key in keys }
    if (glyphs.isEmpty()) throw TextEditException(app.nougat.pdf.i18n.Str.ErrTextNotFound)
    val page = doc.getPage(pageIndex)
    val box = page.cropBox
    val rotated = page.rotation % 180 != 0
    val pageW = if (rotated) box.height else box.width
    val pageH = if (rotated) box.width else box.height
    val line = scanLinePlacement(glyphs, pageW)

    val image = PDFRenderer(doc).renderImageWithDPI(pageIndex, SCAN_EDIT_DPI, ImageType.RGB)
    val k = image.width / pageW
    val style = analyzeScanLine(image.toRgbImage(), k, line.originX, line.originY, line.angle, line.length, line.size, target.text, Java2DRasterizer)

    // 1. Le texte invisible de la ligne disparait.
    val plan = TextEditPlanner.plan(analysis, target, Replacement.SameFont(emptyList(), 0f))
    rewriteContent(doc, page, plan)

    // 2. Le papier recouvre l'ancienne encre ; 3. le nouveau texte.
    val geometry = PageGeometry(box.lowerLeftX, box.lowerLeftY, box.width, box.height, normalizeRotation(page.rotation))
    val m = geometry.displayToPdfMatrix()
    val c = cos(line.angle)
    val s = sin(line.angle)
    // Point (u le long de la ligne, v vers le bas) -> page affichee, origine en bas a gauche.
    fun point(u: Float, v: Float): Pair<Float, Float> {
        val x = line.originX + u * c + v * s
        val yTop = line.originY - u * s + v * c
        return x to (pageH - yTop)
    }
    val patch = JPEGFactory.createFromImage(doc, style.patch.toBufferedImage(), 0.92f)
    val pw = style.patchU1 - style.patchU0
    val ph = style.patchV1 - style.patchV0
    val (ox, oy) = point(style.patchU0, style.patchV1) // coin bas gauche de l'image
    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
        cs.transform(Matrix(m[0], m[1], m[2], m[3], m[4], m[5]))
        cs.drawImage(patch, Matrix(c * pw, s * pw, -s * ph, c * ph, ox, oy))
        val text = newText.trim()
        if (text.isNotEmpty()) {
            val font = pdfFont(doc, style, text)
            val safe = safeText(font, text)
            val (x, y) = point(style.inkStart - style.leftBearing, 0f)
            cs.beginText()
            cs.setFont(font, style.size)
            cs.setNonStrokingColor(Color(style.ink))
            cs.setTextMatrix(Matrix(c, s, -s, c, x, y))
            cs.showText(safe)
            cs.endText()
        }
    }
}
