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
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Modifier une ligne d'un scan (texte reconnu par OCR, invisible, pose sur l'image).
 *
 *  1. La ligne est « redressee » dans une image (elle peut etre penchee) a 200 dpi.
 *  2. On mesure le papier (couleur du fond autour) et l'encre (couleur des lettres), et la
 *     place exacte de l'encre (haut, bas, debut, fin).
 *  3. On cherche la police la plus ressemblante : l'ancien texte est dessine dans chaque police
 *     candidate (Arial, Times, Calibri, Georgia..., normal et gras), a la meme largeur, et compare
 *     pixel par pixel a l'image.
 *  4. Le texte invisible de la ligne est retire, la zone est recouverte de la couleur du papier,
 *     et le nouveau texte est ecrit dans la police trouvee, a la meme taille, dans la meme couleur,
 *     sur la meme ligne de base et avec la meme inclinaison. C'est du vrai texte : il se cherche
 *     et se modifie ensuite comme n'importe quel texte.
 */

private const val SCAN_DPI = 200f

/** Une police candidate : son nom Java (pour comparer) et ce qu'on met dans le PDF. */
private class FontCandidate(val family: String, val style: Int, val standard: Standard14Fonts.FontName?) {
    val awt = Font(family, style, 100)
    val bold get() = style and Font.BOLD != 0
    val italic get() = style and Font.ITALIC != 0
    override fun toString() = "$family${if (bold) " gras" else ""}${if (italic) " italique" else ""}"
}

private val SANS = listOf("Arial", "Helvetica", "Liberation Sans", "Arimo", "Nimbus Sans", "Nimbus Sans L")
private val SERIF = listOf("Times New Roman", "Times", "Liberation Serif", "Tinos", "Nimbus Roman", "Nimbus Roman No9 L")
private val MONO = listOf("Courier New", "Courier", "Liberation Mono", "Cousine", "Nimbus Mono PS")
private val OTHERS = listOf(
    "Calibri", "Cambria", "Georgia", "Verdana", "Tahoma", "Segoe UI", "Trebuchet MS", "Garamond",
    "Century Gothic", "Book Antiqua", "Palatino Linotype", "Candara", "Constantia", "Corbel",
    "Franklin Gothic Medium", "Lucida Sans", "DejaVu Sans", "DejaVu Serif",
)

private fun standardFor(group: List<String>, base: Standard14Fonts.FontName, bold: Standard14Fonts.FontName, italic: Standard14Fonts.FontName, boldItalic: Standard14Fonts.FontName) =
    { style: Int -> when (style) { Font.BOLD -> bold; Font.ITALIC -> italic; Font.BOLD or Font.ITALIC -> boldItalic; else -> base } }

private val candidates: List<FontCandidate> by lazy {
    val installed = runCatching { GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet() }.getOrDefault(emptySet())
    val list = ArrayList<FontCandidate>()
    val styles = listOf(Font.PLAIN, Font.BOLD, Font.ITALIC)
    fun addGroup(names: List<String>, std: (Int) -> Standard14Fonts.FontName, logical: String) {
        val family = names.firstOrNull { it in installed } ?: logical
        for (s in styles) list.add(FontCandidate(family, s, std(s)))
    }
    addGroup(SANS, standardFor(SANS, Standard14Fonts.FontName.HELVETICA, Standard14Fonts.FontName.HELVETICA_BOLD, Standard14Fonts.FontName.HELVETICA_OBLIQUE, Standard14Fonts.FontName.HELVETICA_BOLD_OBLIQUE), Font.SANS_SERIF)
    addGroup(SERIF, standardFor(SERIF, Standard14Fonts.FontName.TIMES_ROMAN, Standard14Fonts.FontName.TIMES_BOLD, Standard14Fonts.FontName.TIMES_ITALIC, Standard14Fonts.FontName.TIMES_BOLD_ITALIC), Font.SERIF)
    addGroup(MONO, standardFor(MONO, Standard14Fonts.FontName.COURIER, Standard14Fonts.FontName.COURIER_BOLD, Standard14Fonts.FontName.COURIER_OBLIQUE, Standard14Fonts.FontName.COURIER_BOLD_OBLIQUE), Font.MONOSPACED)
    for (name in OTHERS) if (name in installed) {
        list.add(FontCandidate(name, Font.PLAIN, null))
        list.add(FontCandidate(name, Font.BOLD, null))
    }
    list
}

