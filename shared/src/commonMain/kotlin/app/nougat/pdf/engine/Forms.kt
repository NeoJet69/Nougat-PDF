package app.nougat.pdf.engine

import kotlin.math.max
import kotlin.math.min

/** Les sortes de champs de formulaire que Nougat sait remplir. */
enum class FieldKind { Text, CheckBox, Radio, Choice }

/**
 * Un champ de formulaire PDF (AcroForm), tel qu'il apparait sur une page. Un meme champ peut
 * apparaitre plusieurs fois (boutons radio d'un groupe, champ repete) : un [FormField] par endroit.
 */
data class FormField(
    val page: Int,
    /** Nom complet du champ, unique dans le document : c'est lui qu'on modifie. */
    val name: String,
    /** Ce qu'on montre a l'utilisateur : l'info-bulle du champ si elle existe, sinon son nom court. */
    val label: String,
    val kind: FieldKind,
    /** Emplacement, en fractions de la page affichee. */
    val rect: NormRect,
    /** Texte saisi (champ texte), valeur choisie (liste), ou etat (case, radio). */
    val value: String,
    /** Case ou bouton radio : coche a cet endroit. */
    val checked: Boolean = false,
    /** Case ou bouton radio : la valeur qui veut dire « coche » pour cet endroit. */
    val onValue: String? = null,
    /** Liste : les choix affiches... */
    val options: List<String> = emptyList(),
    /** ...et la valeur enregistree pour chacun (souvent identique). */
    val optionValues: List<String> = options,
    val multiline: Boolean = false,
    val readOnly: Boolean = false,
    /** Nombre maximal de caracteres (0 = pas de limite). */
    val maxLength: Int = 0,
    /** Taille du texte en points (0 = automatique). */
    val fontSize: Float = 0f,
) {
    /** Ce que la liste affiche pour la valeur actuelle. */
    val displayValue: String
        get() = if (kind == FieldKind.Choice) options.getOrNull(optionValues.indexOf(value)) ?: value else value
}

/** Valeur d'une case decochee, comme dans le format PDF. */
const val FIELD_OFF = "Off"

/** Un champ qu'on ne peut pas remplir (lecture seule, caractere absent de la police...). */
class FormFieldException(message: String) : Exception(message)

/**
 * Rectangle du fichier PDF (repere de la page, origine en bas a gauche, en points) vers
 * un [NormRect] de la page affichee : l'inverse de [PageGeometry.displayToPdfMatrix].
 */
fun PageGeometry.pdfRectToNorm(llx: Float, lly: Float, urx: Float, ury: Float): NormRect {
    fun toDisplay(px: Float, py: Float): Pair<Float, Float> = when (rotation) {
        90 -> (py - y0) to (x0 + width - px)
        180 -> (x0 + width - px) to (y0 + height - py)
        270 -> (y0 + height - py) to (px - x0)
        else -> (px - x0) to (py - y0)
    }
    val (ax, ay) = toDisplay(llx, lly)
    val (bx, by) = toDisplay(urx, ury)
    val w = displayWidth.takeIf { it > 0f } ?: 1f
    val h = displayHeight.takeIf { it > 0f } ?: 1f
    return NormRect(
        left = min(ax, bx) / w,
        top = 1f - max(ay, by) / h,
        right = max(ax, bx) / w,
        bottom = 1f - min(ay, by) / h,
    )
}

/** Le champ suivant pour la touche Tab : de haut en bas, puis de gauche a droite. */
fun nextFieldAfter(current: FormField, fields: List<FormField>): FormField? {
    val ordered = fields.filter { !it.readOnly && it.kind == FieldKind.Text }
        .sortedWith(compareBy<FormField>({ it.page }, { (it.rect.top * 200).toInt() }, { it.rect.left }))
    val index = ordered.indexOfFirst { it.name == current.name && it.rect == current.rect }
    return if (index >= 0) ordered.getOrNull(index + 1) else null
}
