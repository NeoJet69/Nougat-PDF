package app.nougat.pdf.engine

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import kotlin.random.Random

/** Une ligne du document d'origine : sa position (points, origine en bas a gauche) et son texte. */
internal data class SampleLine(val x: Float, val baseline: Float, val size: Float, val text: String)

internal val SCAN_PAGE_1 = listOf(
    SampleLine(60f, 760f, 22f, "Atelier Nougat & Fils"),
    SampleLine(60f, 735f, 12f, "Rue des Chats 7, 1003 Lausanne"),
    SampleLine(60f, 680f, 12f, "Madame Élise Martin"),
    SampleLine(60f, 664f, 12f, "Avenue de la Gare 15"),
    SampleLine(60f, 600f, 16f, "Devis numéro 2026-117"),
    SampleLine(60f, 560f, 12f, "Réfection complète de la salle de bain : 4 850 francs."),
    SampleLine(60f, 542f, 12f, "Délai d'exécution : trois semaines dès réception de l'acompte."),
    SampleLine(60f, 524f, 12f, "Ce devis est valable jusqu'au 30 novembre."),
    SampleLine(60f, 470f, 12f, "Avec nos meilleures salutations."),
)

internal val SCAN_PAGE_2 = listOf(
    SampleLine(60f, 760f, 18f, "Conditions générales"),
    SampleLine(60f, 720f, 12f, "Les travaux sont garantis pendant deux ans."),
    SampleLine(60f, 702f, 12f, "Le paiement est dû à trente jours."),
    SampleLine(60f, 684f, 12f, "Tout retard entraîne des intérêts moratoires."),
)

/**
 * Un faux scan realiste : la page est imprimee (Helvetica / Times), « scannee » a 200 dpi sur un
 * papier legerement jauni, penchee de [skewDegrees], avec du bruit, puis enregistree en JPEG
 * dans un PDF sans aucun texte. Avec [upsideDownSecondPage], la page 2 est a l'envers.
 */
internal fun makeSampleScan(file: File, skewDegrees: Double = 1.2, upsideDownSecondPage: Boolean = true): String {
    val pages = listOf(SCAN_PAGE_1, SCAN_PAGE_2)
    val clean = PDDocument()
    val helv = PDType1Font(Standard14Fonts.FontName.HELVETICA)
    val bold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
    val times = PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN)
    for (lines in pages) {
        val page = PDPage(PDRectangle.A4)
        clean.addPage(page)
        PDPageContentStream(clean, page).use { cs ->
            for ((i, l) in lines.withIndex()) {
                val f = when {
                    i == 0 -> bold
                    l.text.startsWith("Délai") || l.text.startsWith("Ce devis") -> times
                    else -> helv
                }
                cs.beginText(); cs.setFont(f, l.size); cs.newLineAtOffset(l.x, l.baseline); cs.showText(l.text); cs.endText()
            }
        }
    }
    val rendered = (0 until clean.numberOfPages).map { PDFRenderer(clean).renderImageWithDPI(it, 200f, ImageType.RGB) }
    clean.close()

    val random = Random(42)
    PDDocument().use { scan ->
        for ((i, image) in rendered.withIndex()) {
            val w = image.width
            val h = image.height
            val paper = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = paper.createGraphics()
            g.color = Color(246, 241, 230)
            g.fillRect(0, 0, w, h)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            val upside = upsideDownSecondPage && i == 1
            g.rotate(Math.toRadians(skewDegrees + if (upside) 180.0 else 0.0), w / 2.0, h / 2.0)
            // L'encre : on ne garde que le noir de la page imprimee (multiplication).
            g.composite = java.awt.AlphaComposite.SrcOver
            val ink = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until h) for (x in 0 until w) {
                val v = image.getRGB(x, y) and 0xFF
                val alpha = 255 - v
                if (alpha > 0) ink.setRGB(x, y, (alpha shl 24) or 0x2A2622)
            }
            g.drawImage(ink, 0, 0, null)
            g.dispose()
            for (y in 0 until h) for (x in 0 until w) {
                val noise = random.nextInt(-14, 15)
                val c = paper.getRGB(x, y)
                fun ch(v: Int) = (v + noise).coerceIn(0, 255)
                paper.setRGB(x, y, (ch(c shr 16 and 0xFF) shl 16) or (ch(c shr 8 and 0xFF) shl 8) or ch(c and 0xFF))
            }
            val page = PDPage(PDRectangle.A4)
            scan.addPage(page)
            val jpeg = JPEGFactory.createFromImage(scan, paper, 0.8f)
            PDPageContentStream(scan, page).use { it.drawImage(jpeg, 0f, 0f, PDRectangle.A4.width, PDRectangle.A4.height) }
        }
        scan.save(file)
    }
    return file.absolutePath
}
