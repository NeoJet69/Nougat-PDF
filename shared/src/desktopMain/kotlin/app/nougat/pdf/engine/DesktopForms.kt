package app.nougat.pdf.engine

import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox
import org.apache.pdfbox.pdmodel.interactive.form.PDChoice
import org.apache.pdfbox.pdmodel.interactive.form.PDField
import org.apache.pdfbox.pdmodel.interactive.form.PDRadioButton
import org.apache.pdfbox.pdmodel.interactive.form.PDTerminalField
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField
import org.apache.pdfbox.pdmodel.interactive.form.PDVariableText
import java.util.IdentityHashMap
import app.nougat.pdf.i18n.Str

/** Vrai si le PDF contient au moins un champ a remplir. */
internal fun documentHasForm(doc: PDDocument): Boolean {
    val form = doc.documentCatalog.acroForm ?: return false
    return form.fieldTree.any { it is PDTerminalField && fieldKind(it) != null && !it.isReadOnly }
}

/** Les champs de formulaire de la page [pageIndex], avec leur emplacement a l'ecran. */
internal fun pageFormFields(doc: PDDocument, pageIndex: Int): List<FormField> {
    val form = doc.documentCatalog.acroForm ?: return emptyList()
    val page = doc.getPage(pageIndex)
    // Un widget ne connait pas son champ : on fait le lien a partir des champs.
    val owners = IdentityHashMap<COSDictionary, PDTerminalField>()
    for (field in form.fieldTree) {
        if (field is PDTerminalField) for (widget in field.widgets) owners[widget.cosObject] = field
    }
    val box = page.cropBox
    val geometry = PageGeometry(box.lowerLeftX, box.lowerLeftY, box.width, box.height, normalizeRotation(page.rotation))
    val result = ArrayList<FormField>()
    for (annotation in page.annotations) {
        if (annotation !is PDAnnotationWidget) continue
        if (annotation.isHidden || annotation.isNoView || annotation.isInvisible) continue
        val field = owners[annotation.cosObject] ?: continue
        val kind = fieldKind(field) ?: continue
        val r = annotation.rectangle ?: continue
        if (r.width < 1f || r.height < 1f) continue
        val rect = geometry.pdfRectToNorm(r.lowerLeftX, r.lowerLeftY, r.upperRightX, r.upperRightY)
        val label = field.alternateFieldName?.takeIf { it.isNotBlank() } ?: field.partialName ?: field.fullyQualifiedName
        val base = FormField(
            page = pageIndex,
            name = field.fullyQualifiedName,
            label = label,
            kind = kind,
            rect = rect,
            value = field.valueAsString ?: "",
            readOnly = field.isReadOnly,
            fontSize = (field as? PDVariableText)?.let { fontSizeOf(it.defaultAppearance) } ?: 0f,
        )
        result += when (field) {
            is PDTextField -> base.copy(multiline = field.isMultiline, maxLength = field.maxLen.coerceAtLeast(0))
            is PDCheckBox, is PDRadioButton -> {
                val on = onStateOf(annotation) ?: (field as? PDCheckBox)?.onValue
                base.copy(value = field.valueAsString ?: FIELD_OFF, onValue = on, checked = on != null && field.valueAsString == on)
            }
            is PDChoice -> {
                val display = field.optionsDisplayValues
                val export = field.optionsExportValues.takeIf { it.size == display.size } ?: display
                base.copy(value = field.value.firstOrNull() ?: "", options = display, optionValues = export)
            }
            else -> continue
        }
    }
    return result
}

/**
 * Remplit le champ [name] : texte, valeur choisie dans une liste, valeur « cochee » d'une case
 * ou d'un bouton radio, ou [FIELD_OFF] pour decocher.
 */
internal fun setFormFieldValue(doc: PDDocument, name: String, value: String) {
    val form = doc.documentCatalog.acroForm ?: throw FormFieldException(Str.ErrNoForm)
    val field = form.getField(name) ?: throw FormFieldException(Str.ErrFieldGone)
    if (field.isReadOnly) throw FormFieldException(Str.FieldReadOnly)
    try {
        when (field) {
            is PDCheckBox -> if (value == FIELD_OFF || value.isEmpty()) field.unCheck() else field.setValue(value)
            is PDRadioButton -> field.setValue(value)
            is PDTextField, is PDChoice -> {
                ensureFieldFont(form, field as PDVariableText)
                if (field is PDTextField) {
                    val limit = field.maxLen
                    field.setValue(if (limit > 0) value.take(limit) else value)
                } else {
                    (field as PDChoice).setValue(value)
                }
            }
            else -> throw FormFieldException(Str.ErrFieldUnsupported)
        }
    } catch (e: FormFieldException) {
        throw e
    } catch (e: IllegalArgumentException) {
        throw FormFieldException(Str.ErrFieldCharset, e.message ?: "?")
    }
    // Certains PDF demandent au lecteur de redessiner les champs : on le fait nous-memes.
    if (form.needAppearances) runCatching { form.refreshAppearances(listOf<PDField>(field)) }
}

private fun fieldKind(field: PDTerminalField): FieldKind? = when (field) {
    is PDTextField -> FieldKind.Text
    is PDCheckBox -> FieldKind.CheckBox
    is PDRadioButton -> FieldKind.Radio
    is PDChoice -> FieldKind.Choice
    else -> null // boutons, signatures
}

/** La valeur « cochee » d'un widget : le nom de son apparence qui n'est pas « Off ». */
private fun onStateOf(widget: PDAnnotationWidget): String? {
    val normal = widget.appearance?.normalAppearance ?: return null
    if (!normal.isSubDictionary) return null
    return normal.subDictionary.keys.map { it.name }.firstOrNull { it != FIELD_OFF }
}

/** La taille dans « /Helv 10 Tf » (0 = automatique). */
private fun fontSizeOf(da: String?): Float =
    da?.let { Regex("""([\d.]+)\s+Tf""").find(it)?.groupValues?.get(1)?.toFloatOrNull() } ?: 0f

/**
 * Beaucoup de formulaires citent une police (« /Helv ») sans la fournir : PDFBox ne peut alors
 * pas dessiner la valeur. On ajoute Helvetica sous ce nom.
 */
private fun ensureFieldFont(form: PDAcroForm, field: PDVariableText) {
    val da = field.defaultAppearance ?: form.defaultAppearance ?: return
    val fontName = Regex("""/([^\s/]+)\s+[\d.]+\s+Tf""").find(da)?.groupValues?.get(1) ?: return
    val resources = form.defaultResources ?: PDResources().also { form.defaultResources = it }
    val key = COSName.getPDFName(fontName)
    if (runCatching { resources.getFont(key) }.getOrNull() == null) {
        resources.put(key, PDType1Font(Standard14Fonts.FontName.HELVETICA))
    }
}
