package app.nougat.pdf.engine

import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox
import org.apache.pdfbox.pdmodel.interactive.form.PDRadioButton
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField
import java.awt.Color
import java.io.File

/**
 * Un vrai petit formulaire PDF (AcroForm) : champs texte, champ sur plusieurs lignes, champ en
 * lecture seule, case a cocher, boutons radio et liste deroulante.
 * [withFont] faux : la police citee par les champs n'est pas fournie (formulaire « mal fait »).
 */
internal fun makeSampleForm(file: File, withFont: Boolean = true, rotation: Int = 0): String {
    PDDocument().use { doc ->
        val page = PDPage(PDRectangle.A4)
        page.rotation = rotation
        doc.addPage(page)
        val helv = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        val bold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
        PDPageContentStream(doc, page).use { cs ->
            fun t(f: PDType1Font, size: Float, x: Float, y: Float, text: String, color: Color = Color(0x33, 0x33, 0x33)) {
                cs.beginText(); cs.setNonStrokingColor(color); cs.setFont(f, size); cs.newLineAtOffset(x, y); cs.showText(text); cs.endText()
            }
            cs.setNonStrokingColor(Color(0xCC, 0x54, 0x2C)); cs.addRect(0f, 780f, 595f, 62f); cs.fill()
            t(bold, 22f, 50f, 802f, "FORMULAIRE D'INSCRIPTION", Color.WHITE)
            t(helv, 11f, 50f, 760f, "Club des amis de Nougat — saison 2026", Color.GRAY)
            t(bold, 10f, 50f, 732f, "Nom complet")
            t(bold, 10f, 380f, 732f, "N° de dossier")
            t(bold, 10f, 50f, 682f, "Adresse e-mail")
            t(bold, 10f, 380f, 682f, "Pays")
            t(bold, 10f, 50f, 632f, "Formule")
            t(helv, 11f, 70f, 611f, "Mensuelle")
            t(helv, 11f, 180f, 611f, "Annuelle")
            t(bold, 10f, 50f, 582f, "Remarques")
            t(helv, 11f, 70f, 473f, "J'accepte le règlement du club")
            t(helv, 9f, 50f, 120f, "Merci ! Votre carte de membre vous sera envoyée par e-mail.", Color.GRAY)
            // Cadres des champs, comme sur un vrai formulaire imprime
            cs.setStrokingColor(Color(0xC8, 0xC8, 0xC8))
            cs.setLineWidth(0.8f)
            for (r in listOf(
                floatArrayOf(50f, 705f, 300f, 22f), floatArrayOf(380f, 705f, 165f, 22f),
                floatArrayOf(50f, 655f, 300f, 22f), floatArrayOf(380f, 655f, 165f, 22f),
                floatArrayOf(50f, 500f, 495f, 74f),
            )) {
                cs.addRect(r[0], r[1], r[2], r[3])
            }
            cs.stroke()
        }

        val form = PDAcroForm(doc)
        doc.documentCatalog.acroForm = form
        val resources = PDResources()
        resources.put(COSName.getPDFName("Helv"), helv)
        form.defaultResources = resources
        form.defaultAppearance = "/Helv 0 Tf 0 g"

        fun place(w: PDAnnotationWidget, x: Float, y: Float, width: Float, height: Float) {
            w.rectangle = PDRectangle(x, y, width, height)
            w.page = page
            w.isPrinted = true
            page.annotations.add(w)
        }

        fun text(name: String, label: String?, da: String, x: Float, y: Float, w: Float, h: Float, value: String? = null): PDTextField {
            val f = PDTextField(form)
            f.partialName = name
            if (label != null) f.alternateFieldName = label
            f.defaultAppearance = da
            form.fields.add(f)
            place(f.widgets[0], x, y, w, h)
            if (value != null) f.setValue(value)
            return f
        }

        text("nom", "Nom complet", "/Helv 12 Tf 0 g", 50f, 705f, 300f, 22f, "Jean Dupont")
        text("email", "Adresse e-mail", "/Helv 11 Tf 0 g", 50f, 655f, 300f, 22f)
        text("dossier", "N° de dossier", "/Helv 11 Tf 0 g", 380f, 705f, 165f, 22f, "2026-118").isReadOnly = true
        text("remarques", "Remarques", "/Helv 10 Tf 0 g", 50f, 500f, 495f, 74f).isMultiline = true

        val accord = PDCheckBox(form)
        accord.partialName = "accord"
        accord.alternateFieldName = "J'accepte le règlement"
        form.fields.add(accord)
        val cw = accord.widgets[0]
        place(cw, 50f, 470f, 14f, 14f)
        cw.appearance = buttonAppearance(doc, "Oui", 14f, round = false)
        cw.appearanceState = COSName.Off
        accord.cosObject.setItem(COSName.V, COSName.Off)

        val formule = PDRadioButton(form)
        formule.partialName = "formule"
        formule.alternateFieldName = "Formule"
        form.fields.add(formule)
        val monthly = PDAnnotationWidget()
        val yearly = PDAnnotationWidget()
        formule.widgets = listOf(monthly, yearly)
        place(monthly, 50f, 608f, 14f, 14f)
        place(yearly, 160f, 608f, 14f, 14f)
        monthly.appearance = buttonAppearance(doc, "Mensuelle", 14f, round = true)
        yearly.appearance = buttonAppearance(doc, "Annuelle", 14f, round = true)
        monthly.appearanceState = COSName.getPDFName("Mensuelle")
        yearly.appearanceState = COSName.Off
        formule.cosObject.setItem(COSName.V, COSName.getPDFName("Mensuelle"))

        val pays = PDComboBox(form)
        pays.partialName = "pays"
        pays.alternateFieldName = "Pays"
        pays.defaultAppearance = "/Helv 11 Tf 0 g"
        form.fields.add(pays)
        place(pays.widgets[0], 380f, 655f, 165f, 22f)
        pays.setOptions(listOf("FR", "CH", "BE"), listOf("France", "Suisse", "Belgique"))
        pays.setValue("FR")

        if (!withFont) form.defaultResources = PDResources()
        doc.save(file)
    }
    return file.absolutePath
}

