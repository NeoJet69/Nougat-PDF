package app.nougat.pdf.engine

import org.apache.pdfbox.contentstream.operator.Operator
import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSInteger
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSString
import org.apache.pdfbox.pdfparser.PDFStreamParser
import org.apache.pdfbox.pdfwriter.ContentStreamWriter
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.common.PDStream
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.bytedeco.tesseract.TessBaseAPI
import org.bytedeco.tesseract.global.tesseract.OEM_LSTM_ONLY
import org.bytedeco.tesseract.global.tesseract.PSM_AUTO
import org.bytedeco.tesseract.global.tesseract.RIL_TEXTLINE
import org.bytedeco.tesseract.global.tesseract.RIL_WORD
import org.bytedeco.tesseract.global.tesseract.TessDeleteText
import org.bytedeco.tesseract.global.tesseract.TessResultIteratorDelete
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * OCR de la version ordinateur, avec Tesseract 5 (moteur LSTM).
 *
 * Pour chaque page : rendu en niveaux de gris a 300 dpi, reconnaissance, et si le resultat est
 * mauvais, essai des 3 autres sens (page couchee ou a l'envers) puis redressement de la page.
 * Le texte reconnu est ajoute en texte invisible : chaque mot a sa place exacte sur l'image,
 * le long de la ligne de base reelle (lignes penchees comprises) et a sa largeur reelle.
 * Le calque est encadre par « /NougatOCR BMC ... EMC » : on peut le retrouver et le refaire.
 */

/** Les modeles de langue (fichiers .traineddata), fournis avec l'app. */
internal object TessData {
    val dir: File? by lazy {
        listOfNotNull(
            System.getProperty("nougat.tessdata"),
            System.getenv("NOUGAT_TESSDATA"),
            System.getProperty("compose.application.resources.dir")?.let { "$it${File.separator}tessdata" },
        ).map(::File).firstOrNull { d -> d.isDirectory && d.listFiles()?.any { it.name.endsWith(".traineddata") } == true }
    }

    fun languages(): List<String> =
        dir?.listFiles()?.map { it.name }?.filter { it.endsWith(".traineddata") }?.map { it.removeSuffix(".traineddata") }?.sorted()
            ?: emptyList()
}

// ---- Resultat de Tesseract -------------------------------------------------------------------------

/** Un mot reconnu, en pixels de l'image (origine en haut a gauche). */
internal class OcrWord(val text: String, val conf: Float, val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Une ligne : ses mots et sa ligne de base (x1, y1) -> (x2, y2), en pixels. */
internal class OcrLine(val words: List<OcrWord>, val x1: Float, val y1: Float, val x2: Float, val y2: Float)

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

    val text: String get() = lines.joinToString("\n") { l -> l.words.joinToString(" ") { it.text } }
}

/** Une instance de Tesseract (une par fil de calcul : Tesseract n'aime pas etre partage). */
internal class Tesseract(dataDir: File, languages: String) : AutoCloseable {
    private val api = TessBaseAPI()

    init {
        if (api.Init(dataDir.absolutePath, languages, OEM_LSTM_ONLY) != 0) {
            api.close()
            throw IllegalStateException("Tesseract : langues introuvables ($languages)")
        }
        api.SetPageSegMode(PSM_AUTO)
        // Les espaces entre les mots ne sont pas des lettres : pas de « mots » d'un espace.
        api.SetVariable("preserve_interword_spaces", "0")
    }

    fun recognize(image: BufferedImage, dpi: Int): OcrResult {
        val gray = evenLighting(toGray(image))
        val data = (gray.raster.dataBuffer as DataBufferByte).data
        api.SetImage(data, gray.width, gray.height, 1, gray.width)
        api.SetSourceResolution(dpi)
        try {
            if (api.Recognize(null) != 0) return OcrResult(gray.width, gray.height, emptyList())
            val it = api.GetIterator() ?: return OcrResult(gray.width, gray.height, emptyList())
            val lines = ArrayList<OcrLine>()
            try {
                var words = ArrayList<OcrWord>()
                var base = FloatArray(4)
                fun flush() {
                    if (words.isNotEmpty()) lines.add(OcrLine(words, base[0], base[1], base[2], base[3]))
                    words = ArrayList()
                }
                val l = IntArray(1); val t = IntArray(1); val r = IntArray(1); val b = IntArray(1)
                it.Begin()
                do {
                    if (it.IsAtBeginningOf(RIL_TEXTLINE)) {
                        flush()
                        base = if (it.Baseline(RIL_TEXTLINE, l, t, r, b)) {
                            floatArrayOf(l[0].toFloat(), t[0].toFloat(), r[0].toFloat(), b[0].toFloat())
                        } else {
                            FloatArray(4)
                        }
                    }
                    val ptr = it.GetUTF8Text(RIL_WORD) ?: continue
                    val text = try {
                        ptr.getString(Charsets.UTF_8).trim()
                    } finally {
                        TessDeleteText(ptr)
                    }
                    if (text.isEmpty() || !it.BoundingBox(RIL_WORD, l, t, r, b)) continue
                    words.add(OcrWord(text, it.Confidence(RIL_WORD), l[0], t[0], r[0], b[0]))
                } while (it.Next(RIL_WORD))
                flush()
            } finally {
                TessResultIteratorDelete(it)
            }
            return OcrResult(gray.width, gray.height, lines.map(::fixBaseline))
        } finally {
            api.Clear()
        }
    }

    override fun close() {
        api.End()
        api.close()
    }
}

/** Ligne de base absente ou aberrante : on prend le bas des lettres sans jambage. */
private fun fixBaseline(line: OcrLine): OcrLine {
    val left = line.words.minOf { it.left }.toFloat()
    val right = line.words.maxOf { it.right }.toFloat()
    val ok = line.x2 > line.x1 && abs(line.y2 - line.y1) < (right - left) * 0.3f
    if (ok) return line
    val bottoms = line.words.map { it.bottom.toFloat() }.sorted()
    val y = bottoms[bottoms.size / 2]
    return OcrLine(line.words, left, y, right, y)
}

private fun abs(v: Float) = kotlin.math.abs(v)

internal fun toGray(image: BufferedImage): BufferedImage {
    if (image.type == BufferedImage.TYPE_BYTE_GRAY) return image
    val gray = BufferedImage(image.width, image.height, BufferedImage.TYPE_BYTE_GRAY)
    val g = gray.createGraphics()
    g.drawImage(image, 0, 0, null)
    g.dispose()
    return gray
}

/**
 * Corrige l'eclairage d'un scan ou d'une photo : ombre de la main ou du telephone, coin plus
 * sombre, papier jauni. On estime la teinte du papier case par case (les pixels les plus clairs,
 * pour ignorer l'encre), on la lisse, puis on divise chaque pixel par elle : le papier devient
 * blanc partout et l'encre garde son contraste. Sur une photo avec une ombre, Tesseract passe
 * ainsi de rien (ou presque) a quasiment tout. Les zones sombres (bandeau colore, photo) ne sont pas
 * du papier : on n'y touche pas, le texte clair sur fond fonce reste lisible.
 */
internal fun evenLighting(gray: BufferedImage): BufferedImage {
    val w = gray.width
    val h = gray.height
    val src = (gray.raster.dataBuffer as DataBufferByte).data
    if (src.size < w * h) return gray
    val cell = max(8, min(w, h) / 60)
    val gw = (w + cell - 1) / cell
    val gh = (h + cell - 1) / cell
    val paper = FloatArray(gw * gh)
    val hist = IntArray(256)
    for (gy in 0 until gh) for (gx in 0 until gw) {
        hist.fill(0)
        var n = 0
        for (y in gy * cell until min(h, (gy + 1) * cell)) for (x in gx * cell until min(w, (gx + 1) * cell)) {
            hist[src[y * w + x].toInt() and 0xFF]++
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
    if (pageLevel < 40f) return gray // page presque noire : rien a corriger
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
    val out = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
    val dst = (out.raster.dataBuffer as DataBufferByte).data
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
            val v = (src[y * w + x].toInt() and 0xFF) / max(level, 1f)
            dst[y * w + x] = (min(1f, v) * 255f).roundToInt().toByte()
        }
    }
    return out
}

/** Tourne l'image de [quarter] quarts de tour dans le sens des aiguilles d'une montre. */
internal fun rotateQuarter(image: BufferedImage, quarter: Int): BufferedImage {
    val q = ((quarter % 4) + 4) % 4
    if (q == 0) return image
    val w = image.width
    val h = image.height
    val out = if (q == 2) BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY) else BufferedImage(h, w, BufferedImage.TYPE_BYTE_GRAY)
    val g = out.createGraphics()
    val t = AffineTransform()
    when (q) {
        1 -> { t.translate(h.toDouble(), 0.0); t.quadrantRotate(1) }
        2 -> { t.translate(w.toDouble(), h.toDouble()); t.quadrantRotate(2) }
        3 -> { t.translate(0.0, w.toDouble()); t.quadrantRotate(3) }
    }
    g.drawImage(image, t, null)
    g.dispose()
    return out
}

