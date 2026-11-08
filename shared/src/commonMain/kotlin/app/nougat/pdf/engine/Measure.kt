package app.nougat.pdf.engine

import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sqrt

/*
 * Metre sur plan : longueurs (lignes brisees) et surfaces (polygones) mesurees sur une page, avec
 * l'echelle du plan (1:100...) ou une echelle etalonnee sur une cote connue.
 */

enum class MeasureKind { Length, Area }

/** Une mesure sur la page [page] : points en fractions de la page affichee (0..1). */
data class Measurement(val page: Int, val kind: MeasureKind, val points: List<NPoint>) {
    /** Assez de points pour donner un resultat. */
    val complete: Boolean get() = points.size >= if (kind == MeasureKind.Area) 3 else 2
}

object Measures {
    /** Un point PDF (1/72 de pouce) en metres, sur le papier. */
    const val POINT_METERS = 0.0254 / 72.0

    /** Les echelles courantes des plans. */
    val COMMON_RATIOS = listOf(20, 25, 50, 100, 200, 250, 500, 1000)

    /** Metres reels par point PDF, pour un plan a l'echelle 1:[ratio] imprime a sa taille. */
    fun metersPerPoint(ratio: Double) = POINT_METERS * ratio

    /** L'echelle 1:x qui correspond a [metersPerPoint] (pour l'afficher apres un etalonnage). */
    fun ratioOf(metersPerPoint: Double): Long = (metersPerPoint / POINT_METERS).roundToLong()

    /** Longueur de la ligne brisee en points PDF ([closed] : revenir au premier point). */
    fun lengthPoints(points: List<NPoint>, pageWidth: Float, pageHeight: Float, closed: Boolean = false): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        val path = if (closed && points.size > 2) points + points.first() else points
        for (i in 1 until path.size) {
            val dx = (path[i].x - path[i - 1].x).toDouble() * pageWidth
            val dy = (path[i].y - path[i - 1].y).toDouble() * pageHeight
            total += sqrt(dx * dx + dy * dy)
        }
        return total
    }

    /** Surface du polygone en points PDF carres (formule du lacet). */
    fun areaPoints(points: List<NPoint>, pageWidth: Float, pageHeight: Float): Double {
        if (points.size < 3) return 0.0
        var sum = 0.0
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            sum += a.x.toDouble() * pageWidth * b.y * pageHeight - b.x.toDouble() * pageWidth * a.y * pageHeight
        }
        return abs(sum) / 2.0
    }

    /** Longueur reelle en metres (perimetre pour une surface). */
    fun meters(m: Measurement, size: PageSize, metersPerPoint: Double) =
        lengthPoints(m.points, size.width, size.height, closed = m.kind == MeasureKind.Area) * metersPerPoint

    /** Surface reelle en metres carres. */
    fun squareMeters(m: Measurement, size: PageSize, metersPerPoint: Double) =
        areaPoints(m.points, size.width, size.height) * metersPerPoint * metersPerPoint

    /** L'echelle ecrite sur le plan (« Échelle 1:50 », « M 1:100 », « 1/200 »), ou null. */
    fun findScale(text: String): Int? {
        val labelled = Regex("""(?i)(?:[ée]chelle|ma(?:ß|ss)stab|scala|scale|\bM)\s*[:.]?\s*1\s*[:/]\s*(\d{1,5})\b""")
        labelled.find(text)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 2..10000 }?.let { return it }
        val bare = Regex("""(?<![\d.,/:])1\s?:\s?(\d{2,4})(?![\d.,:])""")
        return bare.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.firstOrNull { it in COMMON_RATIOS || it in 10..5000 && it % 5 == 0 }
    }

    /** « 3,45 m », « 85,0 cm » (virgule ou point selon la langue). */
    fun formatLength(meters: Double, comma: Boolean): String {
        val text = if (abs(meters) < 1.0) decimal(meters * 100, 1) + " cm" else decimal(meters, 2) + " m"
        return if (comma) text.replace('.', ',') else text
    }

    /** « 12,30 m² ». */
    fun formatArea(squareMeters: Double, comma: Boolean): String {
        val text = decimal(squareMeters, 2) + " m²"
        return if (comma) text.replace('.', ',') else text
    }

    private fun decimal(value: Double, digits: Int): String {
        var factor = 1L
        repeat(digits) { factor *= 10 }
        val scaled = (abs(value) * factor).roundToLong()
        val units = scaled / factor
        val frac = (scaled % factor).toString().padStart(digits, '0')
        val sign = if (value < 0 && scaled != 0L) "-" else ""
        return if (digits == 0) "$sign$units" else "$sign$units.$frac"
    }

    /** « 5 », « 5,20 », « 5.2 » → 5.2 ; null si ce n'est pas une longueur positive. */
    fun parseNumber(text: String): Double? = text.trim().replace(',', '.').replace(" ", "").toDoubleOrNull()?.takeIf { it > 0 }
}