/** Apparences « coche » et « Off » d'une case ([round] : bouton radio). */
private fun buttonAppearance(doc: PDDocument, onName: String, size: Float, round: Boolean): PDAppearanceDictionary {
    fun stream(checked: Boolean): PDAppearanceStream {
        val s = PDAppearanceStream(doc)
        s.bBox = PDRectangle(size, size)
        s.resources = PDResources()
        PDPageContentStream(doc, s).use { cs ->
            cs.setStrokingColor(0.35f, 0.35f, 0.35f)
            if (round) {
                circle(cs, size / 2, size / 2, size / 2 - 0.75f); cs.stroke()
                if (checked) {
                    cs.setNonStrokingColor(0.2f, 0.2f, 0.2f)
                    circle(cs, size / 2, size / 2, size / 4); cs.fill()
                }
            } else {
                cs.addRect(0.5f, 0.5f, size - 1f, size - 1f); cs.stroke()
                if (checked) {
                    cs.setLineWidth(1.6f)
                    cs.moveTo(3f, 7f); cs.lineTo(6f, 3.5f); cs.lineTo(11f, 11f); cs.stroke()
                }
            }
        }
        return s
    }
    val normal = COSDictionary()
    normal.setItem(COSName.getPDFName(onName), stream(true))
    normal.setItem(COSName.Off, stream(false))
    return PDAppearanceDictionary().apply { cosObject.setItem(COSName.N, normal) }
}

private fun circle(cs: PDPageContentStream, cx: Float, cy: Float, r: Float) {
    val k = 0.5523f * r
    cs.moveTo(cx + r, cy)
    cs.curveTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r)
    cs.curveTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy)
    cs.curveTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r)
    cs.curveTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy)
    cs.closePath()
}