private fun downscale(image: BufferedImage, factor: Float): BufferedImage {
    val w = max(1, (image.width * factor).roundToInt())
    val h = max(1, (image.height * factor).roundToInt())
    val out = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
    val g = out.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.drawImage(image, 0, 0, w, h, null)
    g.dispose()
    return out
}

/**
 * Reconnait une page. Si la lecture est mauvaise et que [autoRotate] est vrai, essaie les trois
 * autres sens sur une image reduite. Renvoie le resultat et le quart de tour qui a redresse la page.
 */
internal fun recognizeUpright(tess: Tesseract, image: BufferedImage, dpi: Int, autoRotate: Boolean): Pair<OcrResult, Int> {
    val first = tess.recognize(image, dpi)
    if (!autoRotate || (first.meanConf >= 70f && first.score >= 40f)) return first to 0
    val small = downscale(toGray(image), 0.5f)
    val base = tess.recognize(small, dpi / 2).score
    var bestQuarter = 0
    var bestScore = base
    for (q in 1..3) {
        val s = tess.recognize(rotateQuarter(small, q), dpi / 2).score
        if (s > bestScore) {
            bestScore = s
            bestQuarter = q
        }
    }
    // Il faut une nette amelioration : une page presque vide ne doit pas tourner au hasard.
    if (bestQuarter == 0 || bestScore < 15f || bestScore < base * 1.6f + 5f) return first to 0
    return tess.recognize(rotateQuarter(image, bestQuarter), dpi) to bestQuarter
}

