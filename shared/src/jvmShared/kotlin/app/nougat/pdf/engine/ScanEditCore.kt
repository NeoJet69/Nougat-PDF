package app.nougat.pdf.engine

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Modifier une ligne d'un scan (texte reconnu par OCR, invisible, pose sur l'image) : la partie
 * commune a l'ordinateur et a Android. Seuls le rendu de la page, le dessin des polices candidates
 * et l'ecriture dans le PDF dependent de la plateforme.
 *
 *  1. La ligne est « redressee » dans une image (elle peut etre penchee) a 200 dpi.
 *  2. On mesure le papier (couleur du fond autour) et l'encre (couleur des lettres), et la
 *     place exacte de l'encre (haut, bas, debut, fin).
 *  3. On cherche la police la plus ressemblante : l'ancien texte est dessine dans chaque police
 *     candidate, a la meme largeur, et compare pixel par pixel a l'image.
 *  4. Un morceau de papier est reconstitue (teinte et grain du scan) pour recouvrir l'ancienne
 *     encre ; le nouveau texte est ensuite ecrit dans la police trouvee, a la meme taille, dans la
 *     meme couleur, sur la meme ligne de base et avec la meme inclinaison.
 */

internal const val SCAN_EDIT_DPI = 200f

/** Image en couleurs : 0xRRGGBB par pixel, ligne apres ligne. */
internal class RgbImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width > 0 && height > 0 && pixels.size >= width * height) { "Image vide" }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x] and 0xFFFFFF
}

/** Famille des polices standard du PDF (Helvetica, Times, Courier). */
internal enum class StandardFamily { Sans, Serif, Mono }

/** Une police candidate : [name] pour la plateforme, et la police standard du PDF qui lui ressemble. */
internal class ScanFont(val name: String, val bold: Boolean, val italic: Boolean, val standard: StandardFamily?) {
    override fun toString() = "$name${if (bold) " gras" else ""}${if (italic) " italique" else ""}"
}

/** Dessine les polices candidates (Java2D sur ordinateur, Canvas sur Android). */
internal interface GlyphRasterizer {
    val candidates: List<ScanFont>

    /** Cadre de l'encre de [text] a la taille [sizePx] : gauche, haut, droite, bas (origine sur la ligne de base). */
    fun bounds(font: ScanFont, text: String, sizePx: Float): FloatArray?

    /** [text] dessine dans une image [w] x [h] (vrai : encre), origine en ([x], [baseline]). */
    fun render(font: ScanFont, text: String, sizePx: Float, x: Float, baseline: Float, w: Int, h: Int): BooleanArray
}

/** Ce que l'image dit de la ligne : couleurs, encre, police. */
internal class ScanLineStyle(
    val background: Int,
    val ink: Int,
    /** Debut et fin de l'encre le long de la ligne, haut et bas autour de la ligne de base (points). */
    val inkStart: Float,
    val inkEnd: Float,
    val inkTop: Float,
    val inkBottom: Float,
    val font: ScanFont,
    /** Taille des lettres, en points. */
    val size: Float,
    /** Decalage entre l'origine du texte et le bord gauche de l'encre (points). */
    val leftBearing: Float,
    val score: Float,
    /** Morceau de papier reconstitue pour recouvrir l'encre, et sa place (u0..u1, v0..v1, points). */
    val patch: RgbImage,
    val patchU0: Float,
    val patchU1: Float,
    val patchV0: Float,
    val patchV1: Float,
)

private fun luminance(rgb: Int): Int = ((rgb shr 16 and 0xFF) * 299 + (rgb shr 8 and 0xFF) * 587 + (rgb and 0xFF) * 114) / 1000

private fun colorDistance(a: Int, b: Int): Int =
    abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) + abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) + abs((a and 0xFF) - (b and 0xFF))

private fun median(values: List<Int>): Int = values.sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }

private fun medianColor(pixels: List<Int>): Int =
    (median(pixels.map { it shr 16 and 0xFF }) shl 16) or (median(pixels.map { it shr 8 and 0xFF }) shl 8) or median(pixels.map { it and 0xFF })

/**
 * Analyse la ligne de l'image [page] ([k] pixels par point). [originX], [originY] : debut de la
 * ligne de base (points, y depuis le haut) ; [angle] : inclinaison (radians, sens inverse des
 * aiguilles d'une montre) ; [length] : longueur de la ligne ; [size] : taille estimee par l'OCR ;
 * [oldText] : ce que l'OCR a lu.
 */
