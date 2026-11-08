package app.nougat.pdf.engine

/*
 * Lecture a voix haute : la voix du systeme (synthese vocale d'Android, voix de Windows), et une
 * petite devinette de la langue d'un texte quand l'appareil ne sait pas la reconnaitre.
 */

/** La synthese vocale de l'appareil. */
interface Speaker {
    /** Vitesse : 1 = normale. */
    var rate: Float

    /**
     * Lit [text] dans la langue [language] (fr, de, en...) et revient a la fin. Faux si aucune voix
     * de cette langue n'est installee. Annuler la coroutine arrete la voix.
     */
    suspend fun speak(text: String, language: String): Boolean

    fun stop()
}

object LanguageGuess {
    private val WORDS: Map<String, Set<String>> = mapOf(
        "fr" to setOf("le", "la", "les", "des", "et", "est", "une", "pour", "que", "qui", "dans", "du", "vous", "nous", "sur", "pas", "avec", "au", "aux", "votre", "nos", "ce", "cette", "sont", "être"),
        "de" to setOf("der", "die", "das", "und", "ist", "nicht", "mit", "den", "ein", "eine", "zu", "von", "für", "sie", "wir", "auf", "dem", "bitte", "ihr", "im", "ihre", "werden", "sich", "auch", "bei"),
        "it" to setOf("il", "di", "che", "è", "per", "non", "una", "sono", "del", "della", "con", "gli", "ai", "al", "si", "ci", "questo", "alla", "nel", "delle", "lei", "suo", "anche", "più", "essere"),
        "en" to setOf("the", "and", "is", "of", "to", "that", "for", "you", "with", "on", "are", "this", "be", "your", "we", "please", "have", "at", "it", "will", "our", "from", "by", "not", "can"),
        "es" to setOf("el", "que", "y", "los", "del", "las", "por", "para", "con", "una", "es", "su", "lo", "como", "más", "pero", "sus", "le", "ya", "este", "usted", "muy", "sin", "sobre", "también"),
        "pt" to setOf("o", "que", "e", "do", "da", "em", "um", "para", "com", "não", "uma", "os", "no", "se", "na", "por", "mais", "as", "dos", "como", "mas", "ao", "ele", "das", "você"),
        "nl" to setOf("de", "het", "een", "en", "van", "is", "dat", "op", "te", "voor", "met", "niet", "zijn", "u", "wij", "aan", "ook", "als", "bij", "uw", "worden", "naar", "deze", "dit", "zal"),
    )

    /** La langue la plus probable de [text] (fr, de, it, en, es, pt, nl), ou null si on ne sait pas. */
    fun guess(text: String): String? {
        val words = Regex("""\p{L}+""").findAll(text.lowercase()).map { it.value }.take(600).toList()
        if (words.size < 4) return null
        val scores = WORDS.mapValues { (_, set) -> words.count { it in set } }.entries.sortedByDescending { it.value }
        val best = scores[0]
        val second = scores.getOrNull(1)?.value ?: 0
        return if (best.value >= 3 && best.value >= second * 1.3) best.key else null
    }
}
