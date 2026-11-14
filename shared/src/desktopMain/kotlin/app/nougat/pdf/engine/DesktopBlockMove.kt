package app.nougat.pdf.engine

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.pdmodel.graphics.state.RenderingMode
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.util.Matrix
import java.awt.image.BufferedImage

private const val MOVE_DPI = 200f

/** Deplace les lignes choisies (voir [BlockMove]) avec PDFBox. */
internal fun moveBlocksWithPdfBox(editable: EditableDoc, doc: PDDocument, index: Int, blocks: List<TextBlock>, dx: Float, dy: Float, scale: Float) {
    if (blocks.isEmpty()) return
    val g = editable.geometry(index)
    val w = g.displayWidth
    val h = g.displayHeight
    val targets = BlockMove.targets(blocks, dx, dy, scale, w, h)

    // 1. L'encre de chaque ligne, sur le rendu de la page avant tout changement.
    val rendered = PDFRenderer(doc).renderImageWithDPI(index, MOVE_DPI, ImageType.RGB)
    val iw = rendered.width
    val ih = rendered.height
    val pixels = rendered.getRGB(0, 0, iw, ih, null, 0, iw)
    val patches = targets.map { t ->
        val (pw, ph, px) = BlockMove.crop(pixels, iw, ih, t.from)
        BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, pw, ph, BlockMove.inkOnly(px, pw, ph), 0, pw) }
    }

    // 2. Les lignes sont effacees (texte retire, pixels du scan repeints).
    editable.deleteTextBlocks(index, blocks)

    // 3. L'encre recollee, et le texte invisible pour chercher et copier.
    val page = doc.getPage(index)
    val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
    val m = g.displayToPdfMatrix()
    PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
        cs.transform(Matrix(m[0], m[1], m[2], m[3], m[4], m[5]))
        for ((t, patch) in targets.zip(patches)) {
            val to = t.to
            val image = LosslessFactory.createFromImage(doc, patch)
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
