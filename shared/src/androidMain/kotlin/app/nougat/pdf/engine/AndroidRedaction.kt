package app.nougat.pdf.engine

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import java.io.File

/*
 * Cacher pour de bon, version Android : sous un rectangle « Masquer », le texte et les pixels sont
 * retires ; « Finaliser » transforme chaque page en image (rendue par PdfRenderer).
 */

/** Voir [EditableDoc.eraseUnder]. */
internal fun eraseUnderAndroid(doc: PDDocument, pageIndex: Int, areas: List<Pair<NormRect, Int>>) {
    val page = doc.getPage(pageIndex)
    val box = page.cropBox
    val rotated = page.rotation % 180 != 0
    val w = if (rotated) box.height else box.width
    val h = if (rotated) box.width else box.height
    val analysis = analyzePageTextAndroid(doc, pageIndex, w, h).analysis
    val plan = TextEditPlanner.planAreaRemoval(analysis, areas.map { it.first })
    if (plan.isNotEmpty()) rewriteContent(doc, page, plan)
    val geometry = PageGeometry(box.lowerLeftX, box.lowerLeftY, box.width, box.height, normalizeRotation(page.rotation))
    scrubPageImagesAndroid(doc, page, areas.map { (r, argb) -> scrubArea(geometry, r, 0f, 0f, argb and 0xFFFFFF) })
}

/**
 * Voir [EditableDoc.flatten]. [src] : le fichier ouvert (les annotations y sont deja gravees), rendu
 * par PdfRenderer ; sans lui, PdfBox-Android rend la page.
 */
internal fun flattenAndroid(doc: PDDocument, src: File?, dpi: Float) {
    val pages = (0 until doc.numberOfPages).map { doc.getPage(it) }
    for ((i, page) in pages.withIndex()) {
        val bitmap = renderPageForScanEdit(doc, src, i, dpi)
        try {
            val width = bitmap.width * 72f / dpi
            val height = bitmap.height * 72f / dpi
            val flat = PDPage(PDRectangle(width, height))
            val jpeg = JPEGFactory.createFromImage(doc, bitmap, 0.88f)
            PDPageContentStream(doc, flat).use { it.drawImage(jpeg, 0f, 0f, width, height) }
            doc.pages.insertBefore(flat, page)
        } finally {
            bitmap.recycle()
        }
    }
    for (page in pages) doc.pages.remove(page)
    val catalog = doc.documentCatalog.cosObject
    for (key in listOf(COSName.ACRO_FORM, COSName.OUTLINES, COSName.NAMES, COSName.OPEN_ACTION, COSName.AA, COSName.METADATA, COSName.STRUCT_TREE_ROOT, COSName.getPDFName("PieceInfo"))) {
        catalog.removeItem(key)
    }
    doc.documentInformation = PDDocumentInformation()
}
