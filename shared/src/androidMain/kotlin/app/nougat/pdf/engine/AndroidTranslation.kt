package app.nougat.pdf.engine

import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil
import kotlin.math.max

/*
 * Traduction sur Android : ML Kit de Google traduit sur le telephone (gratuit, sans Internet une
 * fois la langue telechargee, environ 30 Mo par langue) et reconnait la langue du document ;
 * Gemini Nano, s'il est la, relit la traduction pour la rendre plus naturelle.
 * La copie traduite est dessinee par Android lui-meme (toutes les ecritures : arabe, chinois...)
 * dans une page PDF transparente, posee ensuite par-dessus la page d'origine.
 */

/** Attend la fin d'une tache des services Google. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        val error = task.exception
        when {
            error != null -> cont.resumeWithException(error)
            task.isCanceled -> cont.cancel()
            else -> cont.resume(task.result)
        }
    }
}

internal class MlKitTranslator : DeviceTranslator {
    override val languages: Set<String> =
        TRANSLATION_LANGUAGES.mapNotNull { TranslateLanguage.fromLanguageTag(it.code) }.toSet()

    private val identifier by lazy {
        LanguageIdentification.getClient(LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.45f).build())
    }
    private val models by lazy { RemoteModelManager.getInstance() }

    // Le dernier traducteur (une paire de langues) reste ouvert d'une page a l'autre.
    private var translator: Translator? = null
    private var pair: Pair<String, String>? = null

    override suspend fun detect(text: String): String? {
        val tag = runCatching { identifier.identifyLanguage(text.take(2000)).await() }.getOrNull() ?: return null
        // « und » : pas reconnue ; « xx-Latn » : langue ecrite en lettres latines (pinyin...).
        if (tag == "und" || "-Latn" in tag) return null
        return TranslateLanguage.fromLanguageTag(tag.substringBefore('-'))?.takeIf { it in languages }
    }

    override suspend fun ready(code: String): Boolean = runCatching {
        models.getDownloadedModels(TranslateRemoteModel::class.java).await().any { it.language == code }
    }.getOrDefault(false)

    override suspend fun prepare(codes: List<String>) {
        for (code in codes.distinct()) {
            if (ready(code)) continue
            models.download(TranslateRemoteModel.Builder(code).build(), DownloadConditions.Builder().build()).await()
        }
    }

    @Synchronized
    private fun client(source: String, target: String): Translator {
        val wanted = source to target
        translator?.let { if (pair == wanted) return it }
        translator?.close()
        return Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target).build(),
        ).also {
            translator = it
            pair = wanted
        }
    }

    override suspend fun translate(texts: List<String>, source: String, target: String): List<String> {
        val client = client(source, target)
        client.downloadModelIfNeeded().await()
        return texts.map { client.translate(it).await() }
    }

    override suspend fun polish(original: String, draft: String, source: String, target: String): String? {
        val from = translationLanguage(source)?.englishName ?: return null
        val to = translationLanguage(target)?.englishName ?: return null
        return GeminiNano.polishTranslation(original, draft, from, to)
    }
}

// ---- La copie traduite ---------------------------------------------------------------------------------

/** Resolution du rendu qui sert a lire les couleurs du papier et de l'encre. */
private const val COLOR_DPI = 110f

/**
 * Ecrit la traduction des paragraphes de la page [pageIndex] (voir [EditableDoc.writeTranslation]).
 * Renvoie le petit PDF du calque de texte, a fermer apres l'enregistrement du document.
 */