/** Ce que l'image dit de la ligne : couleurs, encre, police. */
internal class ScanLineStyle(
    val background: Color,
    val ink: Color,
    /** Debut et fin de l'encre le long de la ligne, haut et bas autour de la ligne de base (points). */
    val inkStart: Float,
    val inkEnd: Float,
    val inkTop: Float,
    val inkBottom: Float,
    val fontName: String,
    val fontStyle: Int,
    val standard: Standard14Fonts.FontName?,
    /** Taille des lettres, en points. */
    val size: Float,
    /** Decalage entre l'origine du texte et le bord gauche de l'encre (points). */
    val leftBearing: Float,
    val score: Float,
    /** Morceau de papier reconstitue pour recouvrir l'encre, et sa place (u0..u1, v0..v1, points). */
    val patch: BufferedImage,
    val patchU0: Float,
    val patchU1: Float,
    val patchV0: Float,
    val patchV1: Float,
)

/** La ligne redressee : chaque pixel (i, j) de [image] vient du point (u, v) de la ligne. */
private class StraightLine(val image: BufferedImage, val k: Float, val padLeft: Float, val above: Float) {
    fun u(i: Int) = i / k - padLeft
    fun v(j: Int) = j / k - above
    fun col(u: Float) = ((u + padLeft) * k).roundToInt()
    fun row(v: Float) = ((v + above) * k).roundToInt()
}

private fun luminance(rgb: Int): Int = ((rgb shr 16 and 0xFF) * 299 + (rgb shr 8 and 0xFF) * 587 + (rgb and 0xFF) * 114) / 1000

private fun colorDistance(a: Int, b: Int): Int =
    abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) + abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) + abs((a and 0xFF) - (b and 0xFF))

private fun median(values: List<Int>): Int = values.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }

private fun medianColor(pixels: List<Int>): Int =
    (median(pixels.map { it shr 16 and 0xFF }) shl 16) or (median(pixels.map { it shr 8 and 0xFF }) shl 8) or median(pixels.map { it and 0xFF })

/**
 * Analyse la ligne de l'image. [originX], [originY] : debut de la ligne de base (points, y depuis
 * le haut) ; [angle] : inclinaison (radians, sens inverse des aiguilles d'une montre) ; [length] :
 * longueur de la ligne ; [size] : taille estimee par l'OCR ; [oldText] : ce que l'OCR a lu.
 */
