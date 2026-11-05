package app.nougat.pdf.engine

import kotlin.math.max
import kotlin.math.min

/**
 * Un PDF ouvert pour modification. Chaque plateforme l'implemente avec sa version de PDFBox ;
 * toute la logique (annotations, ordre des pages...) est ecrite une seule fois, ici et dans l'editeur.
 */
interface EditableDoc {
    val pageCount: Int

    fun geometry(index: Int): PageGeometry

    /** Rotation absolue (0, 90, 180 ou 270). */
    fun setRotation(index: Int, degrees: Int)

    /**
     * Nouvel ordre des pages : liste d'index actuels, sans doublon. Une page absente est supprimee.
     */
    fun reorder(order: List<Int>)

    /** Ajoute une page blanche de [width] x [height] points a la position [at]. */
    fun insertBlankPage(at: Int, width: Float, height: Float)

    /** Ajoute a la fin toutes les pages du PDF [path]. */
    fun appendPdf(path: String, password: String? = null)

    /**
     * Ajoute a la fin une page par image (photo, capture d'ecran, scan) : l'image entiere,
     * centree sur une page A4 dans le meme sens qu'elle (portrait ou paysage).
     */
    fun appendImages(paths: List<String>)

    /** Dessine sur la page [index], dans le repere de la page telle qu'on la voit (voir [PageCanvas]). */
    fun draw(index: Int, block: (PageCanvas) -> Unit)

    /** Protege par mot de passe, ou retire la protection si [password] est null. */
    fun setPassword(password: String?)

    /**
     * Remplace le texte de la ligne [block] (trouvee par une analyse precedente de la meme page)
     * par [newText], au meme endroit et dans le meme style.
     * @throws TextEditException si la ligne n'est pas modifiable.
     */
    fun replaceText(block: TextBlock, newText: String): TextEditResult

    /**
     * Remplit le champ de formulaire [name] (voir [FormField]) : texte, choix d'une liste, valeur
     * « cochee » d'une case ou d'un bouton radio, ou [FIELD_OFF] pour decocher.
     * @throws FormFieldException si le champ ne peut pas prendre cette valeur.
     */
    fun setFieldValue(name: String, value: String)

    /**
     * Reconnait le texte des pages scannees (OCR) et le pose, invisible, sur l'image.
     * [progress] (pages faites, total) renvoie faux pour arreter.
     */
    fun recognizeText(options: OcrOptions, progress: (Int, Int) -> Boolean): OcrSummary =
        throw UnsupportedOperationException("OCR indisponible")

    /**
     * Copie traduite : chaque paragraphe de [paragraphs] (trouve par une analyse precedente de la
     * page [index]) est remplace par sa traduction, au meme endroit et dans un style proche. Le texte
     * d'origine est retire (ou recouvert de la couleur du papier sur un scan). [language] : code de
     * la langue de la traduction (police, sens d'ecriture). [eraseImages] : sur un scan, repeindre
     * aussi les pixels de l'image sous les lignes (effacement), au lieu de seulement les recouvrir.
     * @throws TextEditException si aucune police ne contient les lettres de la traduction.
     */
    fun writeTranslation(index: Int, paragraphs: List<TranslatedParagraph>, language: String, eraseImages: Boolean = false): Unit =
        throw UnsupportedOperationException("Traduction indisponible")
}

/**
 * Efface vraiment les lignes [blocks] de la page [index] (des prix avant d'envoyer un document) :
 * leur texte est retire du PDF, il ne se retrouve plus en cherchant ni en copiant. Sur un scan, les
 * pixels de l'image sous la ligne sont repeints de la couleur du papier.
 */
fun EditableDoc.deleteTextBlocks(index: Int, blocks: List<TextBlock>) {
    if (blocks.isEmpty()) return
    writeTranslation(index, blocks.map { TranslatedParagraph(TextParagraph.of(it), "") }, "fr", eraseImages = true)
}

/**
 * Surface de dessin d'une page. Coordonnees en points, origine en BAS a gauche de la page
 * telle qu'elle s'affiche (rotation deja prise en compte).
 */
interface PageCanvas {
    /** Trait passant par les points (x0, y0, x1, y1, ...). */
    fun stroke(points: FloatArray, argb: Int, width: Float, opacity: Float = 1f)

    fun fillRect(x: Float, y: Float, width: Float, height: Float, argb: Int, opacity: Float = 1f)

    /** Une ligne de texte en Helvetica ; les caracteres non pris en charge deviennent « ? ». */
    fun text(x: Float, baseline: Float, text: String, size: Float, argb: Int)
}

/** Position de la page dans le fichier : cadre visible (cropBox) et rotation. */
data class PageGeometry(val x0: Float, val y0: Float, val width: Float, val height: Float, val rotation: Int) {
    val rotated: Boolean get() = rotation % 180 != 0
    val displayWidth: Float get() = if (rotated) height else width
    val displayHeight: Float get() = if (rotated) width else height

    /**
     * Matrice [a, b, c, d, e, f] qui passe du repere « page affichee, origine en bas a gauche »
     * au repere du fichier PDF : une rotation de [rotation] degres plus un decalage.
     */
    fun displayToPdfMatrix(): FloatArray = when (rotation) {
        90 -> floatArrayOf(0f, 1f, -1f, 0f, x0 + width, y0)
        180 -> floatArrayOf(-1f, 0f, 0f, -1f, x0 + width, y0 + height)
        270 -> floatArrayOf(0f, -1f, 1f, 0f, x0, y0 + height)
        else -> floatArrayOf(1f, 0f, 0f, 1f, x0, y0)
    }
}

