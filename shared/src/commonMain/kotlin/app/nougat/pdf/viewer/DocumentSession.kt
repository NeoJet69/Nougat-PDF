package app.nougat.pdf.viewer

import app.nougat.pdf.engine.NormRect
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import app.nougat.pdf.engine.FormField
import app.nougat.pdf.engine.Mark
import app.nougat.pdf.engine.PageText
import app.nougat.pdf.engine.TextBlock
import app.nougat.pdf.engine.PdfDocument
import app.nougat.pdf.engine.SearchHit
import app.nougat.pdf.engine.TextSearch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Un document ouvert : pages, zoom, recherche, annotations en attente.
 * [path] est le fichier lu (souvent une copie de travail) ; [sourceRef] l'endroit ou enregistrer.
 */
class DocumentSession(
    name: String,
    val path: String,
    sourceRef: String,
    /** Mot de passe du fichier [path], s'il est protege. */
    val password: String?,
    val document: PdfDocument,
    parentScope: CoroutineScope,
    /** Position de depart (pour retrouver la meme vue apres une modification). */
    initialItem: Int = 0,
    initialOffset: Int = 0,
) {
    var name by mutableStateOf(name)
        private set
    var sourceRef by mutableStateOf(sourceRef)
        private set

    fun rename(newName: String, newSourceRef: String) {
        name = newName
        sourceRef = newSourceRef
    }

    /** Annotations ajoutees mais pas encore gravees dans le PDF. */
    val marks = mutableStateListOf<Mark>()

    /** Ligne de texte en cours de modification (outil « Éditer »), et le texte tape. */
    var editingBlock: TextBlock? by mutableStateOf(null)
    var editingText by mutableStateOf("")

    fun startEditing(block: TextBlock) {
        editingBlock = block
        editingText = block.text
    }

    // ---- Plusieurs lignes choisies (Ctrl + clic, Maj + clic, appui long), pour les effacer ----------

    val selectedBlocks = mutableStateListOf<TextBlock>()
    private var selectionAnchor: TextBlock? = null

    /** Deplacement en attente des lignes choisies (fractions de la page) et taille (1 = pareil). */
    var moveDx by mutableStateOf(0f)
    var moveDy by mutableStateOf(0f)
    var moveScale by mutableStateOf(1f)

    val hasPendingMove: Boolean get() = moveDx != 0f || moveDy != 0f || moveScale != 1f

    fun resetMove() {
        moveDx = 0f
        moveDy = 0f
        moveScale = 1f
    }

    fun nudge(dx: Float, dy: Float) {
        moveDx += dx
        moveDy += dy
    }

    fun resize(factor: Float) {
        moveScale = (moveScale * factor).coerceIn(0.25f, 6f)
    }

    /** Ou la ligne choisie [b] ira (apercu), autour du coin haut gauche des lignes choisies de sa page. */
    fun movedRect(b: TextBlock): NormRect {
        if (!hasPendingMove) return b.rect
        val onPage = selectedBlocks.filter { it.page == b.page }
        val ox = onPage.minOfOrNull { it.rect.left } ?: b.rect.left
        val oy = onPage.minOfOrNull { it.rect.top } ?: b.rect.top
        val r = b.rect
        return NormRect(
            ox + (r.left - ox) * moveScale + moveDx, oy + (r.top - oy) * moveScale + moveDy,
            ox + (r.right - ox) * moveScale + moveDx, oy + (r.bottom - oy) * moveScale + moveDy,
        )
    }

    /** Ajoute la ligne a la selection, ou l'en retire. */
    fun toggleSelection(block: TextBlock) {
        resetMove()
        if (!selectedBlocks.remove(block)) {
            selectedBlocks.add(block)
            selectionAnchor = block
        }
    }

    /** Maj + clic : toutes les lignes de la zone entre la derniere choisie et [block] (une colonne de prix). */
    fun selectArea(pageBlocks: List<TextBlock>, block: TextBlock) {
        resetMove()
        val anchor = selectionAnchor?.takeIf { it.page == block.page && it in selectedBlocks }
        if (anchor == null) {
            toggleSelection(block)
            return
        }
        val left = minOf(anchor.rect.left, block.rect.left)
        val top = minOf(anchor.rect.top, block.rect.top)
        val right = maxOf(anchor.rect.right, block.rect.right)
        val bottom = maxOf(anchor.rect.bottom, block.rect.bottom)
        for (b in pageBlocks) {
            val r = b.rect
            if (r.left < right && r.right > left && r.top < bottom && r.bottom > top && b !in selectedBlocks) selectedBlocks.add(b)
        }
    }

    fun clearSelection() {
        resetMove()
        selectedBlocks.clear()
        selectionAnchor = null
    }

    private val blocks = HashMap<Int, List<TextBlock>>()
    private val blocksMutex = Mutex()

    /** Les lignes de texte modifiables de la page (calculees une fois). */
    suspend fun textBlocks(page: Int): List<TextBlock> = blocksMutex.withLock {
        blocks.getOrPut(page) {
            try {
                document.textBlocks(page)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }

    private val fields = HashMap<Int, List<FormField>>()
    private val fieldsMutex = Mutex()

    /** Les champs de formulaire de la page (calcules une fois). */
    suspend fun formFields(page: Int): List<FormField> = fieldsMutex.withLock {
        fields.getOrPut(page) {
            try {
                document.formFields(page)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {
                emptyList()
            }
        }
    }

    fun cachedFormFields(page: Int): List<FormField>? = fields[page]

    /** Tous les champs du document, page par page (pour l'assistant et la touche Tab). */
    suspend fun allFormFields(): List<FormField> = (0 until pageCount).flatMap { formFields(it) }

    /** Le PDF est un formulaire : les champs sont mis en evidence en mode Lire. */
    var hasForm by mutableStateOf(false)

    /** Pages scannees dont le texte n'est pas encore reconnu (bandeau « Reconnaitre le texte »). */
    var ocrPages by mutableStateOf(0)

    /** Le bandeau de l'OCR a ete ferme pour ce document. */
    var ocrBannerClosed by mutableStateOf(false)

    /** Champ en cours de saisie (formulaire). */
    var editingField: FormField? by mutableStateOf(null)
    var editingFieldText by mutableStateOf("")

    fun startFieldEditing(field: FormField) {
        editingField = field
        editingFieldText = field.value
    }

    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))

    val pageCount: Int get() = document.pageCount
    val pageSizes get() = document.pageSizes

    val listState = LazyListState(initialItem.coerceIn(0, (document.pageCount - 1).coerceAtLeast(0)), initialOffset)

    // ---- Zoom ----------------------------------------------------------------------------

    /** 1 = la page prend la largeur de l'ecran (jusqu'a une largeur maximale confortable). */
    var zoom by mutableFloatStateOf(1f)
        private set

    fun updateZoom(value: Float) {
        zoom = value.coerceIn(MIN_ZOOM, MAX_ZOOM)
    }

    fun zoomIn() = updateZoom(nextStep(up = true))
    fun zoomOut() = updateZoom(nextStep(up = false))
    fun resetZoom() = updateZoom(1f)

    private fun nextStep(up: Boolean): Float {
        val steps = ZOOM_STEPS
        return if (up) steps.firstOrNull { it > zoom + 0.01f } ?: MAX_ZOOM
        else steps.lastOrNull { it < zoom - 0.01f } ?: MIN_ZOOM
    }

    // ---- Navigation ------------------------------------------------------------------------

    /** La page au milieu de l'ecran (0 = premiere), tenue a jour par l'ecran. */
    var visiblePage by mutableIntStateOf(0)

    fun nextPage() = goToPage(visiblePage + 1)
    fun previousPage() = goToPage(visiblePage - 1)

    /** Demande de defilement, executee par l'ecran (qui connait la taille des pages). */
    var scrollRequest: ScrollRequest? by mutableStateOf(null)
        private set
    private var scrollCounter = 0L

    fun goToPage(page: Int, fractionInPage: Float = 0f) {
        if (pageCount == 0) return
        scrollRequest = ScrollRequest(page.coerceIn(0, pageCount - 1), fractionInPage, ++scrollCounter)
    }

    fun scrollHandled(request: ScrollRequest) {
        if (scrollRequest == request) scrollRequest = null
    }

    // ---- Images des pages ----------------------------------------------------------------

    private val bitmaps = BitmapCache(maxEntries = 8)

    /** Une image deja prete pour cette page, quelle que soit sa taille (en attendant la bonne). */
    fun cachedPage(page: Int): ImageBitmap? = bitmaps.anyFor(page)

    suspend fun renderPage(page: Int, widthPx: Int): ImageBitmap {
        bitmaps.get(page, widthPx)?.let { return it }
        val image = document.renderPage(page, widthPx)
        bitmaps.put(page, widthPx, image)
        return image
    }

    private val thumbnails = BitmapCache(maxEntries = 120)

    /** Vignette (pour l'ecran des pages). */
    suspend fun thumbnail(page: Int, widthPx: Int): ImageBitmap {
        thumbnails.get(page, widthPx)?.let { return it }
        val image = document.renderPage(page, widthPx)
        thumbnails.put(page, widthPx, image)
        return image
    }

    fun cachedThumbnail(page: Int): ImageBitmap? = thumbnails.anyFor(page)

    // ---- Recherche ---------------------------------------------------------------------------

    var searchOpen by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
        private set
    var hits: List<SearchHit> by mutableStateOf(emptyList())
        private set
    /** Index dans [hits] de l'occurrence selectionnee, -1 si aucune. */
    var currentHit by mutableIntStateOf(-1)
        private set
    var searching by mutableStateOf(false)
        private set
    /** Vrai quand une recherche s'est terminee (pour afficher « aucun resultat »). */
    var searchDone by mutableStateOf(false)
        private set

    private var searchJob: Job? = null
    private val texts = HashMap<Int, PageText>()
    private val textMutex = Mutex()

    fun openSearch() {
        searchOpen = true
    }

    fun closeSearch() {
        searchOpen = false
        searchJob?.cancel()
        query = ""
        hits = emptyList()
        currentHit = -1
        searching = false
        searchDone = false
    }

    /** Nouvelle saisie : la recherche part apres une courte pause. */
    fun onQueryChange(value: String) {
        query = value
        launchSearch(debounceMs = 300)
    }

    /** Touche Entree : occurrence suivante, ou lance la recherche tout de suite. */
    fun onSearchSubmit() {
        if (searchDone && hits.isNotEmpty()) nextHit() else launchSearch(debounceMs = 0)
    }

    fun nextHit() = selectHit(if (hits.isEmpty()) -1 else (currentHit + 1).mod(hits.size))
    fun previousHit() = selectHit(if (hits.isEmpty()) -1 else (currentHit - 1).mod(hits.size))

    private fun selectHit(index: Int) {
        currentHit = index
        val hit = hits.getOrNull(index) ?: return
        val top = hit.rects.minOfOrNull { it.top } ?: 0f
        goToPage(hit.page, top)
    }

    private fun launchSearch(debounceMs: Long) {
        searchJob?.cancel()
        hits = emptyList()
        currentHit = -1
        searchDone = false
        val q = query
        if (q.isBlank()) {
            searching = false
            return
        }
        searching = true
        searchJob = scope.launch {
            if (debounceMs > 0) delay(debounceMs)
            val found = ArrayList<SearchHit>()
            // On commence a la page affichee, puis on fait le tour du document.
            val start = listState.firstVisibleItemIndex.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
            val order = (start until pageCount) + (0 until start)
            for (page in order) {
                val pageHits = TextSearch.find(page, textOf(page), q)
                if (pageHits.isNotEmpty()) {
                    found.addAll(pageHits)
                    hits = found.sortedWith(compareBy({ (it.page - start).mod(pageCount) }))
                    if (currentHit < 0) selectHit(0)
                }
            }
            // Ordre final : celui du document.
            val ordered = found.sortedBy { it.page }
            val selected = hits.getOrNull(currentHit)
            hits = ordered
            currentHit = if (selected != null) ordered.indexOf(selected) else -1
            searching = false
            searchDone = true
        }
    }

    /** Le texte de la page, lettre par lettre avec sa position (calcule une fois). */
    suspend fun pageTextOf(page: Int): PageText = textOf(page)

    private suspend fun textOf(page: Int): PageText = textMutex.withLock {
        texts.getOrPut(page) {
            try {
                document.pageText(page)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Throwable) {
                PageText.Empty
            }
        }
    }

    fun hitsOnPage(page: Int): List<IndexedValue<SearchHit>> =
        hits.withIndex().filter { it.value.page == page }

    // ---- Fin -----------------------------------------------------------------------------------

    fun close() {
        scope.cancel()
        bitmaps.clear()
        thumbnails.clear()
        runCatching { document.close() }
    }

    companion object {
        const val MIN_ZOOM = 0.25f
        const val MAX_ZOOM = 5f
        val ZOOM_STEPS = listOf(0.25f, 0.33f, 0.5f, 0.67f, 0.75f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f, 5f)
    }
}

data class ScrollRequest(val page: Int, val fractionInPage: Float, val id: Long)

/** Les dernieres images rendues, les plus anciennes sont oubliees. */
private class BitmapCache(private val maxEntries: Int) {
    private val map = LinkedHashMap<Long, ImageBitmap>()

    private fun key(page: Int, width: Int) = (page.toLong() shl 32) or width.toLong()

    fun get(page: Int, width: Int): ImageBitmap? {
        val k = key(page, width)
        val v = map.remove(k) ?: return null
        map[k] = v
        return v
    }

    fun anyFor(page: Int): ImageBitmap? =
        map.entries.lastOrNull { (it.key shr 32).toInt() == page }?.value

    fun put(page: Int, width: Int, image: ImageBitmap) {
        val k = key(page, width)
        map.remove(k)
        map[k] = image
        while (map.size > maxEntries) map.remove(map.keys.first())
    }

    fun clear() = map.clear()
}
