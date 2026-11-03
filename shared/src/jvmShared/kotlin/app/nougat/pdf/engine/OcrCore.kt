package app.nougat.pdf.engine

import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Le cœur de l'OCR, commun a l'ordinateur et a Android : seuls le moteur Tesseract (bibliotheque
 * native differente), le rendu des pages et l'ecriture dans le PDF (PDFBox / PdfBox-Android)
 * changent d'une plateforme a l'autre.
 *
 * Pour chaque page : rendu en niveaux de gris a 300 dpi, eclairage corrige, reconnaissance ; si
 * le resultat est mauvais, essai des 3 autres sens (page couchee ou a l'envers). Le texte reconnu
 * est ajoute en texte invisible : chaque mot a sa place exacte sur l'image, le long de la ligne
 * de base reelle (lignes penchees comprises) et a sa largeur reelle. Le calque est encadre par
 * « /NougatOCR BMC ... EMC » : on peut le retrouver et le refaire.
 */

// ---- Image en niveaux de gris ----------------------------------------------------------------------

/** Image en niveaux de gris : un octet par pixel, ligne apres ligne, origine en haut a gauche. */
internal class GrayImage(val width: Int, val height: Int, val pixels: ByteArray) {
    init {
        require(width > 0 && height > 0 && pixels.size >= width * height) { "Image vide" }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF

    /** Tournee de [quarter] quarts de tour dans le sens des aiguilles d'une montre. */
    fun rotateQuarter(quarter: Int): GrayImage {
        val q = ((quarter % 4) + 4) % 4
        if (q == 0) return this
        val w = width
        val h = height
        val ow = if (q == 2) w else h
        val oh = if (q == 2) h else w
        val out = ByteArray(ow * oh)
        for (y in 0 until oh) for (x in 0 until ow) {
            val (sx, sy) = when (q) {
                1 -> y to h - 1 - x
                2 -> w - 1 - x to h - 1 - y
                else -> w - 1 - y to x
            }
            out[y * ow + x] = pixels[sy * w + sx]
        }
        return GrayImage(ow, oh, out)
    }

    /** Reduite (ou agrandie) de [factor], par interpolation bilineaire. */
    fun scale(factor: Float): GrayImage {
        val ow = max(1, (width * factor).roundToInt())
        val oh = max(1, (height * factor).roundToInt())
        val out = ByteArray(ow * oh)
        val kx = width.toFloat() / ow
        val ky = height.toFloat() / oh
        for (y in 0 until oh) {
            val fy = ((y + 0.5f) * ky - 0.5f).coerceIn(0f, height - 1f)
            val y0 = fy.toInt()
            val y1 = min(height - 1, y0 + 1)
            val ty = fy - y0
            for (x in 0 until ow) {
                val fx = ((x + 0.5f) * kx - 0.5f).coerceIn(0f, width - 1f)
                val x0 = fx.toInt()
                val x1 = min(width - 1, x0 + 1)
                val tx = fx - x0
                val v = (this[x0, y0] * (1 - tx) + this[x1, y0] * tx) * (1 - ty) + (this[x0, y1] * (1 - tx) + this[x1, y1] * tx) * ty
                out[y * ow + x] = v.roundToInt().coerceIn(0, 255).toByte()
            }
        }
        return GrayImage(ow, oh, out)
    }

    /** Le rectangle [left, right[ x [top, bottom[ (borne a l'image). */
    fun crop(left: Int, top: Int, right: Int, bottom: Int): GrayImage {
        val l = left.coerceIn(0, width - 1)
        val t = top.coerceIn(0, height - 1)
        val r = right.coerceIn(l + 1, width)
        val b = bottom.coerceIn(t + 1, height)
        val ow = r - l
        val out = ByteArray(ow * (b - t))
        for (y in t until b) System.arraycopy(pixels, y * width + l, out, (y - t) * ow, ow)
        return GrayImage(ow, b - t, out)
    }

    /**
     * Eclairage corrige (scan ou photo) : ombre de la main ou du telephone, coin plus sombre,
     * papier jauni. On estime la teinte du papier case par case (les pixels les plus clairs, pour
     * ignorer l'encre), on la lisse, puis on divise chaque pixel par elle : le papier devient blanc
     * partout et l'encre garde son contraste. Sur une photo avec une ombre, Tesseract passe ainsi de
     * rien (ou presque) a quasiment tout. Les zones sombres (bandeau colore, photo) ne sont pas du
     * papier : on n'y touche pas, le texte clair sur fond fonce reste lisible.
     */
    fun evenLighting(): GrayImage {
        val w = width
        val h = height
        val cell = max(8, min(w, h) / 60)
        val gw = (w + cell - 1) / cell
        val gh = (h + cell - 1) / cell
        val paper = FloatArray(gw * gh)
        val hist = IntArray(256)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            hist.fill(0)
            var n = 0
            for (y in gy * cell until min(h, (gy + 1) * cell)) for (x in gx * cell until min(w, (gx + 1) * cell)) {
                hist[pixels[y * w + x].toInt() and 0xFF]++
                n++
            }
            // Le 90e centile : le papier, meme dans une case pleine de texte.
            var seen = 0
            var v = 255
            while (v > 0) {
                seen += hist[v]
                if (seen >= n / 10) break
                v--
            }
            paper[gy * gw + gx] = v.toFloat()
        }
        val sorted = paper.sortedArray()
        val pageLevel = sorted[min(sorted.size - 1, (sorted.size * 0.9).toInt())]
        if (pageLevel < 40f) return this // page presque noire : rien a corriger
        for (i in paper.indices) if (paper[i] < pageLevel * 0.4f) paper[i] = pageLevel
        // Lisser (le papier varie doucement) ; garder le plus clair evite d'assombrir le bord des lettres.
        repeat(2) {
            val copy = paper.copyOf()
            for (gy in 0 until gh) for (gx in 0 until gw) {
                var sum = 0f
                var k = 0
                for (yy in max(0, gy - 1)..min(gh - 1, gy + 1)) for (xx in max(0, gx - 1)..min(gw - 1, gx + 1)) {
                    sum += copy[yy * gw + xx]
                    k++
                }
                paper[gy * gw + gx] = max(copy[gy * gw + gx], sum / k)
            }
        }
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val fy = ((y + 0.5f) / cell - 0.5f).coerceIn(0f, gh - 1f)
            val y0 = fy.toInt()
            val y1 = min(gh - 1, y0 + 1)
            val ty = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5f) / cell - 0.5f).coerceIn(0f, gw - 1f)
                val x0 = fx.toInt()
                val x1 = min(gw - 1, x0 + 1)
                val tx = fx - x0
                val level = (paper[y0 * gw + x0] * (1 - tx) + paper[y0 * gw + x1] * tx) * (1 - ty) +
                    (paper[y1 * gw + x0] * (1 - tx) + paper[y1 * gw + x1] * tx) * ty
                val v = (pixels[y * w + x].toInt() and 0xFF) / max(level, 1f)
                out[y * w + x] = (min(1f, v) * 255f).roundToInt().toByte()
            }
        }
        return GrayImage(w, h, out)
    }
}