internal fun writeTranslationAndroid(doc: PDDocument, src: File?, pageIndex: Int, items: List<TranslatedParagraph>, language: String): PDDocument? {
    if (items.isEmpty()) return null
    val page = doc.getPage(pageIndex)
    val box = page.cropBox
    val rotated = page.rotation % 180 != 0
    val w = if (rotated) box.height else box.width
    val h = if (rotated) box.width else box.height

    // 1. Les couleurs, lues sur la page telle qu'elle est (avant de retirer le texte).
    val bitmap = renderPageForScanEdit(doc, src, pageIndex, COLOR_DPI)
    val iw = bitmap.width
    val ih = bitmap.height
    val pixels = IntArray(iw * ih)
    bitmap.getPixels(pixels, 0, iw, 0, 0, iw, ih)
    bitmap.recycle()
    fun colors(r: NormRect) = PageColors.sample(
        pixels, iw, ih,
        (r.left * iw).toInt(), (r.top * ih).toInt(), (r.right * iw).toInt() + 1, (r.bottom * ih).toInt() + 1,
    )

    // 2. Le texte d'origine : retire s'il est dessine par la page, sinon recouvert.
    val analysis = analyzePageTextAndroid(doc, pageIndex, w, h).analysis
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
        Plan(item, paper, ink, if (p.scanned && covers.isNotEmpty()) listOf(p.rect) else covers)
    }
    if (removable.isNotEmpty()) rewriteContent(doc, page, TextEditPlanner.planRemoval(analysis, removable))

    // 3. Le calque : papier et traduction dessines par Android dans une page PDF de la meme taille.
    val pw = ceil(w).toInt().coerceAtLeast(1)
    val ph = ceil(h).toInt().coerceAtLeast(1)
    val layer = android.graphics.pdf.PdfDocument()
    val bytes = try {
        val layerPage = layer.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(pw, ph, 1).create())
        val canvas = layerPage.canvas
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        for (plan in plans) {
            val p = plan.item.paragraph
            val pad = p.fontSize * 0.12f
            fill.color = plan.paper or (0xFF shl 24)
            for (r in plan.covers) canvas.drawRect(RectF(r.left * w - pad, r.top * h - pad, r.right * w + pad, r.bottom * h + pad), fill)
            drawParagraph(canvas, plan.item, plan.ink, w, h)
        }
        layer.finishPage(layerPage)
        ByteArrayOutputStream().also { layer.writeTo(it) }.toByteArray()
    } finally {
        layer.close()
    }
    val layerDoc = PDDocument.load(bytes)
    val form = LayerUtility(doc).importPageAsForm(layerDoc, 0)
    val geometry = PageGeometry(box.lowerLeftX, box.lowerLeftY, box.width, box.height, normalizeRotation(page.rotation))
    val m = geometry.displayToPdfMatrix()
    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
        cs.transform(Matrix(m[0], m[1], m[2], m[3], m[4], m[5]))
        cs.transform(Matrix.getScaleInstance(w / pw, h / ph))
        cs.drawForm(form)
    }
    return layerDoc
}

/** Le paragraphe traduit, dans son cadre, a la plus grande taille qui y tient (au plus l'originale). */
private fun drawParagraph(canvas: android.graphics.Canvas, item: TranslatedParagraph, ink: Int, w: Float, h: Float) {
    val p = item.paragraph
    val text = item.text.replace(Regex("\\s+"), " ").trim()
    if (text.isEmpty()) return
    val family = when (p.standardFont.name.take(5)) {
        "Times" -> "serif"
        "Couri" -> "monospace"
        else -> "sans-serif"
    }
    val style = when {
        p.bold && p.italic -> Typeface.BOLD_ITALIC
        p.bold -> Typeface.BOLD
        p.italic -> Typeface.ITALIC
        else -> Typeface.NORMAL
    }
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(family, style)
        color = ink or (0xFF shl 24)
    }
    val boxW = ceil(p.layoutWidth * w * 1.02f).toInt().coerceAtLeast(1)
    val firstLine = p.lines.first()
    val firstBaseline = firstLine.bottom * h - (firstLine.bottom - firstLine.top) * h * (0.3f / 1.4f)
    val ascent = (firstBaseline - p.rect.top * h).coerceAtLeast(p.fontSize * 0.6f)
    val boxH = max((p.rect.bottom - p.rect.top) * h, p.fontSize * (1f + (p.lines.size - 1) * p.lineSpacing))
    val alignment = when (p.align) {
        ParagraphAlign.Center -> Layout.Alignment.ALIGN_CENTER
        ParagraphAlign.End -> Layout.Alignment.ALIGN_OPPOSITE
        ParagraphAlign.Start -> Layout.Alignment.ALIGN_NORMAL
    }
    val spacing = max(1.1f, p.lineSpacing)
    fun layout(size: Float): StaticLayout {
        paint.textSize = size
        // L'ecart naturel entre les lignes d'une police est d'environ 1,17 fois sa taille.
        val mult = (spacing / 1.17f).coerceIn(0.85f, 1.8f)
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, boxW)
            .setAlignment(alignment)
            .setLineSpacing(0f, mult)
            .setIncludePad(false)
            .build()
    }
    var size = p.fontSize
    val minSize = max(3f, p.fontSize * 0.4f)
    var layout = layout(size)
    while (layout.height > boxH * 1.02f && size > minSize) {
        size = max(minSize, size * 0.94f)
        layout = layout(size)
    }
    val scale = size / p.fontSize
    val baseline = p.rect.top * h + ascent * scale
    canvas.save()
    canvas.translate(p.rect.left * w, baseline - layout.getLineBaseline(0))
    layout.draw(canvas)
    canvas.restore()
}
