package app.nougat.pdf.engine

import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDDocumentInformation
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer

/*
 * Cacher pour de bon, version ordinateur (PDFBox) : sous un rectangle « Masquer », le texte et les
 * pixels sont retires ; « Finaliser » transforme chaque page en image.
 */

/** Voir [EditableDoc.eraseUnder]. */
internal fun eraseUnderWithPdfBox(doc: PDDocument, pageIndex: Int, areas: List<Pair<NormRect, Int>>) {
    val page = doc.getPage(pageIndex)
    val box = page.cropBox
    val rotated = page.rotation % 180 != 0
    val w = if (rotated) box.height else box.width
    val h = if (rotated) box.width else box.height
    val analysis = analyzePageText(doc, pageIndex, w, h).analysis
    val plan = TextEditPlanner.planAreaRemoval(analysis, areas.map { it.first })
    if (plan.isNotEmpty()) rewriteContent(doc, page, plan)
    val geometry = PageGeometry(box.lowerLeftX, box.lowerLeftY, box.width, box.height, normalizeRotation(page.rotation))
    scrubPageImages(doc, page, areas.map { (r, argb) -> scrubArea(geometry, r, 0f, 0f, argb and 0xFFFFFF) })
}

/** Voir [EditableDoc.flatten]. */
internal fun flattenWithPdfBox(doc: PDDocument, dpi: Float) {
    val renderer = PDFRenderer(doc)
    val pages = (0 until doc.numberOfPages).map { doc.getPage(it) }
    for (page in pages) {
        // L'image de la page telle qu'on la voit (rotation, annotations et formulaire compris).
        // Les pages deja finalisees sont inserees avant : on cherche la place actuelle de la page.
        val image = renderer.renderImageWithDPI(doc.pages.indexOf(page), dpi, ImageType.RGB)
        val width = image.width * 72f / dpi
        val height = image.height * 72f / dpi
        val flat = PDPage(PDRectangle(width, height))
        val jpeg = JPEGFactory.createFromImage(doc, image, 0.88f)
        PDPageContentStream(doc, flat).use { it.drawImage(jpeg, 0f, 0f, width, height) }
        doc.pages.insertBefore(flat, page)
    }
    for (page in pages) doc.pages.remove(page)
    removeHiddenParts(doc)
}

/** Ce qui ne se voit pas mais reste dans un PDF : formulaire, signets, pieces jointes, scripts, metadonnees. */
internal fun removeHiddenParts(doc: PDDocument) {
    val catalog = doc.documentCatalog.cosObject
    for (key in listOf(COSName.ACRO_FORM, COSName.OUTLINES, COSName.NAMES, COSName.OPEN_ACTION, COSName.AA, COSName.METADATA, COSName.STRUCT_TREE_ROOT, COSName.getPDFName("PieceInfo"))) {
        catalog.removeItem(key)
    }
    doc.documentInformation = PDDocumentInformation()
}