// ---- Resultat de la reconnaissance -------------------------------------------------------------------

/** Un mot reconnu, en pixels de l'image (origine en haut a gauche). [conf] : 0 a 100. */
internal class OcrWord(val text: String, val conf: Float, val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Une ligne : ses mots et sa ligne de base (x1, y1) -> (x2, y2), en pixels (0 partout : inconnue). */
internal class OcrLine(val words: List<OcrWord>, val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    val left: Int get() = words.minOf { it.left }
    val right: Int get() = words.maxOf { it.right }
    val top: Int get() = words.minOf { it.top }
    val bottom: Int get() = words.maxOf { it.bottom }
    val text: String get() = words.sortedBy { it.left }.joinToString(" ") { it.text }

    /** Confiance moyenne, ponderee par la longueur des mots. */
    val conf: Float
        get() {
            val n = words.sumOf { it.text.length }
            return if (n == 0) 0f else words.sumOf { it.conf.toDouble() * it.text.length }.toFloat() / n
        }
}

internal class OcrResult(val width: Int, val height: Int, val lines: List<OcrLine>) {
    val words: List<OcrWord> get() = lines.flatMap { it.words }

    /** Lettres bien reconnues : sert a comparer les sens de lecture. */
    val score: Float
        get() = words.filter { it.conf >= 50f }.sumOf { it.text.count(Char::isLetterOrDigit) * it.conf / 100.0 }.toFloat()

    val meanConf: Float
        get() {
            val w = words.filter { it.text.any(Char::isLetterOrDigit) }
            if (w.isEmpty()) return 0f
            return w.sumOf { it.conf.toDouble() * it.text.length }.toFloat() / w.sumOf { it.text.length }
        }

    val text: String get() = lines.joinToString("\n") { it.text }
}

/** Un moteur de reconnaissance (Tesseract) : une instance par fil de calcul. */
internal interface TextRecognizer : AutoCloseable {
    /** Reconnait [image] (deja eclairee). */
    fun recognize(image: GrayImage, dpi: Int): OcrResult
}

private const val NO_BASELINE_LETTERS = "gjpqyçQ,;()[]{}|/"

/**
 * Ligne de base absente (le moteur Android ne la donne pas) ou aberrante : on la retrouve par le
 * bas des mots sans jambage (droite des moindres carres, la ligne peut etre penchee).
 */
internal fun fixBaseline(line: OcrLine): OcrLine {
    val left = line.left.toFloat()
    val right = line.right.toFloat()
    val ok = line.x2 > line.x1 && abs(line.y2 - line.y1) < (right - left) * 0.3f
    if (ok) return line
    val flat = line.words.filter { w -> w.text.any(Char::isLetterOrDigit) && w.text.none { it in NO_BASELINE_LETTERS } }
    if (flat.size >= 2) {
        val xs = flat.map { (it.left + it.right) / 2.0 }
        val ys = flat.map { it.bottom.toDouble() }
        val mx = xs.average()
        val my = ys.average()
        val sxx = xs.sumOf { (it - mx) * (it - mx) }
        if (sxx > 1.0) {
            val slope = (xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / sxx).coerceIn(-0.2, 0.2)
            fun y(x: Float) = (my + slope * (x - mx)).toFloat()
            return OcrLine(line.words, left, y(left), right, y(right))
        }
    }
    val bottoms = (flat.ifEmpty { line.words }).map { it.bottom.toFloat() }.sorted()
    val y = bottoms[bottoms.size / 2]
    return OcrLine(line.words, left, y, right, y)
}

/** Taille des lettres d'une ligne, en pixels, d'apres la hauteur des mots et les lettres qu'ils contiennent. */
internal fun estimateFontSizePx(line: OcrLine): Float {
    val estimates = line.words.filter { it.conf > 30f }.mapNotNull { w ->
        val letters = w.text.filter(Char::isLetterOrDigit)
        if (letters.isEmpty()) return@mapNotNull null
        val h = (w.bottom - w.top).toFloat()
        val latin = letters.all { it.code < 0x250 }
        if (!latin) return@mapNotNull h / 0.86f
        val ascender = letters.any { it.isUpperCase() || it.isDigit() || it in "bdfhklt" || (it.isLowerCase() && it.code > 127 && it != 'ç') }
        val descender = letters.any { it in "gjpqyç" }
        h / when {
            ascender && descender -> 0.94f
            ascender -> 0.73f
            descender -> 0.72f
            else -> 0.53f
        }
    }.sorted()
    if (estimates.isNotEmpty()) return estimates[estimates.size / 2]
    return line.words.map { it.bottom - it.top }.average().toFloat() / 0.8f
}

/**
 * Reconnait une page (eclairage corrige au passage). Si la lecture est mauvaise et que
 * [autoRotate] est vrai, essaie les trois autres sens sur une image reduite. Renvoie le resultat,
 * le quart de tour qui a redresse la page et l'image droite (pour la relecture).
 */
internal fun recognizeUpright(engine: TextRecognizer, image: GrayImage, dpi: Int, autoRotate: Boolean): Triple<OcrResult, Int, GrayImage> {
    val lit = image.evenLighting()
    val first = engine.recognize(lit, dpi)
    if (!autoRotate || (first.meanConf >= 70f && first.score >= 40f)) return Triple(first, 0, lit)
    val small = lit.scale(0.5f)
    val base = engine.recognize(small, dpi / 2).score
    var bestQuarter = 0
    var bestScore = base
    for (q in 1..3) {
        val s = engine.recognize(small.rotateQuarter(q), dpi / 2).score
        if (s > bestScore) {
            bestScore = s
            bestQuarter = q
        }
    }
    // Il faut une nette amelioration : une page presque vide ne doit pas tourner au hasard.
    if (bestQuarter == 0 || bestScore < 15f || bestScore < base * 1.6f + 5f) return Triple(first, 0, lit)
    val upright = lit.rotateQuarter(bestQuarter)
    return Triple(engine.recognize(upright, dpi), bestQuarter, upright)
}

// ---- Ce que contient deja une page -------------------------------------------------------------------

internal class PageTextInfo(val visibleChars: Int, val invisibleChars: Int, val hasImages: Boolean, val hasNougatLayer: Boolean)

/** Une page a reconnaitre : de l'image, et presque pas de texte (visible ou deja reconnu). */
internal fun needsOcr(info: PageTextInfo): Boolean =
    info.hasImages && info.visibleChars < 100 && info.invisibleChars < 20 && !info.hasNougatLayer

/**
 * Les pages a reconnaitre parmi celles demandees, et le nombre de pages laissees (deja du texte,
 * page blanche). Avec « refaire », l'ancien calque de Nougat est retire ([removeLayer]).
 */
internal fun planOcrPages(pageCount: Int, options: OcrOptions, info: (Int) -> PageTextInfo, removeLayer: (Int) -> Unit): Pair<List<Int>, Int> {
    val candidates = (options.pages ?: (0 until pageCount).toList()).filter { it in 0 until pageCount }.distinct()
    val todo = ArrayList<Int>()
    var skipped = 0
    for (i in candidates) {
        val p = info(i)
        when {
            p.hasNougatLayer && options.redo -> {
                removeLayer(i)
                todo.add(i)
            }
            p.hasNougatLayer -> skipped++
            p.visibleChars >= 100 || p.invisibleChars >= 20 -> skipped++
            p.hasImages || p.visibleChars > 0 -> todo.add(i)
            else -> skipped++ // page blanche
        }
    }
    return todo to skipped
}

internal const val OCR_DPI = 300f

/** Resolution de rendu d'une page : 300 dpi, moins pour les tres grandes pages ([maxPixels]). */
internal fun ocrDpi(geometry: PageGeometry, maxPixels: Float): Float {
    val inches = (geometry.displayWidth / 72f) * (geometry.displayHeight / 72f)
    return min(OCR_DPI, sqrt(maxPixels / max(inches, 0.01f)))
}

// ---- Le calque de texte invisible --------------------------------------------------------------------

internal const val OCR_LAYER_TAG = "NougatOCR"
internal const val OCR_FONT_RESOURCE = "NougatOCRFont"

/**
 * Police « sans dessin » (d'apres celle de Tesseract) : chaque caractere Unicode y a un code
 * (UTF-16), tous dessinent le meme glyphe vide de largeur 500/1000. Le texte invisible peut ainsi
 * contenir n'importe quelle lettre, dans n'importe quelle langue.
 */
internal const val GLYPHLESS_TTF_BASE64 =
    "AAEAAAAKAIAAAwAgT1MvMlbeyJQAAAEoAAAAYGNtYXAACwA2AAABkAAAACBnbHlmFSJBJAAAAbgAAAAYaGVhZAoyacEAAACsAAAANmhoZWEMAvwBAAAA5AAAACRobXR4AAAAAAAAAYgAAAAGbG9jYQAMAAAAAAGwAAAABm1heHAABAAFAAABCAAAACBuYW1l8usW2gAAAdAAAABLcG9zdAABAAEAAAIcAAAAIAABAAAAAQAAux2QVl8PPPUEBwgAAAAAAM+a/G4AAAAA030gTgAAAAAEAAgAAAAAEAACAAAAAAAAAAEAAAgA//8AAAAAAAD8AAQAAAEAAAAAAAAAAAAAAAAAAAABAAEAAAACAAQAAQAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAAwAAAZAABQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAUAAQABAAAAAAAAAAAAAAAAAAAAAAAAAAAAR09PRwBAAAAAAAAB//8AAAABAAGAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAIAAQAAAAAAFAADAAAAAAAUAAYADAAAAAAAAQAAAAAAAAAMAAAAAQAAAAAEAAgAAAMAADEhESEEAPwACAAAAAADACoAAAADAAAABQAWAAAAAQAAAAAABQALABYAAwABBAkABQAWAAAAVgBlAHIAcwBpAG8AbgAgADEALgAwVmVyc2lvbiAxLjAAAAEAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAA="

/** La police sans dessin, en octets. */
internal val glyphlessTtf: ByteArray by lazy { java.util.Base64.getDecoder().decode(GLYPHLESS_TTF_BASE64) }

/** CIDToGIDMap : tous les codes vers le glyphe 1 (vide). */
internal fun glyphlessCidToGid(): ByteArray = ByteArray(65536 * 2) { i -> if (i % 2 == 1) 1 else 0 }

/** ToUnicode : chaque code UTF-16 est sa propre lettre. */
internal fun glyphlessToUnicode(): ByteArray = buildString {
    append("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n")
    append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
    append("/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n")
    append("1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n")
    for (block in 0 until 256 step 100) {
        val end = min(256, block + 100)
        append("${end - block} beginbfrange\n")
        for (hi in block until end) {
            val h = "%02X".format(hi)
            append("<${h}00> <${h}FF> <${h}00>\n")
        }
        append("endbfrange\n")
    }
    append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n")
}.toByteArray(Charsets.US_ASCII)

private fun fmt(v: Double): String {
    val s = String.format(Locale.ROOT, "%.3f", v).trimEnd('0').trimEnd('.')
    return if (s == "-0") "0" else s
}

private fun hex(text: String): String = buildString {
    append('<')
    for (c in text) append("%04X".format(if (Character.isSurrogate(c)) '?'.code else c.code))
    append('>')
}

/** Le calque pret a ajouter : les operateurs PDF et le nombre de mots. */
internal class OcrLayer(val content: ByteArray, val words: Int)

/**
 * Le texte reconnu [ocr] (image de [ocr].width pixels de large pour toute la page affichee) en
 * operateurs PDF : texte invisible (mode 3) dans la police [OCR_FONT_RESOURCE], dans le repere de
 * la page affichee. Null s'il n'y a aucun mot.
 */
internal fun ocrLayer(geometry: PageGeometry, ocr: OcrResult): OcrLayer? {
    if (ocr.lines.isEmpty()) return null
    val scale = geometry.displayWidth.toDouble() / ocr.width // points par pixel
    val pageHeight = geometry.displayHeight.toDouble()
    val m = geometry.displayToPdfMatrix()
    var count = 0
    val out = StringBuilder()
    out.append("/$OCR_LAYER_TAG BMC\nq\n")
    out.append("${m.joinToString(" ") { fmt(it.toDouble()) }} cm\n")
    out.append("BT\n3 Tr\n")
    for (line in ocr.lines) {
        if (line.words.isEmpty()) continue
        val dxBase = (line.x2 - line.x1).toDouble()
        val dyBase = (line.y2 - line.y1).toDouble()
        val slope = if (dxBase > 0.0) dyBase / dxBase else 0.0
        val angle = atan2(-dyBase, if (dxBase > 0.0) dxBase else 1.0) // repere de la page : y vers le haut
        val cosA = cos(angle)
        val sinA = sin(angle)
        val size = estimateFontSizePx(line) * scale
        if (size <= 0.5) continue
        out.append("/$OCR_FONT_RESOURCE ${fmt(size)} Tf\n")
        val words = line.words.sortedBy { it.left }
        for ((i, w) in words.withIndex()) {
            val next = words.getOrNull(i + 1)
            val text = if (next != null) w.text + " " else w.text
            // Ligne de base au debut du mot (la ligne peut etre penchee).
            val xPx = w.left.toDouble()
            val yPx = line.y1 + (xPx - line.x1) * slope
            val x = xPx * scale
            val y = pageHeight - yPx * scale
            // Largeur a couvrir : jusqu'au mot suivant (espace compris), sinon le mot seul.
            val spanPx = (next?.left ?: w.right).toDouble() - w.left
            val span = spanPx * scale / max(0.2, cosA)
            val natural = text.length * 0.5 * size
            if (natural <= 0.0 || span <= 0.0) continue
            // Largeur par Tz (et non par Tm) : la taille des lettres reste la meme pour toute
            // la ligne, et les mots se regroupent en une seule ligne a modifier.
            val h = span / natural * 100.0
            out.append("${fmt(h)} Tz ${fmt(cosA)} ${fmt(sinA)} ${fmt(-sinA)} ${fmt(cosA)} ${fmt(x)} ${fmt(y)} Tm ${hex(text)} Tj\n")
            count++
        }
    }
    out.append("ET\nQ\nEMC\n")
    if (count == 0) return null
    return OcrLayer(out.toString().toByteArray(Charsets.US_ASCII), count)
}

// ---- Tout un document ------------------------------------------------------------------------------

/** Une page reconnue, prete a etre ajoutee au PDF. */
internal class RecognizedPage(val index: Int, val result: OcrResult, val quarter: Int, val aiLines: Int)

/**
 * Reconnait [pages] en parallele ([threads] moteurs). Le rendu ([render] : image et dpi) se fait
 * une page a la fois ; [proofread] (facultatif, une page a la fois) relit l'image droite et le
 * resultat. [onPage] (sur le fil appelant, dans l'ordre d'arrivee) ajoute la page au PDF ; il
 * renvoie faux pour arreter. Renvoie vrai si tout a ete fait.
 */
internal fun recognizePages(
    pages: List<Int>,
    threads: Int,
    render: (Int) -> Pair<GrayImage, Int>,
    newRecognizer: () -> TextRecognizer,
    autoRotate: Boolean,
    proofread: ((GrayImage, OcrResult) -> Pair<OcrResult, Int>)?,
    onPage: (RecognizedPage, done: Int, total: Int) -> Boolean,
): Boolean {
    if (pages.isEmpty()) return true
    val renderLock = Any()
    val proofLock = Any()
    val engines = java.util.Collections.synchronizedList(ArrayList<TextRecognizer>())
    val local = ThreadLocal.withInitial { newRecognizer().also(engines::add) }
    val pool = Executors.newFixedThreadPool(max(1, threads)) { r -> Thread(r, "nougat-ocr").apply { isDaemon = true } }
    val completion = ExecutorCompletionService<RecognizedPage>(pool)
    val futures = ArrayList<Future<RecognizedPage>>()
    try {
        for (index in pages) {
            futures += completion.submit(Callable {
                val (image, dpi) = synchronized(renderLock) { render(index) }
                val (result, quarter, upright) = recognizeUpright(local.get(), image, dpi, autoRotate)
                if (proofread == null) {
                    RecognizedPage(index, result, quarter, 0)
                } else {
                    val (better, ai) = synchronized(proofLock) { proofread(upright, result) }
                    RecognizedPage(index, better, quarter, ai)
                }
            })
        }
        for (done in 1..futures.size) {
            val page = try {
                completion.take().get()
            } catch (e: java.util.concurrent.ExecutionException) {
                throw e.cause ?: e
            }
            if (!onPage(page, done, futures.size)) return done == futures.size
        }
        return true
    } finally {
        futures.forEach { it.cancel(true) }
        pool.shutdownNow()
        // Une reconnaissance deja lancee va au bout (code natif) : on attend avant de liberer les
        // moteurs, sinon on libererait la memoire sous ses pieds (au pire, on renonce a les liberer).
        if (pool.awaitTermination(2, TimeUnit.MINUTES)) engines.forEach { runCatching { it.close() } }
    }
}

// ---- Sortie hOCR (Tesseract pour Android) -------------------------------------------------------------

private val HOCR_SPAN = Regex("""<span\s+class=['"](ocr_line|ocr_header|ocr_caption|ocr_textfloat|ocrx_word)['"][^>]*?title=(['"])(.*?)\2[^>]*>""")
private val HOCR_BBOX = Regex("""bbox (-?\d+) (-?\d+) (-?\d+) (-?\d+)""")
private val HOCR_BASELINE = Regex("""baseline (-?[\d.]+) (-?[\d.]+)""")
private val HOCR_CONF = Regex("""x_wconf (\d+)""")
private val HTML_TAG = Regex("<[^>]*>")

private fun unescapeHtml(s: String): String = s
    .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
    .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
    .replace(Regex("&#x([0-9a-fA-F]+);")) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value }
    .replace("&amp;", "&")

