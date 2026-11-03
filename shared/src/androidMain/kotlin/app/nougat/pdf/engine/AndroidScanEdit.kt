package app.nougat.pdf.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * Modifier une ligne d'un scan, version Android : rendu par PdfRenderer (PDFium), polices candidates
 * dessinees par Android (sans, avec empattements, a chasse fixe ; normal, gras, italique), ecriture
 * par PdfBox-Android avec la police standard du PDF correspondante (Helvetica, Times, Courier).
 * L'analyse de l'image (papier, encre, police, papier reconstitue) est commune : ScanEditCore.kt.
 */

/** Les polices d'Android, dessinees par Canvas. */
private object AndroidRasterizer : GlyphRasterizer {
    override val candidates: List<ScanFont> = buildList {
        for ((name, std) in listOf("sans-serif" to StandardFamily.Sans, "serif" to StandardFamily.Serif, "monospace" to StandardFamily.Mono)) {
            add(ScanFont(name, bold = false, italic = false, standard = std))
            add(ScanFont(name, bold = true, italic = false, standard = std))
            add(ScanFont(name, bold = false, italic = true, standard = std))
        }
    }

    private fun paint(font: ScanFont, sizePx: Float): Paint {
        val style = when {
            font.bold && font.italic -> Typeface.BOLD_ITALIC
            font.bold -> Typeface.BOLD
            font.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(font.name, style)
            textSize = sizePx
            color = Color.WHITE
        }
    }

    override fun bounds(font: ScanFont, text: String, sizePx: Float): FloatArray? {
        val r = Rect()
        paint(font, sizePx).getTextBounds(text, 0, text.length, r)
        if (r.width() <= 0) return null
        return floatArrayOf(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
    }

    override fun render(font: ScanFont, text: String, sizePx: Float, x: Float, baseline: Float, w: Int, h: Int): BooleanArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            Canvas(bitmap).drawText(text, x, baseline, paint(font, sizePx))
            val px = IntArray(w * h)
            bitmap.getPixels(px, 0, w, 0, 0, w, h)
            return BooleanArray(w * h) { i -> (px[i] ushr 24) > 100 }
        } finally {
            bitmap.recycle()
        }
    }
}

private fun Bitmap.toRgbImage(): RgbImage {
    val px = IntArray(width * height)
    getPixels(px, 0, width, 0, 0, width, height)
    for (i in px.indices) px[i] = px[i] and 0xFFFFFF
    return RgbImage(width, height, px)
}

private fun RgbImage.toBitmap(): Bitmap {
    val colors = IntArray(width * height) { i -> pixels[i] or (0xFF shl 24) }
    return Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888)
}

/**
 * La page a [dpi], en couleurs. PdfRenderer (PDFium) sur le fichier ouvert, qui lit tous les
 * formats d'image des scans ; PdfBox-Android en secours (PDF protege par mot de passe).
 */
private fun renderPageForScanEdit(doc: PDDocument, src: File?, pageIndex: Int, dpi: Float): Bitmap {
    if (src != null) {
        val viaPdfium = runCatching {
            ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    renderer.openPage(pageIndex).use { page ->
                        val w = (page.width * dpi / 72f).roundToInt().coerceAtLeast(1)
                        val h = (page.height * dpi / 72f).roundToInt().coerceAtLeast(1)
                        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                            it.eraseColor(Color.WHITE)
                            page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        }
                    }
                }
            }
        }.getOrNull()
        if (viaPdfium != null) return viaPdfium
    }
    return PDFRenderer(doc).renderImageWithDPI(pageIndex, dpi, ImageType.RGB)
}

private fun standardFont(font: ScanFont): PDType1Font = when (font.standard ?: StandardFamily.Sans) {
    StandardFamily.Sans -> when {
        font.bold && font.italic -> PDType1Font.HELVETICA_BOLD_OBLIQUE
        font.bold -> PDType1Font.HELVETICA_BOLD
        font.italic -> PDType1Font.HELVETICA_OBLIQUE
        else -> PDType1Font.HELVETICA
    }
    StandardFamily.Serif -> when {
        font.bold && font.italic -> PDType1Font.TIMES_BOLD_ITALIC
        font.bold -> PDType1Font.TIMES_BOLD
        font.italic -> PDType1Font.TIMES_ITALIC
        else -> PDType1Font.TIMES_ROMAN
    }
    StandardFamily.Mono -> when {
        font.bold && font.italic -> PDType1Font.COURIER_BOLD_OBLIQUE
        font.bold -> PDType1Font.COURIER_BOLD
        font.italic -> PDType1Font.COURIER_OBLIQUE
        else -> PDType1Font.COURIER
    }
}

/** Polices du telephone pour les lettres hors de l'alphabet latin (grec, cyrillique...). */
private val SYSTEM_FONTS = listOf(
    "/system/fonts/NotoSans-Regular.ttf", "/system/fonts/Roboto-Regular.ttf", "/system/fonts/DroidSans.ttf",
    "/system/fonts/NotoSerif-Regular.ttf",
)

private fun pdfFont(doc: PDDocument, style: ScanLineStyle, text: String): PDFont {
    fun encodable(f: PDFont) = runCatching { f.encode(text); true }.getOrDefault(false)
    val std = standardFont(style.font)
    if (encodable(std)) return std
    for (path in SYSTEM_FONTS) {
        val file = File(path)
        if (!file.isFile) continue
        val f = runCatching { PDType0Font.load(doc, file) }.getOrNull() ?: continue
        if (encodable(f)) return f
    }
    return std
}

private fun safeText(font: PDFont, text: String): String = buildString {
    for (ch in text) append(if (runCatching { font.encode(ch.toString()) }.isSuccess) ch else '?')
}

/** Remplace une ligne de scan (voir ScanEditCore.kt). [src] : le fichier ouvert, pour le rendu. */
internal fun rewriteScannedLineAndroid(doc: PDDocument, src: File?, pageIndex: Int, analysis: PageTextAnalysis, target: TextBlock, newText: String) {
    val keys = target.glyphs.toSet()
    val glyphs = analysis.glyphs.filter { it.key in keys }
    if (glyphs.isEmpty()) throw TextEditException(app.nougat.pdf.i18n.Str.ErrTextNotFound)
    val page = doc.getPage(pageIndex)
    val box = page.cropBox
    val rotated = page.rotation % 180 != 0
    val pageW = if (rotated) box.height else box.width
    val pageH = if (rotated) box.width else box.height
    val line = scanLinePlacement(glyphs, pageW)

    // L'image de la page avant toute modification (le rendu se fait avant de retirer le texte).
    val bitmap = renderPageForScanEdit(doc, src, pageIndex, SCAN_EDIT_DPI)
    val style = try {
        val k = bitmap.width / pageW
        analyzeScanLine(bitmap.toRgbImage(), k, line.originX, line.originY, line.angle, line.length, line.size, target.text, AndroidRasterizer)
    } finally {
        bitmap.recycle()
    }

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
    val patchBitmap = style.patch.toBitmap()
    val patch = try {
        JPEGFactory.createFromImage(doc, patchBitmap, 0.92f)
    } finally {
        patchBitmap.recycle()
    }
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
            cs.setNonStrokingColor((style.ink shr 16 and 0xFF) / 255f, (style.ink shr 8 and 0xFF) / 255f, (style.ink and 0xFF) / 255f)
            cs.setTextMatrix(Matrix(c, s, -s, c, x, y))
            cs.showText(safe)
            cs.endText()
        }
    }
}
