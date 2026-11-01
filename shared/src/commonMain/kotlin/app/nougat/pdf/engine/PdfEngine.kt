package app.nougat.pdf.engine

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Le moteur PDF d'une plateforme : PDFBox sur ordinateur, PdfRenderer + PdfBox-Android sur Android.
 * Le reste de l'app ne connait que ces interfaces.
 */
interface PdfEngine {
    /**
     * Ouvre le fichier [path].
     * @throws PasswordRequiredException si le PDF est protege et que [password] est absent ou faux.
     */
    suspend fun open(path: String, password: String? = null): PdfDocument
}

interface PdfDocument : AutoCloseable {
    val pageCount: Int

    /** Taille de chaque page en points PDF (1/72 de pouce), rotation deja appliquee. */
    val pageSizes: List<PageSize>

    /** Dessine la page [index] (0 = premiere) sur une image de [widthPx] pixels de large. */
    suspend fun renderPage(index: Int, widthPx: Int): ImageBitmap

    /** Le texte de la page [index], lettre par lettre avec sa position. */
    suspend fun pageText(index: Int): PageText
}

data class PageSize(val width: Float, val height: Float) {
    val aspectRatio: Float get() = if (height > 0f) width / height else 1f
}

class PasswordRequiredException(val wrongPassword: Boolean) : Exception(
    if (wrongPassword) "Mot de passe incorrect" else "Ce PDF est protege par un mot de passe",
)

/** Rectangle en fractions de la page : 0..1 en largeur et en hauteur, origine en haut a gauche. */
data class NormRect(val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * Le texte d'une page. [text] et [boxes] ont la meme longueur : boxes[i] est la position
 * du caractere text[i], ou null pour un espace ajoute entre deux mots ou deux lignes.
 */
class PageText(val text: String, val boxes: List<NormRect?>) {
    init {
        require(text.length == boxes.size) { "text et boxes doivent avoir la meme longueur" }
    }

    companion object {
        val Empty = PageText("", emptyList())
    }
}