/**
 * Lit la sortie hOCR de Tesseract (lignes avec leur ligne de base, mots avec leur cadre et leur
 * confiance) : sur Android, c'est la seule facon d'obtenir la ligne de base des lignes.
 * La ligne de base hOCR « baseline pente decalage » part du coin bas-gauche du cadre de la ligne.
 */
internal fun parseHocr(hocr: String, width: Int, height: Int): OcrResult {
    val lines = ArrayList<OcrLine>()
    var words = ArrayList<OcrWord>()
    var base = FloatArray(4)
    fun flush() {
        if (words.isNotEmpty()) lines.add(fixBaseline(OcrLine(words, base[0], base[1], base[2], base[3])))
        words = ArrayList()
    }
    for (m in HOCR_SPAN.findAll(hocr)) {
        val kind = m.groupValues[1]
        val title = m.groupValues[3]
        val box = HOCR_BBOX.find(title)?.groupValues?.drop(1)?.map { it.toInt() } ?: continue
        if (kind != "ocrx_word") {
            flush()
            val bl = HOCR_BASELINE.find(title)
            base = if (bl != null) {
                val slope = bl.groupValues[1].toFloat()
                val offset = bl.groupValues[2].toFloat()
                val x0 = box[0].toFloat()
                val x1 = box[2].toFloat()
                val yBottom = box[3].toFloat()
                floatArrayOf(x0, yBottom + offset, x1, yBottom + offset + slope * (x1 - x0))
            } else {
                FloatArray(4)
            }
            continue
        }
        val end = hocr.indexOf("</span>", m.range.last + 1).takeIf { it >= 0 } ?: continue
        val text = unescapeHtml(hocr.substring(m.range.last + 1, end).replace(HTML_TAG, "")).trim()
        if (text.isEmpty()) continue
        val conf = HOCR_CONF.find(title)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
        words.add(OcrWord(text, conf, box[0], box[1], box[2], box[3]))
    }
    flush()
    return OcrResult(width, height, lines)
}