// ---- Ce que contient deja une page -------------------------------------------------------------------

internal class PageTextInfo(val visibleChars: Int, val invisibleChars: Int, val hasImages: Boolean, val hasNougatLayer: Boolean)

private class CharCounter : PDFTextStripper() {
    var visible = 0
    var invisible = 0

    override fun processTextPosition(text: TextPosition) {
        val chars = text.unicode?.count { !it.isWhitespace() } ?: 0
        if (graphicsState.textState.renderingMode == RenderingMode.NEITHER) invisible += chars else visible += chars
    }
}

internal fun pageTextInfo(doc: PDDocument, index: Int): PageTextInfo {
    val counter = CharCounter()
    counter.startPage = index + 1
    counter.endPage = index + 1
    runCatching { counter.getText(doc) }
    val page = doc.getPage(index)
    return PageTextInfo(counter.visible, counter.invisible, hasImages(page.resources, 0), hasNougatLayer(page))
}

private fun hasImages(resources: PDResources?, depth: Int): Boolean {
    if (resources == null || depth > 3) return false
    for (name in resources.xObjectNames) {
        val x = runCatching { resources.getXObject(name) }.getOrNull()
        if (x is PDImageXObject) return true
        if (x is PDFormXObject && hasImages(x.resources, depth + 1)) return true
    }
    return false
}

/** Une page a reconnaitre : de l'image, et presque pas de texte (visible ou deja reconnu). */
internal fun needsOcr(info: PageTextInfo): Boolean =
    info.hasImages && info.visibleChars < 100 && info.invisibleChars < 20 && !info.hasNougatLayer

private val OCR_TAG = COSName.getPDFName("NougatOCR")
private val OCR_FONT = COSName.getPDFName("NougatOCRFont")

private fun pageTokens(page: PDPage): List<Any> = PDFStreamParser(page).parse()

private fun hasNougatLayer(page: PDPage): Boolean {
    val tokens = runCatching { pageTokens(page) }.getOrNull() ?: return false
    return tokens.indices.any { i -> tokens[i] is Operator && (tokens[i] as Operator).name == "BMC" && tokens.getOrNull(i - 1) == OCR_TAG }
}

