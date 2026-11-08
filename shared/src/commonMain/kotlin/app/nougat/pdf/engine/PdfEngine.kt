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

    /**
     * Ouvre [src] pour modification, applique [block] puis enregistre le resultat dans [dst].
     * Le fichier [src] n'est jamais modifie. Un PDF protege le reste (meme mot de passe),
     * sauf si [block] change le mot de passe.
     */
    suspend fun edit(src: String, password: String?, dst: String, block: (EditableDoc) -> Unit)

    /** Cree un nouveau PDF [dst] : document vide, rempli par [block] (pages blanches, images...). */
    suspend fun create(dst: String, block: (EditableDoc) -> Unit)

    /** Telephone ou tablette (et non ordinateur) : change quelques textes. */
    val isPhone: Boolean get() = false

    /** Langues de reconnaissance de texte (OCR) disponibles ; vide si la plateforme ne sait pas faire. */
    val ocrLanguages: List<String> get() = emptyList()

    /** Faux si la langue d'OCR [code] doit d'abord etre telechargee (une seule fois). */
    fun ocrLanguageReady(code: String): Boolean = true

    /**
     * Telecharge les langues d'OCR [codes] qui manquent ([onProgress] : langue, pourcentage).
     * Leve une exception si une langue ne peut pas etre obtenue.
     */
    suspend fun prepareOcrLanguages(codes: List<String>, onProgress: (String, Int) -> Unit) {}

    /** L'IA de l'appareil (Gemini Nano sur Android) pour relire l'OCR. */
    suspend fun deviceAiStatus(): DeviceAiStatus = DeviceAiStatus.Unavailable

    /** Fait telecharger l'IA de l'appareil par le systeme ([onBytes] : progression). Vrai si elle est prete. */
    suspend fun prepareDeviceAi(onBytes: (Long) -> Unit): Boolean = false

    /** La voix de l'appareil pour lire a voix haute, ou null si elle n'existe pas. */
    val speaker: Speaker? get() = null

    /** Le traducteur de l'appareil (ML Kit sur Android), ou null : la traduction passe alors par Claude. */
    val deviceTranslator: DeviceTranslator? get() = null

    /**
     * Censure : les noms de personnes et d'entreprises de [text] trouves par l'IA de l'appareil
     * (Gemini Nano), ou null si elle n'est pas disponible.
     */
    suspend fun deviceFindNames(text: String): List<Pair<SensitiveKind, String>>? = null

    /**
     * « Ce que je dois faire » : la reponse de l'IA de l'appareil (Gemini Nano) a [instructions]
     * pour [text], ou null si elle n'est pas disponible.
     */
    suspend fun deviceDigest(text: String, instructions: String): String? = null
}

interface PdfDocument : AutoCloseable {
    val pageCount: Int

    /** Taille de chaque page en points PDF (1/72 de pouce), rotation deja appliquee. */
    val pageSizes: List<PageSize>

    /** Dessine la page [index] (0 = premiere) sur une image de [widthPx] pixels de large. */
    suspend fun renderPage(index: Int, widthPx: Int): ImageBitmap

    /** Le texte de la page [index], lettre par lettre avec sa position. */
    suspend fun pageText(index: Int): PageText

    /** Les lignes de texte de la page [index], pour les modifier. */
    suspend fun textBlocks(index: Int): List<TextBlock>

    /** La page [index] en image JPEG (pour l'assistant). */
    suspend fun renderPageJpeg(index: Int, widthPx: Int): ByteArray

    /** Les champs de formulaire de la page [index] (vide si le PDF n'est pas un formulaire). */
    suspend fun formFields(index: Int): List<FormField> = emptyList()

    /** Vrai si le PDF contient des champs a remplir. */
    suspend fun hasForm(): Boolean = false

    /** Nombre de pages scannees dont le texte n'est pas encore reconnu. */
    suspend fun pagesNeedingOcr(): Int = 0

    /** Les textes des codes QR dessines sur la page [index] (QR-facture...). */
    suspend fun qrCodes(index: Int): List<String> = emptyList()
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
