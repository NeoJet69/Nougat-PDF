package app.nougat.pdf.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.googlecode.tesseract.android.TessBaseAPI
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdfwriter.ContentStreamWriter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * OCR de la version Android : Tesseract 5 (Tesseract4Android) avec la meme logique que sur
 * ordinateur (voir OcrCore.kt), le rendu des pages par PdfRenderer (PDFium, celui d'Android) et
 * l'ecriture du calque invisible par PdfBox-Android. En option, Gemini Nano (l'IA de
 * l'appareil) relit les lignes ou Tesseract doute (voir GeminiNano.kt).
 *
 * Le francais et l'anglais sont livres dans l'app ; les autres langues se telechargent une seule
 * fois (2 a 6 Mo chacune), puis tout se fait sans Internet.
 */

/** Les modeles de langue de Tesseract sur l'appareil. */
internal class AndroidOcrModels(context: Context) {
    private val app = context.applicationContext

    /** Dossier parent de « tessdata » (ce que demande Tesseract4Android). */
    val dataPath: File = File(app.filesDir, "ocr")
    private val tessdata = File(dataPath, "tessdata")

    private fun file(code: String) = File(tessdata, "$code.traineddata")

    private fun bundled(code: String): Boolean = runCatching { app.assets.list("tessdata")?.contains("$code.traineddata") == true }.getOrDefault(false)

    /** Toutes les langues proposees (livrees ou a telecharger). */
    val languages: List<String> = OCR_LANGUAGES.map { it.first }

    /** La langue est utilisable sans Internet (deja la, ou livree avec l'app). */
    fun ready(code: String): Boolean = file(code).length() > 0 || bundled(code)

    /**
     * Rend les langues [codes] utilisables : copie celles livrees avec l'app, telecharge les
     * autres ([onProgress] : langue, pourcentage). Leve une exception si une langue manque.
     */
    fun prepare(codes: List<String>, onProgress: (String, Int) -> Unit) {
        tessdata.mkdirs()
        for (code in codes.distinct()) {
            val target = file(code)
            if (target.length() > 0) continue
            val tmp = File(tessdata, "$code.part")
            if (bundled(code)) {
                app.assets.open("tessdata/$code.traineddata").use { input -> tmp.outputStream().use { input.copyTo(it) } }
            } else {
                onProgress(code, 0)
                download(code, tmp) { onProgress(code, it) }
            }
            if (tmp.length() < 1000 || !tmp.renameTo(target)) {
                tmp.delete()
                throw IllegalStateException("Modele $code incomplet")
            }
        }
    }

    /** Les modeles « tessdata_best » en entiers, comme sur ordinateur (projet tesseract.js-data). */
    private fun download(code: String, out: File, percent: (Int) -> Unit) {
        val direct = "https://cdn.jsdelivr.net/npm/@tesseract.js-data/$code@1.0.0/4.0.0_best_int/$code.traineddata.gz"
        val failure = runCatching { fetch(direct) { input, total -> gunzipTo(input, total, out, percent) } }.exceptionOrNull() ?: return
        // Secours : le paquet npm entier (les deux versions du modele), dont on extrait la bonne.
        val tarball = "https://registry.npmjs.org/@tesseract.js-data/$code/-/$code-1.0.0.tgz"
        runCatching {
            fetch(tarball) { input, _ ->
                val wanted = "package/4.0.0_best_int/$code.traineddata.gz"
                val gz = readTarEntry(GZIPInputStream(input), wanted) ?: throw IllegalStateException("$wanted absent")
                gunzipTo(ByteArrayInputStream(gz), -1, out, percent)
            }
        }.onFailure { throw it.also { e -> e.addSuppressed(failure) } }
    }

    private fun fetch(url: String, body: (InputStream, Long) -> Unit) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            c.inputStream.use { body(it, c.contentLengthLong) }
        } finally {
            c.disconnect()
        }
    }

    private fun gunzipTo(input: InputStream, compressedSize: Long, out: File, percent: (Int) -> Unit) {
        val counting = object : java.io.FilterInputStream(input) {
            var read = 0L
            override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) read += it }
        }
        var last = -1
        GZIPInputStream(counting).use { gz ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = gz.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    if (compressedSize > 0) {
                        val p = (counting.read * 100 / compressedSize).toInt().coerceIn(0, 100)
                        if (p != last) percent(p).also { last = p }
                    }
                }
            }
        }
        percent(100)
    }

    /** Lit un fichier d'une archive tar (format simple des paquets npm). */
    private fun readTarEntry(tar: InputStream, wanted: String): ByteArray? {
        val header = ByteArray(512)
        while (true) {
            if (tar.readNBytesCompat(header) < 512) return null
            val name = String(header, 0, 100, Charsets.US_ASCII).substringBefore('\u0000')
            if (name.isEmpty()) return null
            val size = String(header, 124, 12, Charsets.US_ASCII).trim('\u0000', ' ').toLong(8)
            val padded = (size + 511) / 512 * 512
            if (name == wanted) {
                val data = ByteArray(size.toInt())
                if (tar.readNBytesCompat(data) < size) return null
                return data
            }
            var skip = padded
            while (skip > 0) {
                val n = tar.skip(skip)
                if (n <= 0) {
                    if (tar.read() < 0) return null
                    skip--
                } else {
                    skip -= n
                }
            }
        }
    }

    private fun InputStream.readNBytesCompat(b: ByteArray): Int {
        var off = 0
        while (off < b.size) {
            val n = read(b, off, b.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }
}

/** Tesseract pour Android ; la sortie hOCR donne aussi la ligne de base des lignes. */
internal class AndroidTesseract(dataPath: File, languages: String) : TextRecognizer {
    private val api = TessBaseAPI()

    init {
        if (!api.init(dataPath.absolutePath, languages, TessBaseAPI.OEM_LSTM_ONLY)) {
            api.recycle()
            throw IllegalStateException("Tesseract : langues introuvables ($languages)")
        }
        api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
        api.setVariable("preserve_interword_spaces", "0")
    }

    override fun recognize(image: GrayImage, dpi: Int): OcrResult {
        api.setVariable("user_defined_dpi", dpi.toString())
        api.setImage(image.pixels, image.width, image.height, 1, image.width)
        try {
            val hocr = api.getHOCRText(0) ?: return OcrResult(image.width, image.height, emptyList())
            return parseHocr(hocr, image.width, image.height)
        } finally {
            api.clear()
        }
    }

    override fun close() = api.recycle()
}

// ---- Images ------------------------------------------------------------------------------------------

internal fun Bitmap.toGrayImage(): GrayImage {
    val w = width
    val h = height
    val out = ByteArray(w * h)
    val row = IntArray(w)
    for (y in 0 until h) {
        getPixels(row, 0, w, 0, y, w, 1)
        for (x in 0 until w) {
            val c = row[x]
            val v = (((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            out[y * w + x] = v.toByte()
        }
    }
    return GrayImage(w, h, out)
}

internal fun GrayImage.toBitmap(): Bitmap {
    val colors = IntArray(width * height) { i ->
        val v = pixels[i].toInt() and 0xFF
        Color.rgb(v, v, v)
    }
    return Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888)
}

/**
 * Rendu des pages pour l'OCR : PdfRenderer (PDFium) sur le fichier d'origine, qui lit tous les
 * formats d'image des scans ; PdfBox-Android en secours (PDF protege par mot de passe).
 */
private class OcrPageRenderer(src: File?, private val doc: PDDocument) : AutoCloseable {
    private val fd = src?.let { runCatching { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull() }
    private val pdfium: PdfRenderer? = fd?.let { runCatching { PdfRenderer(it) }.getOrNull() }
    private val fallback by lazy { PDFRenderer(doc) }

    fun render(index: Int, dpi: Float): GrayImage {
        val r = pdfium
        if (r != null && index < r.pageCount) {
            r.openPage(index).use { page ->
                val w = (page.width * dpi / 72f).roundToInt().coerceAtLeast(1)
                val h = (page.height * dpi / 72f).roundToInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    return bitmap.toGrayImage()
                } finally {
                    bitmap.recycle()
                }
            }
        }
        val bitmap = fallback.renderImageWithDPI(index, dpi, ImageType.RGB)
        try {
            return bitmap.toGrayImage()
        } finally {
            bitmap.recycle()
        }
    }

    override fun close() {
        runCatching { pdfium?.close() }
        runCatching { fd?.close() }
    }
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

internal fun pageTextInfoAndroid(doc: PDDocument, index: Int): PageTextInfo {
    val counter = CharCounter()
    counter.startPage = index + 1
    counter.endPage = index + 1
    runCatching { counter.getText(doc) }
    val page = doc.getPage(index)
    return PageTextInfo(counter.visible, counter.invisible, hasImages(page.resources, 0), hasNougatLayer(page))
}

/** Pages a reconnaitre (les 60 premieres suffisent pour savoir si le document est scanne). */
internal fun countPagesNeedingOcrAndroid(doc: PDDocument): Int =
    (0 until min(doc.numberOfPages, 60)).count { needsOcr(pageTextInfoAndroid(doc, it)) }

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

private fun pageTokens(page: PDPage): List<Any> {
    val parser = PDFStreamParser(page)
    val tokens = ArrayList<Any>()
    while (true) tokens.add(parser.parseNextToken() ?: break)
    return tokens
}

private fun hasNougatLayer(page: PDPage): Boolean {
    val tokens = runCatching { pageTokens(page) }.getOrNull() ?: return false
    return tokens.indices.any { i -> tokens[i] is Operator && (tokens[i] as Operator).name == "BMC" && tokens.getOrNull(i - 1) == OCR_TAG }
}

/** Retire un calque OCR de Nougat deja present (pour le refaire). */
private fun removeNougatLayer(doc: PDDocument, page: PDPage): Boolean {
    val tokens = pageTokens(page)
    val kept = ArrayList<Any>(tokens.size)
    val pending = ArrayList<Any>()
    var depth = 0
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

// ---- Le calque invisible -----------------------------------------------------------------------------

private fun stream(doc: PDDocument, bytes: ByteArray): PDStream = PDStream(doc, ByteArrayInputStream(bytes), COSName.FLATE_DECODE)

private fun cosArray(vararg items: com.tom_roush.pdfbox.cos.COSBase) = COSArray().apply { items.forEach { add(it) } }

private fun glyphlessFont(doc: PDDocument): COSDictionary {
    val ttf = stream(doc, glyphlessTtf)
    ttf.cosObject.setInt(COSName.LENGTH1, glyphlessTtf.size)
    val descriptor = COSDictionary().apply {
        setItem(COSName.TYPE, COSName.FONT_DESC)
        setName(COSName.FONT_NAME, "GlyphLessFont")
        setInt(COSName.FLAGS, 5)
        setItem(COSName.FONT_BBOX, cosArray(COSInteger.get(0), COSInteger.get(-250), COSInteger.get(500), COSInteger.get(750)))
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
        setItem(COSName.DESCENDANT_FONTS, cosArray(cid))
        setItem(COSName.TO_UNICODE, stream(doc, glyphlessToUnicode()))
    }
}

private fun addOcrLayer(doc: PDDocument, page: PDPage, geometry: PageGeometry, ocr: OcrResult, font: COSDictionary): Int {
    val layer = ocrLayer(geometry, ocr) ?: return 0
    val resources = page.resources ?: PDResources().also { page.resources = it }
    val fonts = resources.cosObject.getDictionaryObject(COSName.FONT) as? COSDictionary
        ?: COSDictionary().also { resources.cosObject.setItem(COSName.FONT, it) }
    fonts.setItem(OCR_FONT, font)
    // Le contenu existant est encadre par q/Q : notre repere ne depend pas de ce qu'il laisse.
    val streams = ArrayList<PDStream>()
    streams.add(stream(doc, "q\n".toByteArray()))
    page.contentStreams.forEach { streams.add(it) }
    streams.add(stream(doc, "Q\n".toByteArray()))
    streams.add(stream(doc, layer.content))
    page.setContents(streams)
    return layer.words
}

// ---- Tout un document ------------------------------------------------------------------------------

/** Pages plus petites que sur ordinateur : la memoire d'un telephone est comptee. */
private const val MAX_PIXELS_ANDROID = 12_000_000f

/**
 * Reconnait le texte des pages de [doc] (ouvert depuis [src]). [reader] : la relecture par
 * Gemini Nano, si elle est demandee et disponible.
 */
internal fun recognizeDocumentAndroid(
    doc: PDDocument,
    src: File?,
    models: AndroidOcrModels,
    options: OcrOptions,
    reader: LineReader?,
    geometry: (Int) -> PageGeometry,
    setRotation: (Int, Int) -> Unit,
    progress: (Int, Int) -> Boolean,
): OcrSummary {
    val langs = options.languages.filter { models.ready(it) }.ifEmpty { listOf("eng") }
    models.prepare(langs) { _, _ -> }
    val langString = langs.joinToString("+")

    val (todo, skipped) = planOcrPages(doc.numberOfPages, options, { pageTextInfoAndroid(doc, it) }) { removeNougatLayer(doc, doc.getPage(it)) }
    if (todo.isEmpty()) return OcrSummary(0, skipped, 0, 0)

    val font = glyphlessFont(doc)
    var recognized = 0
    var rotated = 0
    var words = 0
    var aiLines = 0
    val complete = OcrPageRenderer(src, doc).use { renderer ->
        recognizePages(
            pages = todo,
            threads = max(1, min(2, Runtime.getRuntime().availableProcessors() / 2)),
            render = { index ->
                val dpi = ocrDpi(geometry(index), MAX_PIXELS_ANDROID)
                renderer.render(index, dpi) to dpi.roundToInt()
            },
            newRecognizer = { AndroidTesseract(models.dataPath, langString) },
            autoRotate = options.autoRotate,
            proofread = reader?.let { r ->
                { image, result ->
                    // Une minute au plus par page : la relecture ne doit pas tout bloquer.
                    OcrProofreading.proofread(image, result, System.currentTimeMillis() + 60_000, r)
                }
            },
        ) { page, done, total ->
            if (page.quarter != 0) {
                setRotation(page.index, geometry(page.index).rotation + page.quarter * 90)
                rotated++
            }
            val added = addOcrLayer(doc, doc.getPage(page.index), geometry(page.index), page.result, font)
            if (added > 0) recognized++
            words += added
            aiLines += page.aiLines
            progress(done, total)
        }
    }
    return OcrSummary(recognized, skipped, rotated, words, cancelled = !complete, aiLines = aiLines)
}
