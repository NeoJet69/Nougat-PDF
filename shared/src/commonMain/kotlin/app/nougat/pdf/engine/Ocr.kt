package app.nougat.pdf.engine

/*
 * Reconnaissance de texte (OCR) : rendre le texte d'un scan ou d'une photo cherchable, lisible
 * par Claude et modifiable. Le texte reconnu est pose sur l'image, mot par mot, en texte
 * invisible (comme Acrobat ou OCRmyPDF) : la page ne change pas a l'oeil.
 */

/** Ce qu'il faut reconnaitre et comment. */
data class OcrOptions(
    /** Langues Tesseract (« fra », « eng »...), la premiere est la principale. */
    val languages: List<String>,
    /** Pages a traiter (0 = premiere), ou null pour tout le document. */
    val pages: List<Int>? = null,
    /** Remettre droites les pages couchees ou a l'envers. */
    val autoRotate: Boolean = true,
    /** Refaire aussi les pages deja reconnues par Nougat (autre langue...). */
    val redo: Boolean = false,
    /** Faire relire les lignes douteuses par l'IA de l'appareil (Gemini Nano, Android). */
    val aiProofread: Boolean = false,
)

/** Le bilan d'une reconnaissance. */
data class OcrSummary(
    /** Pages dont le texte a ete reconnu. */
    val recognized: Int,
    /** Pages laissees telles quelles : elles contiennent deja du vrai texte. */
    val skipped: Int,
    /** Pages remises droites. */
    val rotated: Int,
    val words: Int,
    /** L'utilisateur a arrete en cours de route (les pages deja faites sont gardees). */
    val cancelled: Boolean = false,
    /** Lignes corrigees par l'IA de l'appareil. */
    val aiLines: Int = 0,
)

/** L'IA qui tourne sur l'appareil (Gemini Nano sur les telephones Android compatibles). */
enum class DeviceAiStatus {
    /** Pas sur cet appareil. */
    Unavailable,

    /** Possible, mais le modele doit d'abord etre telecharge par Android. */
    Downloadable,

    /** En cours de telechargement par Android. */
    Downloading,

    /** Prete. */
    Available,
}

/** Langues de reconnaissance proposees : code Tesseract et nom dans la langue. */
val OCR_LANGUAGES: List<Pair<String, String>> = listOf(
    "fra" to "Français", "eng" to "English", "deu" to "Deutsch", "ita" to "Italiano",
    "spa" to "Español", "por" to "Português", "nld" to "Nederlands", "pol" to "Polski",
    "tur" to "Türkçe", "rus" to "Русский", "ukr" to "Українська", "ara" to "العربية",
    "chi_sim" to "中文", "swe" to "Svenska", "ell" to "Ελληνικά", "ron" to "Română",
)

/** Les langues de reconnaissance par defaut pour une langue d'interface (code de [app.nougat.pdf.i18n.Lang]). */
fun defaultOcrLanguages(appLanguage: String): List<String> {
    val main = when (appLanguage) {
        "fr", "fr-CA", "oc", "br", "miaou" -> "fra"
        "de", "gsw", "als" -> "deu"
        "it", "rm", "co" -> "ita"
        "es" -> "spa"
        "pt" -> "por"
        "nl" -> "nld"
        "pl" -> "pol"
        "tr" -> "tur"
        "ru" -> "rus"
        "uk" -> "ukr"
        "ar" -> "ara"
        "zh" -> "chi_sim"
        "sv" -> "swe"
        "el" -> "ell"
        "ro" -> "ron"
        else -> "eng"
    }
    // L'anglais en plus : les documents melangent souvent (noms de produits, adresses web...).
    return listOf(main, "eng").distinct()
}