internal fun analyzeScanLine(
    page: BufferedImage,
    k: Float,
    originX: Float,
    originY: Float,
    angle: Float,
    length: Float,
    size: Float,
    oldText: String,
): ScanLineStyle {
    val padLeft = size * 0.6f
    val padRight = size * 0.6f
    val above = size * 1.05f
    val below = size * 0.45f
    val w = max(4, ceil((length + padLeft + padRight) * k).toInt())
    val h = max(4, ceil((above + below) * k).toInt())
    val dirX = cos(angle)
    val dirY = -sin(angle) // image : y vers le bas
    val nX = -dirY
    val nY = dirX
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    for (j in 0 until h) for (i in 0 until w) {
        val u = i / k - padLeft
        val v = j / k - above
        val x = ((originX + u * dirX + v * nX) * k).roundToInt().coerceIn(0, page.width - 1)
        val y = ((originY + u * dirY + v * nY) * k).roundToInt().coerceIn(0, page.height - 1)
        img.setRGB(i, j, page.getRGB(x, y))
    }
    val line = StraightLine(img, k, padLeft, above)

    // Le papier : le pourtour ; l'encre : ce qui s'en eloigne le plus, au coeur de la ligne.
    val border = ArrayList<Int>()
    for (i in 0 until w) { border.add(img.getRGB(i, 0)); border.add(img.getRGB(i, h - 1)) }
    for (j in 0 until h) { border.add(img.getRGB(0, j)); border.add(img.getRGB(w - 1, j)) }
    val bg = medianColor(border)
    val core = ArrayList<Int>()
    for (j in line.row(-size * 0.7f).coerceAtLeast(0) until line.row(size * 0.1f).coerceAtMost(h)) {
        for (i in line.col(0f).coerceAtLeast(0) until line.col(length).coerceAtMost(w)) core.add(img.getRGB(i, j))
    }
    val sorted = core.sortedByDescending { colorDistance(it, bg) }
    val inkSamples = sorted.take(max(1, sorted.size / 12))
    val ink = medianColor(inkSamples)
    val contrast = max(30, colorDistance(ink, bg))
    val isInk = Array(h) { j -> BooleanArray(w) { i -> colorDistance(img.getRGB(i, j), bg) > contrast * 0.45f } }

    // Haut et bas de l'encre : on part de la ligne de base et on s'arrete au premier vrai trou
    // (sinon on attraperait les jambages de la ligne du dessus).
    val c0 = line.col(-size * 0.15f).coerceAtLeast(0)
    val c1 = line.col(length + size * 0.15f).coerceAtMost(w - 1)
    fun rowHasInk(j: Int) = (c0..c1).any { isInk[j][it] }
    val gap = max(2, (size * 0.1f * k).roundToInt())
    val baseRow = line.row(0f).coerceIn(0, h - 1)
    var top = baseRow
    var empty = 0
    var j = baseRow
    while (j > 0) {
        j--
        if (rowHasInk(j)) { top = j; empty = 0 } else if (++empty > gap) break
    }
    var bottom = baseRow
    empty = 0
    j = baseRow
    while (j < h - 1) {
        j++
        if (rowHasInk(j)) { bottom = j; empty = 0 } else if (++empty > gap) break
    }
    var first = -1
    var last = -1
    for (i in c0..c1) if ((top..bottom).any { isInk[it][i] }) { if (first < 0) first = i; last = i }
    if (first < 0) { first = line.col(0f); last = line.col(length) }
    val inkWidthPx = (last - first + 1).toFloat()

    // La police : l'ancien texte, a la meme largeur, compare a l'image.
    val frc = FontRenderContext(null, true, true)
    var best: Pair<FontCandidate, Float>? = null
    var bestScore = -1f
    var bestBearing = 0f
    val text = oldText.ifBlank { "x" }
    for (c in candidates) {
        val bounds = runCatching { c.awt.createGlyphVector(frc, text).visualBounds }.getOrNull() ?: continue
        if (bounds.width <= 0.0) continue
        val sizePx = (100.0 * inkWidthPx / bounds.width).toFloat()
        val ratio = sizePx / (size * k)
        if (ratio < 0.6f || ratio > 1.6f) continue
        val font = c.awt.deriveFont(sizePx)
        val gv = font.createGlyphVector(frc, text)
        val vb = gv.visualBounds
        val render = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
        val g = render.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g.color = Color.WHITE
        val ox = (first - vb.x).toFloat()
        g.drawGlyphVector(gv, ox, baseRow.toFloat())
        g.dispose()
        val raster = render.raster
        fun drawn(i: Int, jj: Int) = i in 0 until w && jj in 0 until h && raster.getSample(i, jj, 0) > 100
        fun near(i: Int, jj: Int, f: (Int, Int) -> Boolean) = f(i, jj) || f(i - 1, jj) || f(i + 1, jj) || f(i, jj - 1) || f(i, jj + 1)
        val origInk = { i: Int, jj: Int -> i in 0 until w && jj in 0 until h && jj in top..bottom && isInk[jj][i] }
        var renderCount = 0; var renderHit = 0; var origCount = 0; var origHit = 0
        for (jj in 0 until h) for (i in c0..c1) {
            val r = drawn(i, jj)
            val o = origInk(i, jj)
            if (r) { renderCount++; if (near(i, jj, origInk)) renderHit++ }
            if (o) { origCount++; if (near(i, jj, ::drawn)) origHit++ }
        }
        if (renderCount == 0 || origCount == 0) continue
        val precision = renderHit.toFloat() / renderCount
        val recall = origHit.toFloat() / origCount
        val score = if (precision + recall == 0f) 0f else 2 * precision * recall / (precision + recall)
        if (score > bestScore) {
            bestScore = score
            best = c to sizePx
            bestBearing = vb.x.toFloat()
        }
    }
    val (font, sizePx) = best ?: (candidates.first() to size * k)

    // Le papier sous l'encre : pour chaque colonne, degrade entre le papier juste au-dessus et
    // juste au-dessous, plus le meme grain que le papier autour (un aplat se verrait).
    val margin = max(2, (size * 0.06f * k).roundToInt() + 1)
    val pc0 = (first - margin).coerceAtLeast(0)
    val pc1 = (last + margin).coerceAtMost(w - 1)
    val pr0 = (top - margin).coerceAtLeast(0)
    val pr1 = (bottom + margin).coerceAtMost(h - 1)
    val paperDev = border.filter { colorDistance(it, bg) < contrast * 0.45f }
        .map { (luminance(it) - luminance(bg)).toDouble() }
    val sigma = if (paperDev.size > 4) kotlin.math.sqrt(paperDev.sumOf { it * it } / paperDev.size) else 0.0
    val random = java.util.Random(31L * w + h)
    // Couleur du papier au-dessus et au-dessous, colonne par colonne, puis lissee sur une
    // trentaine de pixels : pas de rayures, mais les degrades du scan (ombre, jaunissement) suivent.
    fun paperRow(rows: IntRange): IntArray {
        val sums = Array(w) { DoubleArray(4) } // r, g, b, nombre
        for (i in 0 until w) for (jj in rows) {
            if (jj !in 0 until h) continue
            val p = img.getRGB(i, jj)
            if (colorDistance(p, bg) > contrast * 0.25f) continue
            sums[i][0] += (p shr 16 and 0xFF).toDouble(); sums[i][1] += (p shr 8 and 0xFF).toDouble()
            sums[i][2] += (p and 0xFF).toDouble(); sums[i][3] += 1.0
        }
        val radius = max(8, (size * 0.6f * k).roundToInt())
        return IntArray(w) { i ->
            var r = 0.0; var g = 0.0; var b = 0.0; var n = 0.0
            for (x in (i - radius)..(i + radius)) {
                if (x !in 0 until w) continue
                r += sums[x][0]; g += sums[x][1]; b += sums[x][2]; n += sums[x][3]
            }
            if (n < 1.0) bg else (((r / n).roundToInt() shl 16) or ((g / n).roundToInt() shl 8) or (b / n).roundToInt())
        }
    }
    val aboveRow = paperRow((pr0 - 6) until pr0)
    val belowRow = paperRow((pr1 + 1)..(pr1 + 6))
    val patch = BufferedImage(pc1 - pc0 + 1, pr1 - pr0 + 1, BufferedImage.TYPE_INT_RGB)
    for (i in pc0..pc1) {
        val above = aboveRow[i]
        val below = belowRow[i]
        for (jj in pr0..pr1) {
            val t = if (pr1 == pr0) 0.0 else (jj - pr0).toDouble() / (pr1 - pr0)
            val n = random.nextGaussian() * sigma
            fun mix(shift: Int): Int {
                val a = above shr shift and 0xFF
                val b = below shr shift and 0xFF
                return (a + (b - a) * t + n).roundToInt().coerceIn(0, 255)
            }
            patch.setRGB(i - pc0, jj - pr0, (mix(16) shl 16) or (mix(8) shl 8) or mix(0))
        }
    }

    return ScanLineStyle(
        background = Color(bg),
        ink = Color(ink),
        inkStart = line.u(first),
        inkEnd = line.u(last + 1),
        inkTop = -line.v(top),
        inkBottom = line.v(bottom + 1),
        fontName = font.family,
        fontStyle = font.style,
        standard = font.standard,
        size = sizePx / k,
        leftBearing = bestBearing / k,
        score = bestScore,
        patch = patch,
        patchU0 = line.u(pc0),
        patchU1 = line.u(pc1 + 1),
        patchV0 = line.v(pr0),
        patchV1 = line.v(pr1 + 1),
    )
}