/** Retire un calque OCR de Nougat deja present (pour le refaire). */
internal fun removeNougatLayer(doc: PDDocument, page: PDPage): Boolean {
    val tokens = pageTokens(page)
    val kept = ArrayList<Any>(tokens.size)
    var pending = ArrayList<Any>()
    var depth = 0 // profondeur dans le calque Nougat
    var removed = false
    for (t in tokens) {
        if (t is Operator) {
            val name = t.name
            if (depth > 0) {
                if (name == "BMC" || name == "BDC") depth++
                if (name == "EMC") depth--
                pending.clear()
                continue
            }
            if (name == "BMC" && pending.size == 1 && pending[0] == OCR_TAG) {
                depth = 1
                removed = true
                pending.clear()
                continue
            }
            kept.addAll(pending)
            kept.add(t)
            pending.clear()
        } else if (depth == 0) {
            pending.add(t)
        }
    }
    kept.addAll(pending)
    if (!removed) return false
    val stream = PDStream(doc)
    stream.createOutputStream(COSName.FLATE_DECODE).use { ContentStreamWriter(it).writeTokens(kept) }
    page.setContents(stream)
    return true
}

// ---- Le calque de texte invisible --------------------------------------------------------------------

/**
 * Police « sans dessin » (la meme que Tesseract) : chaque caractere Unicode y a un code
 * (UTF-16), tous dessinent le meme glyphe vide de largeur 500/1000. Le texte invisible peut
 * ainsi contenir n'importe quelle lettre, dans n'importe quelle langue.
 */
private const val GLYPHLESS_TTF =
    "AAEAAAAKAIAAAwAgT1MvMlbeyJQAAAEoAAAAYGNtYXAACwA2AAABkAAAACBnbHlmFSJBJAAAAbgAAAAYaGVhZAoyacEAAACsAAAANmhoZWEMAvwBAAAA5AAAACRobXR4AAAAAAAAAYgAAAAGbG9jYQAMAAAAAAGwAAAABm1heHAABAAFAAABCAAAACBuYW1l8usW2gAAAdAAAABLcG9zdAABAAEAAAIcAAAAIAABAAAAAQAAux2QVl8PPPUEBwgAAAAAAM+a/G4AAAAA030gTgAAAAAEAAgAAAAAEAACAAAAAAAAAAEAAAgA//8AAAAAAAD8AAQAAAEAAAAAAAAAAAAAAAAAAAABAAEAAAACAAQAAQAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAAwAAAZAABQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAUAAQABAAAAAAAAAAAAAAAAAAAAAAAAAAAAR09PRwBAAAAAAAAB//8AAAABAAGAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAIAAQAAAAAAFAADAAAAAAAUAAYADAAAAAAAAQAAAAAAAAAMAAAAAQAAAAAEAAgAAAMAADEhESEEAPwACAAAAAADACoAAAADAAAABQAWAAAAAQAAAAAABQALABYAAwABBAkABQAWAAAAVgBlAHIAcwBpAG8AbgAgADEALgAwVmVyc2lvbiAxLjAAAAEAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAAAA="

private fun stream(doc: PDDocument, bytes: ByteArray): PDStream = PDStream(doc, ByteArrayInputStream(bytes), COSName.FLATE_DECODE)

