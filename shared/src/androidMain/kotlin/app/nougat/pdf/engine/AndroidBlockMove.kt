package app.nougat.pdf.engine

import android.graphics.Bitmap
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix
import java.io.File

private const val MOVE_DPI = 200f

/** Deplace les lignes choisies (voir [BlockMove]) avec PdfBox-Android. */
internal fun moveBlocksAndroid(editable: EditableDoc, doc: PDDocument, src: File?, index: Int, blocks: List<TextBlock>, dx: Float, dy: Float, scale: Float) {
    if (blocks.isEmpty()) return
    val g = editable.geometry(index)
    val w = g.displayWidth
    val h = g.displayHeight
    val targets = BlockMove.targets(blocks, dx, dy, scale, w, h)

    // 1. L'encre de chaque ligne, sur le rendu de la page avant tout changement.
    val rendered = renderPageForScanEdit(doc, src, index, MOVE_DPI)
    val iw = rendered.width
    val ih = rendered.height
    val pixels = IntArray(iw * ih)
    rendered.getPixels(pixels, 0, iw, 0, 0, iw, ih)
    rendered.recycle()
    val patches = targets.map { t ->
        val (pw, ph, px) = BlockMove.crop(pixels, iw, ih, t.from)
        Bitmap.createBitmap(BlockMove.inkOnly(px, pw, ph), pw, ph, Bitmap.Config.ARGB_8888)
    }

    // 2. Les lignes sont effacees (texte retire, pixels du scan repeints).
    editable.deleteTextBlocks(index, blocks)

    // 3. L'encre recollee, et le texte invisible pour chercher et copier.
    val page = doc.getPage(index)
    val font = PDType1Font.HELVETICA
    val m = g.displayToPdfMatrix()
    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
        cs.transform(Matrix(m[0], m[1], m[2], m[3], m[4], m[5]))
        for ((t, patch) in targets.zip(patches)) {
            val to = t.to
            val image = LosslessFactory.createFromImage(doc, patch)
            patch.recycle()
            cs.drawImage(image, to.left * w, (1f - to.bottom) * h, (to.right - to.left) * w, (to.bottom - to.top) * h)

            val text = t.block.text.filter { c -> runCatching { font.encode(c.toString()) }.isSuccess }.trim()
            if (text.isEmpty()) continue
            val size = (t.block.fontSize * scale).coerceIn(2f, 200f)
            val natural = font.getStringWidth(text) / 1000f * size
            val target = (t.block.rect.right - t.block.rect.left) * scale * w
            cs.beginText()
            cs.setRenderingMode(RenderingMode.NEITHER)
            cs.setFont(font, size)
            if (natural > 0f) cs.setHorizontalScaling((100f * target / natural).coerceIn(10f, 1000f))
            val left = to.left * w + (t.block.rect.left - t.from.left) * scale * w
            val baseline = (1f - to.bottom) * h + (t.from.bottom - t.block.rect.bottom) * scale * h + size * 0.2f
            cs.newLineAtOffset(left, baseline)
            cs.showText(text)
            cs.endText()
        }
    }
}
