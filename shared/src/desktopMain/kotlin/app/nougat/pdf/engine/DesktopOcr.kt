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
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * OCR de la version ordinateur : Tesseract 5 (moteur LSTM, via JavaCPP) et PDFBox. La logique
 * (eclairage, sens de lecture, calque invisible...) est commune avec Android : voir OcrCore.kt.
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

/** Une instance de Tesseract (une par fil de calcul : Tesseract n'aime pas etre partage). */
internal class Tesseract(dataDir: File, languages: String) : TextRecognizer {
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

    override fun recognize(image: GrayImage, dpi: Int): OcrResult {
        api.SetImage(image.pixels, image.width, image.height, 1, image.width)
        api.SetSourceResolution(dpi)
        try {
            if (api.Recognize(null) != 0) return OcrResult(image.width, image.height, emptyList())
            val it = api.GetIterator() ?: return OcrResult(image.width, image.height, emptyList())
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
            return OcrResult(image.width, image.height, lines.map(::fixBaseline))
        } finally {
            api.Clear()
        }
    }

    /** La meme lecture au format hOCR (celui qu'utilise Android) : sert aux tests. */
    fun hocr(image: GrayImage, dpi: Int): String {
        api.SetImage(image.pixels, image.width, image.height, 1, image.width)
        api.SetSourceResolution(dpi)
        try {
            val ptr = api.GetHOCRText(0) ?: return ""
            return try {
                ptr.getString(Charsets.UTF_8)
            } finally {
                TessDeleteText(ptr)
            }
        } finally {
            api.Clear()
        }
    }

    override fun close() {
        api.End()
        api.close()
    }
}

/** Une image rendue par PDFBox, en niveaux de gris. */
internal fun BufferedImage.toGrayImage(): GrayImage {
    val gray = if (type == BufferedImage.TYPE_BYTE_GRAY) this else BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY).also {
        val g = it.createGraphics()
        g.drawImage(this, 0, 0, null)
        g.dispose()
    }
    val data = (gray.raster.dataBuffer as DataBufferByte).data
    return GrayImage(gray.width, gray.height, if (data.size == width * height) data else data.copyOf(width * height))
}

// ---- Ce que contient deja une page -------------------------------------------------------------------

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

private val OCR_TAG = COSName.getPDFName(OCR_LAYER_TAG)
private val OCR_FONT = COSName.getPDFName(OCR_FONT_RESOURCE)

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

private fun stream(doc: PDDocument, bytes: ByteArray): PDStream = PDStream(doc, ByteArrayInputStream(bytes), COSName.FLATE_DECODE)

internal fun glyphlessFont(doc: PDDocument): COSDictionary {
    val ttf = stream(doc, glyphlessTtf)
    ttf.cosObject.setInt(COSName.LENGTH1, glyphlessTtf.size)
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
        setItem(COSName.CID_TO_GID_MAP, stream(doc, glyphlessCidToGid()))
    }
    return COSDictionary().apply {
        setItem(COSName.TYPE, COSName.FONT)
        setItem(COSName.SUBTYPE, COSName.TYPE0)
        setName(COSName.BASE_FONT, "GlyphLessFont")
        setItem(COSName.ENCODING, COSName.IDENTITY_H)
        setItem(COSName.DESCENDANT_FONTS, COSArray(listOf(cid)))
        setItem(COSName.TO_UNICODE, stream(doc, glyphlessToUnicode()))
    }
}

/** Ajoute a la page le texte reconnu [ocr], en texte invisible. Renvoie le nombre de mots. */
internal fun addOcrLayer(doc: PDDocument, page: PDPage, geometry: PageGeometry, ocr: OcrResult, font: COSDictionary): Int {
    val layer = ocrLayer(geometry, ocr) ?: return 0
    val resources = page.resources ?: PDResources().also { page.resources = it }
    val fonts = resources.cosObject.getCOSDictionary(COSName.FONT) ?: COSDictionary().also { resources.cosObject.setItem(COSName.FONT, it) }
    fonts.setItem(OCR_FONT, font)
    // Le contenu existant est encadre par q/Q : notre repere ne depend pas de ce qu'il laisse.
    val streams = ArrayList<PDStream>()
    streams.add(stream(doc, "q\n".toByteArray()))
    page.contentStreams.forEach { streams.add(PDStream(it.cosObject as org.apache.pdfbox.cos.COSStream)) }
    streams.add(stream(doc, "Q\n".toByteArray()))
    streams.add(stream(doc, layer.content))
    page.setContents(streams)
    return layer.words
}

// ---- Tout un document ------------------------------------------------------------------------------

private const val MAX_PIXELS = 30_000_000f

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

    val (todo, skipped) = planOcrPages(doc.numberOfPages, options, { pageTextInfo(doc, it) }) { removeNougatLayer(doc, doc.getPage(it)) }
    if (todo.isEmpty()) return OcrSummary(0, skipped, 0, 0)

    val renderer = PDFRenderer(doc)
    val font = glyphlessFont(doc)
    var recognized = 0
    var rotated = 0
    var words = 0
    val complete = recognizePages(
        pages = todo,
        threads = max(1, min(4, Runtime.getRuntime().availableProcessors() / 2)),
        render = { index ->
            val dpi = ocrDpi(geometry(index), MAX_PIXELS)
            renderer.renderImageWithDPI(index, dpi, ImageType.GRAY).toGrayImage() to dpi.roundToInt()
        },
        newRecognizer = { Tesseract(dataDir, langString) },
        autoRotate = options.autoRotate,
        proofread = null,
    ) { page, done, total ->
        if (page.quarter != 0) {
            setRotation(page.index, geometry(page.index).rotation + page.quarter * 90)
            rotated++
        }
        val added = addOcrLayer(doc, doc.getPage(page.index), geometry(page.index), page.result, font)
        if (added > 0) recognized++
        words += added
        progress(done, total)
    }
    return OcrSummary(recognized, skipped, rotated, words, cancelled = !complete)
}