internal fun glyphlessFont(doc: PDDocument): COSDictionary {
    val ttf = stream(doc, Base64.getDecoder().decode(GLYPHLESS_TTF))
    ttf.cosObject.setInt(COSName.LENGTH1, Base64.getDecoder().decode(GLYPHLESS_TTF).size)
    val descriptor = COSDictionary().apply {
        setItem(COSName.TYPE, COSName.FONT_DESC)
        setName(COSName.FONT_NAME, "GlyphLessFont")
        setInt(COSName.FLAGS, 5)
        setItem(COSName.FONT_BBOX, COSArray(listOf(COSInteger.get(0), COSInteger.get(-250), COSInteger.get(500), COSInteger.get(750))))
        setInt(COSName.ITALIC_ANGLE, 0)
        setInt(COSName.ASCENT, 750)
        setInt(COSName.DESCENT, -250)
        setInt(COSName.CAP_HEIGHT, 700)
        setInt(COSName.STEM_V, 80)
        setItem(COSName.FONT_FILE2, ttf)
    }
    // Tous les codes vers le glyphe 1 (vide).
    val map = ByteArray(65536 * 2) { i -> if (i % 2 == 1) 1 else 0 }
    val cid = COSDictionary().apply {
        setItem(COSName.TYPE, COSName.FONT)
        setItem(COSName.SUBTYPE, COSName.CID_FONT_TYPE2)
        setName(COSName.BASE_FONT, "GlyphLessFont")
        setItem(COSName.CIDSYSTEMINFO, COSDictionary().apply {
            setItem(COSName.REGISTRY, COSString("Adobe"))
            setItem(COSName.ORDERING, COSString("Identity"))
            setInt(COSName.SUPPLEMENT, 0)
        })
        setItem(COSName.FONT_DESC, descriptor)
        setInt(COSName.DW, 500)
        setItem(COSName.CID_TO_GID_MAP, stream(doc, map))
    }
    val cmap = buildString {
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
    }
    return COSDictionary().apply {
        setItem(COSName.TYPE, COSName.FONT)
        setItem(COSName.SUBTYPE, COSName.TYPE0)
        setName(COSName.BASE_FONT, "GlyphLessFont")
        setItem(COSName.ENCODING, COSName.IDENTITY_H)
        setItem(COSName.DESCENDANT_FONTS, COSArray(listOf(cid)))
        setItem(COSName.TO_UNICODE, stream(doc, cmap.toByteArray(Charsets.US_ASCII)))
    }
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

private fun fmt(v: Double): String {
    val s = String.format(Locale.ROOT, "%.3f", v).trimEnd('0').trimEnd('.')
    return if (s == "-0") "0" else s
}

private fun hex(text: String): String = buildString {
    append('<')
    for (c in text) append("%04X".format(if (Character.isSurrogate(c)) '?'.code else c.code))
    append('>')
}

/**
 * Ajoute a la page le texte reconnu [ocr] (image de [ocr].width pixels de large pour toute la
 * page affichee), en texte invisible.
 */
internal fun addOcrLayer(doc: PDDocument, page: PDPage, geometry: PageGeometry, ocr: OcrResult, font: COSDictionary): Int {
    if (ocr.lines.isEmpty()) return 0
    val resources = page.resources ?: PDResources().also { page.resources = it }
    val fonts = resources.cosObject.getCOSDictionary(COSName.FONT) ?: COSDictionary().also { resources.cosObject.setItem(COSName.FONT, it) }
    fonts.setItem(OCR_FONT, font)

    val scale = geometry.displayWidth.toDouble() / ocr.width // points par pixel
    val pageHeight = geometry.displayHeight.toDouble()
    val m = geometry.displayToPdfMatrix()
    var count = 0
    val out = StringBuilder()
    out.append("/NougatOCR BMC\nq\n")
    out.append("${m.joinToString(" ") { fmt(it.toDouble()) }} cm\n")
    out.append("BT\n3 Tr\n")
    for (line in ocr.lines) {
        val dxBase = (line.x2 - line.x1).toDouble()
        val dyBase = (line.y2 - line.y1).toDouble()
        val slope = if (dxBase > 0.0) dyBase / dxBase else 0.0
        val angle = atan2(-dyBase, if (dxBase > 0.0) dxBase else 1.0) // repere de la page : y vers le haut
        val cosA = cos(angle)
        val sinA = sin(angle)
        val size = estimateFontSizePx(line) * scale
        if (size <= 0.5) continue
        out.append("/NougatOCRFont ${fmt(size)} Tf\n")
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
    if (count == 0) return 0
    // Le contenu existant est encadre par q/Q : notre repere ne depend pas de ce qu'il laisse.
    val streams = ArrayList<PDStream>()
    streams.add(stream(doc, "q\n".toByteArray()))
    page.contentStreams.forEach { streams.add(PDStream(it.cosObject as org.apache.pdfbox.cos.COSStream)) }
    streams.add(stream(doc, "Q\n".toByteArray()))
    streams.add(stream(doc, out.toString().toByteArray(Charsets.US_ASCII)))
    page.setContents(streams)
    return count
}

// ---- Tout un document ------------------------------------------------------------------------------

private const val OCR_DPI = 300f
private const val MAX_PIXELS = 30_000_000f

/** Ce qu'il faut savoir avant de lancer : langues disponibles, pages a reconnaitre. */
/** Pages a reconnaitre (les 60 premieres suffisent pour savoir si le document est scanne). */
internal fun countPagesNeedingOcr(doc: PDDocument): Int = (0 until min(doc.numberOfPages, 60)).count { needsOcr(pageTextInfo(doc, it)) }

/**
 * Reconnait le texte des pages de [doc]. Le rendu des pages se fait l'une apres l'autre (PDFBox),
 * la reconnaissance en parallele (une instance de Tesseract par fil).
 * [progress] (pages faites, total) renvoie faux pour arreter.
 */
internal fun recognizeDocument(
    doc: PDDocument,
    options: OcrOptions,
    geometry: (Int) -> PageGeometry,
    setRotation: (Int, Int) -> Unit,
    progress: (Int, Int) -> Boolean,
): OcrSummary {
    val dataDir = TessData.dir ?: throw IllegalStateException("Modeles de langue introuvables")
    val available = TessData.languages().toSet()
    val langs = options.languages.filter { it in available }.ifEmpty { listOf(available.firstOrNull { it == "eng" } ?: available.first()) }
    val langString = langs.joinToString("+")

    val candidates = (options.pages ?: (0 until doc.numberOfPages).toList()).filter { it in 0 until doc.numberOfPages }
    val todo = ArrayList<Int>()
    var skipped = 0
    for (i in candidates) {
        val info = pageTextInfo(doc, i)
        when {
            info.hasNougatLayer && options.redo -> {
                removeNougatLayer(doc, doc.getPage(i))
                todo.add(i)
            }
            info.hasNougatLayer -> skipped++
            info.visibleChars >= 100 || info.invisibleChars >= 20 -> skipped++
            info.hasImages || info.visibleChars > 0 -> todo.add(i)
            else -> skipped++ // page blanche
        }
    }
    if (todo.isEmpty()) return OcrSummary(0, skipped, 0, 0)

    val renderer = PDFRenderer(doc)
    val renderLock = Any()
    val threads = max(1, min(4, Runtime.getRuntime().availableProcessors() / 2))
    val engines = Collections.synchronizedList(ArrayList<Tesseract>())
    val local = ThreadLocal.withInitial { Tesseract(dataDir, langString).also(engines::add) }
    val pool = Executors.newFixedThreadPool(threads) { r -> Thread(r, "nougat-ocr").apply { isDaemon = true } }
    val completion = ExecutorCompletionService<Triple<Int, OcrResult, Int>>(pool)
    val futures = ArrayList<Future<Triple<Int, OcrResult, Int>>>()
    var recognized = 0
    var rotated = 0
    var words = 0
    var cancelled = false
    try {
        for (index in todo) {
            futures += completion.submit(Callable {
                val image = synchronized(renderLock) {
                    val g = geometry(index)
                    val inches = (g.displayWidth / 72f) * (g.displayHeight / 72f)
                    val dpi = min(OCR_DPI, sqrt(MAX_PIXELS / max(inches, 0.01f)))
                    renderer.renderImageWithDPI(index, dpi, ImageType.GRAY) to dpi.roundToInt()
                }
                val (result, quarter) = recognizeUpright(local.get(), image.first, image.second, options.autoRotate)
                Triple(index, result, quarter)
            })
        }
        val font = glyphlessFont(doc)
        for (done in 1..futures.size) {
            val (index, result, quarter) = completion.take().get()
            if (quarter != 0) {
                setRotation(index, geometry(index).rotation + quarter * 90)
                rotated++
            }
            val added = addOcrLayer(doc, doc.getPage(index), geometry(index), result, font)
            if (added > 0) recognized++
            words += added
            if (!progress(done, futures.size)) {
                cancelled = done < futures.size
                break
            }
        }
    } finally {
        futures.forEach { it.cancel(true) }
        pool.shutdownNow()
        // Une reconnaissance deja lancee va au bout (code natif) : on attend avant de liberer les
        // moteurs, sinon on libererait la memoire sous ses pieds (au pire, on renonce a les liberer).
        if (pool.awaitTermination(2, java.util.concurrent.TimeUnit.MINUTES)) engines.forEach { runCatching { it.close() } }
    }
    return OcrSummary(recognized, skipped, rotated, words, cancelled)
}