/** La police PDF qui correspond a la police trouvee (standard, ou installee et embarquee). */
private fun pdfFont(doc: PDDocument, style: ScanLineStyle, text: String): PDFont {
    fun encodable(f: PDFont) = runCatching { f.encode(text); true }.getOrDefault(false)
    style.standard?.let { std ->
        val f = PDType1Font(std)
        if (encodable(f)) return f
    }
    val ps = Font(style.fontName, style.fontStyle, 12).psName
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
    return PDType1Font(style.standard ?: Standard14Fonts.FontName.HELVETICA)
}

private fun safeText(font: PDFont, text: String): String = buildString {
    for (ch in text) append(if (runCatching { font.encode(ch.toString()) }.isSuccess) ch else '?')
}

/** Remplace une ligne de scan (voir le debut du fichier). */
internal fun rewriteScannedLine(doc: PDDocument, pageIndex: Int, analysis: PageTextAnalysis, target: TextBlock, newText: String) {
    val keys = target.glyphs.toSet()
    val glyphs = analysis.glyphs.filter { it.key in keys }
    if (glyphs.isEmpty()) throw TextEditException(app.nougat.pdf.i18n.Str.ErrTextNotFound)
    val first = glyphs.first()
    val last = glyphs.last()
    val dx = last.originX - first.originX
    val dy = last.originY - first.originY
    val span = hypot(dx, dy)
    val angle = if (span > 4f) atan2(-dy, dx) else 0f
    val size = glyphs.map { it.size }.sorted()[glyphs.size / 2]
    val lastWidth = (last.box.right - last.box.left)
    val page = doc.getPage(pageIndex)
    val box = page.cropBox
    val rotated = page.rotation % 180 != 0
    val pageW = if (rotated) box.height else box.width
    val pageH = if (rotated) box.width else box.height
    val length = span + lastWidth * pageW

    val image = PDFRenderer(doc).renderImageWithDPI(pageIndex, SCAN_DPI, ImageType.RGB)
    val k = image.width / pageW
    val style = analyzeScanLine(image, k, first.originX, first.originY, angle, length, size, target.text)

    // 1. Le texte invisible de la ligne disparait.
    val plan = TextEditPlanner.plan(analysis, target, Replacement.SameFont(emptyList(), 0f))
    rewriteContent(doc, page, plan)

    // 2. Le papier recouvre l'ancienne encre ; 3. le nouveau texte.
    val geometry = PageGeometry(box.lowerLeftX, box.lowerLeftY, box.width, box.height, normalizeRotation(page.rotation))
    val m = geometry.displayToPdfMatrix()
    val c = cos(angle)
    val s = sin(angle)
    // Point (u le long de la ligne, v vers le bas) -> page affichee, origine en bas a gauche.
    fun point(u: Float, v: Float): Pair<Float, Float> {
        val x = first.originX + u * c + v * s
        val yTop = first.originY - u * s + v * c
        return x to (pageH - yTop)
    }
    val patch = JPEGFactory.createFromImage(doc, style.patch, 0.92f)
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
            cs.setNonStrokingColor(style.ink)
            cs.setTextMatrix(Matrix(c, s, -s, c, x, y))
            cs.showText(safe)
            cs.endText()
        }
    }
}

@Suppress("unused")
private fun clampInt(v: Int, lo: Int, hi: Int) = min(hi, max(lo, v))
