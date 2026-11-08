package app.nougat.pdf.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.nougat.pdf.engine.MeasureKind
import app.nougat.pdf.engine.Measurement
import app.nougat.pdf.engine.Measures
import app.nougat.pdf.engine.NPoint
import app.nougat.pdf.engine.PageSize
import kotlin.math.abs

/**
 * Le metre sur plan du document affiche : l'echelle (choisie ou etalonnee sur une cote connue),
 * les mesures terminees et celle en cours. On touche le plan point par point.
 */
class MeasureState {
    /** Le mode Metre est affiche (barre et mesures sur les pages). */
    var active by mutableStateOf(false)
        private set

    var kind by mutableStateOf(MeasureKind.Length)
        private set

    val items = mutableStateListOf<Measurement>()

    /** La mesure en cours (pas encore terminee). */
    var current by mutableStateOf<Measurement?>(null)
        private set

    /** Metres reels par point PDF ; null tant que l'echelle n'est pas choisie. */
    var metersPerPoint by mutableStateOf<Double?>(null)
        private set

    /** L'echelle a ete etalonnee sur une cote (et non choisie dans la liste). */
    var calibrated by mutableStateOf(false)
        private set

    /** On trace la ligne d'etalonnage (deux points sur une cote connue). */
    var calibrating by mutableStateOf(false)
        private set

    /** La ligne d'etalonnage tracee, en attente de sa longueur reelle. */
    var calibrationLine by mutableStateOf<Measurement?>(null)
        private set

    /** Fenetre « Echelle du plan » ouverte. */
    var scaleDialog by mutableStateOf(false)

    /** L'echelle lue sur le plan, proposee en premier. */
    var foundRatio by mutableStateOf<Int?>(null)
        private set

    val ratio: Long? get() = metersPerPoint?.let(Measures::ratioOf)

    fun start(found: Int?) {
        active = true
        foundRatio = found
        if (metersPerPoint == null) {
            if (found != null) chooseRatio(found) else scaleDialog = true
        }
    }

    fun stop() {
        active = false
        current = null
        calibrating = false
        calibrationLine = null
        scaleDialog = false
        items.clear()
    }

    /** Nouveau document : l'echelle ne vaut plus. */
    fun reset() {
        stop()
        metersPerPoint = null
        calibrated = false
        foundRatio = null
    }

    fun pick(kind: MeasureKind) {
        finish()
        this.kind = kind
    }

    fun chooseRatio(ratio: Int) {
        metersPerPoint = Measures.metersPerPoint(ratio.toDouble())
        calibrated = false
        calibrating = false
        calibrationLine = null
        scaleDialog = false
    }

    fun startCalibration() {
        finish()
        scaleDialog = false
        calibrating = true
        calibrationLine = null
    }

    fun cancelCalibration() {
        calibrating = false
        calibrationLine = null
    }

    /** La ligne d'etalonnage mesure [realMeters] en vrai. */
    fun calibrate(realMeters: Double, size: PageSize) {
        val line = calibrationLine ?: return
        val points = Measures.lengthPoints(line.points, size.width, size.height)
        if (points > 0.0 && realMeters > 0.0) {
            metersPerPoint = realMeters / points
            calibrated = true
        }
        calibrating = false
        calibrationLine = null
    }

    /** Un toucher sur la page [page] en ([p]). */
    fun tap(page: Int, p: NPoint, size: PageSize) {
        if (calibrating) {
            val line = calibrationLine
            calibrationLine = if (line == null || line.page != page || line.points.size >= 2) {
                Measurement(page, MeasureKind.Length, listOf(p))
            } else {
                line.copy(points = line.points + p)
            }
            return
        }
        if (metersPerPoint == null) {
            scaleDialog = true
            return
        }
        val c = current
        if (c != null && c.page != page) finish()
        val now = current
        if (now == null) {
            current = Measurement(page, kind, listOf(p))
            return
        }
        // Surface : toucher pres du premier point ferme la forme.
        if (now.kind == MeasureKind.Area && now.points.size >= 3 && near(now.points.first(), p, size)) {
            finish()
            return
        }
        current = now.copy(points = now.points + p)
    }

    private fun near(a: NPoint, b: NPoint, size: PageSize): Boolean {
        val tolerance = 14f // points PDF
        return abs(a.x - b.x) * size.width < tolerance && abs(a.y - b.y) * size.height < tolerance
    }

    /** Termine la mesure en cours (gardee si elle a assez de points). */
    fun finish() {
        current?.takeIf { it.complete }?.let { items += it }
        current = null
    }

    /** Retire le dernier point, ou sinon la derniere mesure. */
    fun undo() {
        val c = current
        when {
            calibrating -> calibrationLine = null
            c != null -> current = if (c.points.size > 1) c.copy(points = c.points.dropLast(1)) else null
            items.isNotEmpty() -> items.removeAt(items.lastIndex)
        }
    }

    val canUndo: Boolean get() = current != null || items.isNotEmpty() || calibrationLine != null

    /** Les mesures terminees et celle en cours (si elle donne deja un resultat). */
    val all: List<Measurement> get() = items + listOfNotNull(current?.takeIf { it.complete })
}
