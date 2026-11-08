package app.nougat.pdf.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/*
 * Outils de pages communs a l'ordinateur et a Android : trouver les marges blanches d'une page
 * (rogner) et l'inclinaison d'un scan (redresser), sur l'image de la page ; compresser les images.
 */

internal object PageTools {
    /** Seuil (0..255) en dessous duquel un pixel compte comme de l'encre. */
    private fun inkThreshold(img: GrayImage): Int {
        var sum = 0L
        for (b in img.pixels) sum += b.toInt() and 0xFF
        val mean = (sum / img.pixels.size).toInt()
        return (mean * 0.75f).toInt().coerceIn(60, 200)
    }

    /**
     * Le cadre du contenu (gauche, haut, droite, bas en fractions de l'image), sans les marges
     * blanches ; null pour une page vide. Les taches isolees (poussiere) sont ignorees.
     */
    fun contentBounds(img: GrayImage): NormRect? {
        val t = inkThreshold(img)
        val w = img.width
        val h = img.height
        val cols = IntArray(w)
        val rows = IntArray(h)
        for (y in 0 until h) for (x in 0 until w) {
            if (img[x, y] < t) {
                cols[x]++
                rows[y]++
            }
        }
        // Une colonne ou une ligne compte si elle a assez d'encre (pas un grain de poussiere).
        val minCol = max(2, h / 400)
        val minRow = max(2, w / 400)
        val left = cols.indexOfFirst { it >= minCol }
        val right = cols.indexOfLast { it >= minCol }
        val top = rows.indexOfFirst { it >= minRow }
        val bottom = rows.indexOfLast { it >= minRow }
        if (left < 0 || top < 0 || right <= left || bottom <= top) return null
        return NormRect(left.toFloat() / w, top.toFloat() / h, (right + 1).toFloat() / w, (bottom + 1).toFloat() / h)
    }

    /**
     * L'inclinaison des lignes du texte, en degres (positif : les lignes descendent vers la droite),
     * entre -[maxDegrees] et +[maxDegrees] ; 0 si la page n'a pas assez de texte pour le dire.
     * Methode du profil de projection : a la bonne inclinaison, les lignes de texte forment des
     * bandes nettes, et la somme des carres de l'histogramme des lignes est la plus grande.
     */
    fun skewDegrees(img: GrayImage, maxDegrees: Float = 6f): Float {
        val t = inkThreshold(img)
        val step = max(1, img.width / 900)
        val xs = ArrayList<Int>()
        val ys = ArrayList<Int>()
        var y = 0
        while (y < img.height) {
            var x = 0
            while (x < img.width) {
                if (img[x, y] < t) {
                    xs.add(x)
                    ys.add(y)
                }
                x += step
            }
            y += step
        }
        if (xs.size < 400) return 0f
        fun score(deg: Float): Double {
            val slope = tan(deg * PI / 180.0)
            val bins = IntArray(img.height + img.width)
            val offset = img.width
            for (i in xs.indices) {
                val row = (ys[i] - xs[i] * slope).toInt() + offset
                if (row in bins.indices) bins[row]++
            }
            var s = 0.0
            for (b in bins) s += b.toDouble() * b
            return s
        }
        var best = 0f
        var bestScore = score(0f)
        var deg = -maxDegrees
        while (deg <= maxDegrees + 1e-3f) {
            val s = score(deg)
            if (s > bestScore) {
                bestScore = s
                best = deg
            }
            deg += 0.25f
        }
        // Affiner autour du meilleur angle.
        var fine = best - 0.25f
        while (fine <= best + 0.25f) {
            val s = score(fine)
            if (s > bestScore) {
                bestScore = s
                best = fine
            }
            fine += 0.05f
        }
        return best
    }

    /**
     * La matrice (a, b, c, d, e, f) qui tourne le contenu de la page de [degrees] (sens contraire des
     * aiguilles d'une montre, a l'ecran) autour du centre de la page affichee, exprimee dans le
     * repere du fichier PDF ([geometry] : cadre et rotation de la page).
     */
    fun straightenMatrix(geometry: PageGeometry, degrees: Float): FloatArray {
        val m = geometry.displayToPdfMatrix()
        val inv = invert(m)
        val r = degrees * PI.toFloat() / 180f
        val cx = geometry.displayWidth / 2f
        val cy = geometry.displayHeight / 2f
        val c = cos(r)
        val s = sin(r)
        // Rotation autour du centre, dans le repere de la page affichee (y vers le haut).
        val rot = floatArrayOf(c, s, -s, c, cx - c * cx + s * cy, cy - s * cx - c * cy)
        // Fichier -> affiche -> tourne -> fichier.
        return multiply(multiply(inv, rot), m)
    }

    /** a puis b (convention PDF : un point est multiplie a gauche). */
    private fun multiply(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[0] * b[0] + a[1] * b[2], a[0] * b[1] + a[1] * b[3],
        a[2] * b[0] + a[3] * b[2], a[2] * b[1] + a[3] * b[3],
        a[4] * b[0] + a[5] * b[2] + b[4], a[4] * b[1] + a[5] * b[3] + b[5],
    )

    private fun invert(m: FloatArray): FloatArray {
        val det = m[0] * m[3] - m[1] * m[2]
        val a = m[3] / det
        val b = -m[1] / det
        val c = -m[2] / det
        val d = m[0] / det
        return floatArrayOf(a, b, c, d, -(m[4] * a + m[5] * c), -(m[4] * b + m[5] * d))
    }

    /** Le cadre a garder en rognant, avec une petite marge ; null s'il n'y a presque rien a rogner. */
    fun cropFrame(bounds: NormRect, pageWidth: Float, pageHeight: Float): NormRect? {
        val pad = 14f
        val l = max(0f, bounds.left - pad / pageWidth)
        val t = max(0f, bounds.top - pad / pageHeight)
        val r = min(1f, bounds.right + pad / pageWidth)
        val b = min(1f, bounds.bottom + pad / pageHeight)
        // Moins de 3 % a gagner en largeur et en hauteur : on laisse la page telle quelle.
        if ((r - l) > 0.97f && (b - t) > 0.97f) return null
        return NormRect(l, t, r, b)
    }

    /** Vrai si l'inclinaison vaut la peine d'etre corrigee. */
    fun worthStraightening(degrees: Float) = abs(degrees) >= 0.2f
}