internal fun analyzeScanLine(
    page: RgbImage,
    k: Float,
    originX: Float,
    originY: Float,
    angle: Float,
    length: Float,
    size: Float,
    oldText: String,
    rasterizer: GlyphRasterizer,
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
    val px = IntArray(w * h)
    for (j in 0 until h) for (i in 0 until w) {
        val u = i / k - padLeft
        val v = j / k - above
        val x = ((originX + u * dirX + v * nX) * k).roundToInt().coerceIn(0, page.width - 1)
        val y = ((originY + u * dirY + v * nY) * k).roundToInt().coerceIn(0, page.height - 1)
        px[j * w + i] = page[x, y]
    }
    fun at(i: Int, j: Int) = px[j * w + i]
    fun u(i: Int) = i / k - padLeft
    fun v(j: Int) = j / k - above
    fun col(uu: Float) = ((uu + padLeft) * k).roundToInt()
    fun row(vv: Float) = ((vv + above) * k).roundToInt()

    // Le papier : le pourtour ; l'encre : ce qui s'en eloigne le plus, au coeur de la ligne.
    val border = ArrayList<Int>()
    for (i in 0 until w) { border.add(at(i, 0)); border.add(at(i, h - 1)) }
    for (j in 0 until h) { border.add(at(0, j)); border.add(at(w - 1, j)) }
    val bg = medianColor(border)
    val core = ArrayList<Int>()
    for (j in row(-size * 0.7f).coerceAtLeast(0) until row(size * 0.1f).coerceAtMost(h)) {
        for (i in col(0f).coerceAtLeast(0) until col(length).coerceAtMost(w)) core.add(at(i, j))
    }
    val sorted = core.sortedByDescending { colorDistance(it, bg) }
    val ink = medianColor(sorted.take(max(1, sorted.size / 12)))
    val contrast = max(30, colorDistance(ink, bg))
    val isInk = Array(h) { j -> BooleanArray(w) { i -> colorDistance(at(i, j), bg) > contrast * 0.45f } }

    // Haut et bas de l'encre : on part de la ligne de base et on s'arrete au premier vrai trou
    // (sinon on attraperait les jambages de la ligne du dessus).
    val c0 = col(-size * 0.15f).coerceAtLeast(0)
    val c1 = col(length + size * 0.15f).coerceAtMost(w - 1)
    fun rowHasInk(j: Int) = (c0..c1).any { isInk[j][it] }
    val gap = max(2, (size * 0.1f * k).roundToInt())
    val baseRow = row(0f).coerceIn(0, h - 1)
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
    if (first < 0) { first = col(0f); last = col(length) }
    val inkWidthPx = (last - first + 1).toFloat()

    // La police : l'ancien texte, a la meme largeur, compare a l'image.
    var best: ScanFont? = null
    var bestSize = size * k
    var bestScore = -1f
    var bestBearing = 0f
    val text = oldText.ifBlank { "x" }
    for (c in rasterizer.candidates) {
        val b100 = runCatching { rasterizer.bounds(c, text, 100f) }.getOrNull() ?: continue
        val width100 = b100[2] - b100[0]
        if (width100 <= 0f) continue
        val sizePx = 100f * inkWidthPx / width100
        val ratio = sizePx / (size * k)
        if (ratio < 0.6f || ratio > 1.6f) continue
        val vb = runCatching { rasterizer.bounds(c, text, sizePx) }.getOrNull() ?: continue
        val mask = runCatching { rasterizer.render(c, text, sizePx, first - vb[0], baseRow.toFloat(), w, h) }.getOrNull() ?: continue
        fun drawn(i: Int, jj: Int) = i in 0 until w && jj in 0 until h && mask[jj * w + i]
        fun origInk(i: Int, jj: Int) = i in 0 until w && jj in 0 until h && jj in top..bottom && isInk[jj][i]
        fun near(i: Int, jj: Int, f: (Int, Int) -> Boolean) = f(i, jj) || f(i - 1, jj) || f(i + 1, jj) || f(i, jj - 1) || f(i, jj + 1)
        var renderCount = 0; var renderHit = 0; var origCount = 0; var origHit = 0
        for (jj in 0 until h) for (i in c0..c1) {
            val r = drawn(i, jj)
            val o = origInk(i, jj)
            if (r) { renderCount++; if (near(i, jj, ::origInk)) renderHit++ }
            if (o) { origCount++; if (near(i, jj, ::drawn)) origHit++ }
        }
        if (renderCount == 0 || origCount == 0) continue
        val precision = renderHit.toFloat() / renderCount
        val recall = origHit.toFloat() / origCount
        val score = if (precision + recall == 0f) 0f else 2 * precision * recall / (precision + recall)
        if (score > bestScore) {
            bestScore = score
            best = c
            bestSize = sizePx
            bestBearing = vb[0]
        }
    }
    val font = best ?: rasterizer.candidates.first()

    // Le papier sous l'encre : pour chaque colonne, degrade entre le papier juste au-dessus et
    // juste au-dessous, plus le meme grain que le papier autour (un aplat se verrait).
    val margin = max(2, (size * 0.06f * k).roundToInt() + 1)
    val pc0 = (first - margin).coerceAtLeast(0)
    val pc1 = (last + margin).coerceAtMost(w - 1)
    val pr0 = (top - margin).coerceAtLeast(0)
    val pr1 = (bottom + margin).coerceAtMost(h - 1)
    val paperDev = border.filter { colorDistance(it, bg) < contrast * 0.45f }.map { (luminance(it) - luminance(bg)).toDouble() }
    val sigma = if (paperDev.size > 4) kotlin.math.sqrt(paperDev.sumOf { it * it } / paperDev.size) else 0.0
    val random = java.util.Random(31L * w + h)
    // Couleur du papier au-dessus et au-dessous, colonne par colonne, puis lissee sur une
    // trentaine de pixels : pas de rayures, mais les degrades du scan (ombre, jaunissement) suivent.
    fun paperRow(rows: IntRange): IntArray {
        val sums = Array(w) { DoubleArray(4) } // r, g, b, nombre
        for (i in 0 until w) for (jj in rows) {
            if (jj !in 0 until h) continue
            val p = at(i, jj)
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
    val pw = pc1 - pc0 + 1
    val ph = pr1 - pr0 + 1
    val patch = IntArray(pw * ph)
    for (i in pc0..pc1) {
        val a = aboveRow[i]
        val b = belowRow[i]
        for (jj in pr0..pr1) {
            val t = if (pr1 == pr0) 0.0 else (jj - pr0).toDouble() / (pr1 - pr0)
            val n = random.nextGaussian() * sigma
            fun mix(shift: Int): Int {
                val ca = a shr shift and 0xFF
                val cb = b shr shift and 0xFF
                return (ca + (cb - ca) * t + n).roundToInt().coerceIn(0, 255)
            }
            patch[(jj - pr0) * pw + (i - pc0)] = (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
        }
    }

    return ScanLineStyle(
        background = bg,
        ink = ink,
        inkStart = u(first),
        inkEnd = u(last + 1),
        inkTop = -v(top),
        inkBottom = v(bottom + 1),
        font = font,
        size = bestSize / k,
        leftBearing = bestBearing / k,
        score = bestScore,
        patch = RgbImage(pw, ph, patch),
        patchU0 = u(pc0),
        patchU1 = u(pc1 + 1),
        patchV0 = v(pr0),
        patchV1 = v(pr1 + 1),
    )
}

/** Ou poser le papier et le nouveau texte d'une ligne scannee, dans la page affichee. */
internal class ScanLinePlacement(
    /** Origine de la ligne de base (points, y depuis le haut) et inclinaison. */
    val originX: Float,
    val originY: Float,
    val angle: Float,
    val length: Float,
    val size: Float,
)

/**
 * La ligne a reecrire, d'apres ses lettres invisibles ([glyphs], dans l'ordre) : origine,
 * inclinaison, longueur et taille. [pageWidth] : largeur de la page affichee (points).
 */
internal fun scanLinePlacement(glyphs: List<AnalyzedGlyph>, pageWidth: Float): ScanLinePlacement {
    val first = glyphs.first()
    val last = glyphs.last()
    val dx = last.originX - first.originX
    val dy = last.originY - first.originY
    val span = kotlin.math.hypot(dx, dy)
    val angle = if (span > 4f) kotlin.math.atan2(-dy, dx) else 0f
    val size = glyphs.map { it.size }.sorted()[glyphs.size / 2]
    val lastWidth = (last.box.right - last.box.left) * pageWidth
    return ScanLinePlacement(first.originX, first.originY, angle, span + lastWidth, size)
}
