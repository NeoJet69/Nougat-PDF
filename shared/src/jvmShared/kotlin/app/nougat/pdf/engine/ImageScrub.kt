package app.nougat.pdf.engine

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/*
 * Effacer vraiment une ligne de scan : recouvrir la ligne ne suffit pas, l'image d'origine resterait
 * dans le fichier sous le cache. Les pixels de la zone sont donc repeints dans l'image elle-meme,
 * de la couleur du papier. Ici, le calcul commun : ou tombe la zone dans les pixels de l'image.
 */

/** Une zone a repeindre, dans l'espace de la page PDF (points, origine en bas a gauche), et sa couleur. */
internal class ScrubArea(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val rgb: Int)

/**
 * La zone [r] (fractions de la page affichee, agrandie de [pad] points, [padTop] en haut) dans
 * l'espace de la page PDF, en tenant compte de la rotation de la page.
 */
internal fun scrubArea(geometry: PageGeometry, r: NormRect, pad: Float, padTop: Float, rgb: Int): ScrubArea {
    val w = geometry.displayWidth
    val h = geometry.displayHeight
    val m = geometry.displayToPdfMatrix()
    val left = r.left * w - pad
    val right = r.right * w + pad
    val bottom = h - (r.bottom * h + pad)
    val top = h - (r.top * h - padTop)
    val xs = FloatArray(4)
    val ys = FloatArray(4)
    var i = 0
    for (x in floatArrayOf(left, right)) for (y in floatArrayOf(bottom, top)) {
        xs[i] = m[0] * x + m[2] * y + m[4]
        ys[i] = m[1] * x + m[3] * y + m[5]
        i++
    }
    return ScrubArea(xs.min(), ys.min(), xs.max(), ys.max(), rgb)
}

/**
 * La zone [a] en pixels (gauche, haut, droite, bas) dans une image de [iw] x [ih] pixels posee par la
 * matrice [ctm] (a, b, c, d, e, f : le carre unite de l'image vers la page). Null si elle n'y tombe pas.
 */
internal fun pixelRect(ctm: FloatArray, iw: Int, ih: Int, a: ScrubArea): IntArray? {
    val det = ctm[0] * ctm[3] - ctm[1] * ctm[2]
    if (det == 0f) return null
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (x in floatArrayOf(a.x0, a.x1)) for (y in floatArrayOf(a.y0, a.y1)) {
        val dx = x - ctm[4]
        val dy = y - ctm[5]
        val u = (ctm[3] * dx - ctm[2] * dy) / det
        val v = (-ctm[1] * dx + ctm[0] * dy) / det
        val px = u * iw
        val py = (1f - v) * ih
        minX = min(minX, px); maxX = max(maxX, px)
        minY = min(minY, py); maxY = max(maxY, py)
    }
    val l = floor(minX).toInt().coerceIn(0, iw)
    val t = floor(minY).toInt().coerceIn(0, ih)
    val r = ceil(maxX).toInt().coerceIn(0, iw)
    val b = ceil(maxY).toInt().coerceIn(0, ih)
    return if (r > l && b > t) intArrayOf(l, t, r, b) else null
}