fun normalizeRotation(degrees: Int): Int = ((degrees % 360) + 360) % 360

/** Format A4 en points. */
const val A4_WIDTH = 595.28f
const val A4_HEIGHT = 841.89f

/** Taille de page et place de l'image (x, y, largeur, hauteur) pour une image [w] x [h] pixels. */
fun imagePageLayout(w: Int, h: Int): FloatArray {
    val portrait = h >= w
    val pageW = if (portrait) A4_WIDTH else A4_HEIGHT
    val pageH = if (portrait) A4_HEIGHT else A4_WIDTH
    val scale = minOf(pageW / w.coerceAtLeast(1), pageH / h.coerceAtLeast(1))
    val iw = w * scale
    val ih = h * scale
    return floatArrayOf(pageW, pageH, (pageW - iw) / 2f, (pageH - ih) / 2f, iw, ih)
}

// ---- Annotations ajoutees par l'utilisateur ---------------------------------------------------

/** Point en fractions de la page affichee (0..1), origine en haut a gauche. */
data class NPoint(val x: Float, val y: Float)

/** Ce que l'utilisateur ajoute sur une page, avant d'etre grave dans le PDF. */
sealed interface Mark {
    val page: Int
    fun bounds(): NormRect
}

/** Trait de stylo ou de surligneur. [width] en points. */
data class InkMark(
    override val page: Int,
    val points: List<NPoint>,
    val argb: Int,
    val width: Float,
    val opacity: Float = 1f,
) : Mark {
    override fun bounds() = boundsOf(points)
}

/** Texte libre. [x], [y] : coin haut gauche. [size] en points. */
data class TextMark(
    override val page: Int,
    val x: Float,
    val y: Float,
    val text: String,
    val size: Float,
    val argb: Int,
    /** Taille de la page affichee en points, pour estimer l'encombrement du texte. */
    val pageWidth: Float,
    val pageHeight: Float,
) : Mark {
    val lines: List<String> get() = text.split('\n')

    override fun bounds(): NormRect {
        val longest = lines.maxOfOrNull { it.length } ?: 0
        val w = longest * size * 0.55f / pageWidth
        val h = lines.size * size * LINE_HEIGHT / pageHeight
        return NormRect(x, y, x + w, y + h)
    }

    companion object {
        const val LINE_HEIGHT = 1.2f
        const val ASCENT = 0.8f
    }
}

/** Rectangle plein : masquer du texte (blanc) ou cacher une zone (noir). */
data class BoxMark(
    override val page: Int,
    val rect: NormRect,
    val argb: Int,
) : Mark {
    override fun bounds() = rect
}

/** Signature dessinee a la main, placee dans [rect]. Ses traits sont en fractions de [rect]. */
data class SignatureMark(
    override val page: Int,
    val rect: NormRect,
    val strokes: List<List<NPoint>>,
    val argb: Int,
    val width: Float = 1.6f,
) : Mark {
    override fun bounds() = rect
}

private fun boundsOf(points: List<NPoint>): NormRect {
    if (points.isEmpty()) return NormRect(0f, 0f, 0f, 0f)
    var l = 1f; var t = 1f; var r = 0f; var b = 0f
    for (p in points) {
        l = min(l, p.x); t = min(t, p.y); r = max(r, p.x); b = max(b, p.y)
    }
    return NormRect(l, t, r, b)
}

/** Grave les annotations dans le document (en un seul passage par page). */
fun EditableDoc.burn(marks: List<Mark>) {
    for ((page, pageMarks) in marks.groupBy { it.page }) {
        if (page !in 0 until pageCount) continue
        val g = geometry(page)
        val w = g.displayWidth
        val h = g.displayHeight
        fun px(x: Float) = x * w
        fun py(y: Float) = (1f - y) * h // fraction depuis le haut -> points depuis le bas
        draw(page) { canvas ->
            for (mark in pageMarks) when (mark) {
                is BoxMark -> {
                    val r = mark.rect
                    canvas.fillRect(px(r.left), py(r.bottom), (r.right - r.left) * w, (r.bottom - r.top) * h, mark.argb)
                }
                is InkMark -> {
                    val pts = FloatArray(mark.points.size * 2)
                    mark.points.forEachIndexed { i, p -> pts[2 * i] = px(p.x); pts[2 * i + 1] = py(p.y) }
                    canvas.stroke(pts, mark.argb, mark.width, mark.opacity)
                }
                is TextMark -> {
                    mark.lines.forEachIndexed { i, line ->
                        if (line.isNotEmpty()) {
                            val baselineFromTop = mark.y * h + mark.size * (TextMark.ASCENT + i * TextMark.LINE_HEIGHT)
                            canvas.text(px(mark.x), h - baselineFromTop, line, mark.size, mark.argb)
                        }
                    }
                }
                is SignatureMark -> {
                    val r = mark.rect
                    for (stroke in mark.strokes) {
                        val pts = FloatArray(stroke.size * 2)
                        stroke.forEachIndexed { i, p ->
                            pts[2 * i] = px(r.left + p.x * (r.right - r.left))
                            pts[2 * i + 1] = py(r.top + p.y * (r.bottom - r.top))
                        }
                        canvas.stroke(pts, mark.argb, mark.width)
                    }
                }
            }
        }
    }
}
