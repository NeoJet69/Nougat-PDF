package app.nougat.pdf.engine

import kotlin.math.abs

/*
 * Censurer un document : trouver ce qu'il faut cacher (des mots choisis, ou des informations
 * personnelles reconnues a leur forme et aux mots qui les annoncent), puis le recouvrir d'une
 * barre noire en retirant vraiment le texte (voir EditableDoc.eraseUnder). Tout se fait sur
 * l'appareil ; une IA (Claude, Gemini Nano) peut en plus reperer les noms.
 */

/** Les sortes d'informations que la censure intelligente sait reperer. */
enum class SensitiveKind {
    Person, Company, IdNumber, BirthDate, Address, Phone, Email, Account, Insurance, Medical, Custom;

    companion object {
        /** Celles que la censure intelligente propose (les mots choisis sont a part). */
        val smart: List<SensitiveKind> = entries.filter { it != Custom }
    }
}

/** Une information trouvee dans le texte d'une page : caracteres [start, end[ et sa sorte. */
class SensitiveFinding(val start: Int, val end: Int, val kind: SensitiveKind)

object SensitiveData {

    /**
     * Le texte de la page avec un retour a la ligne entre deux lignes (meme longueur que
     * [PageText.text], pour retrouver la place de chaque caractere).
     */
    fun linedText(page: PageText): String {
        val chars = page.text.toCharArray()
        var prev: NormRect? = null
        for (i in chars.indices) {
            val box = page.boxes[i]
            if (box != null) {
                prev = box
                continue
            }
            if (chars[i] != ' ') continue
            val next = (i + 1 until chars.size).firstNotNullOfOrNull { page.boxes[it] } ?: continue
            val p = prev ?: continue
            val h = maxOf(p.bottom - p.top, next.bottom - next.top)
            if (abs(next.top - p.top) > h * 0.5f) chars[i] = '\n'
        }
        return chars.concatToString()
    }

    /** Ce qu'il faut cacher dans [text] : les sortes [kinds] et les mots [words]. */
    fun detect(text: String, kinds: Set<SensitiveKind>, words: List<String> = emptyList()): List<SensitiveFinding> {
        val found = ArrayList<SensitiveFinding>()
        fun add(start: Int, end: Int, kind: SensitiveKind) {
            var s = start
            var e = end
            while (s < e && (text[s].isWhitespace() || text[s] in ",;:.")) s++
            while (e > s && (text[e - 1].isWhitespace() || text[e - 1] in ",;:")) e--
            if (e - s >= 2) found.add(SensitiveFinding(s, e, kind))
        }
        fun regex(r: Regex, kind: SensitiveKind, group: Int = 0, accept: (String) -> Boolean = { true }) {
            for (m in r.findAll(text)) {
                val g = m.groups[group] ?: continue
                if (accept(g.value)) add(g.range.first, g.range.last + 1, kind)
            }
        }

        if (SensitiveKind.Custom in kinds || words.isNotEmpty()) {
            val haystack = TextSearch.normalize(text)
            for (word in words.map { it.trim() }.filter { it.length >= 2 }.distinct()) {
                val needle = TextSearch.normalize(word.replace(Regex("\\s+"), " "))
                var from = 0
                while (true) {
                    val at = haystack.indexOf(needle, from)
                    if (at < 0) break
                    add(at, at + needle.length, SensitiveKind.Custom)
                    from = at + needle.length
                }
            }
        }
        if (SensitiveKind.Account in kinds) {
            regex(IBAN, SensitiveKind.Account) { ibanValid(it) }
            regex(CARD, SensitiveKind.Account) { luhnValid(it) }
            regex(ACCOUNT, SensitiveKind.Account, group = 1) { it.count(Char::isDigit) >= 3 }
        }
        if (SensitiveKind.IdNumber in kinds) {
            regex(AVS, SensitiveKind.IdNumber)
            regex(NIR, SensitiveKind.IdNumber)
            regex(ID_DOCUMENT, SensitiveKind.IdNumber, group = 1) { it.any(Char::isDigit) }
        }
        if (SensitiveKind.Insurance in kinds) regex(INSURANCE, SensitiveKind.Insurance, group = 1) { it.count(Char::isDigit) >= 3 }
        if (SensitiveKind.Medical in kinds) regex(MEDICAL, SensitiveKind.Medical, group = 1) { it.count(Char::isDigit) >= 3 }
        if (SensitiveKind.Email in kinds) regex(EMAIL, SensitiveKind.Email)
        if (SensitiveKind.Phone in kinds) {
            regex(PHONE, SensitiveKind.Phone) { it.count(Char::isDigit) in 9..15 }
        }
        if (SensitiveKind.BirthDate in kinds) regex(BIRTH_DATE, SensitiveKind.BirthDate, group = 1)
        if (SensitiveKind.Address in kinds) {
            regex(STREET_NUMBER_FIRST, SensitiveKind.Address)
            regex(STREET_NUMBER_LAST, SensitiveKind.Address)
            regex(STREET_GERMAN, SensitiveKind.Address)
            regex(POSTCODE_CITY, SensitiveKind.Address)
        }
        if (SensitiveKind.Company in kinds) regex(COMPANY, SensitiveKind.Company)
        if (SensitiveKind.Person in kinds) {
            regex(PERSON_TITLE, SensitiveKind.Person, group = 1) { name -> name.split(' ', '\t').none { it in NOT_NAMES } }
            regex(PERSON_LABEL, SensitiveKind.Person, group = 1) { name -> name.split(' ', '\t').none { it in NOT_NAMES } }
        }

        // Deux trouvailles qui se chevauchent : la plus sure gagne (un IBAN plutot qu'un telephone).
        val kept = ArrayList<SensitiveFinding>()
        for (f in found.sortedWith(compareBy<SensitiveFinding>({ PRIORITY.indexOf(it.kind) }, { -(it.end - it.start) }))) {
            if (kept.none { it.start < f.end && f.start < it.end }) kept.add(f)
        }
        return kept.sortedBy { it.start }
    }

    /** Les rectangles (un par ligne) des caracteres [start, end[ de la page. */
    fun rects(page: PageText, start: Int, end: Int): List<NormRect> =
        TextSearch.mergeByLine(page.boxes.subList(start.coerceIn(0, page.boxes.size), end.coerceIn(0, page.boxes.size)))

    fun ibanValid(value: String): Boolean {
        val iban = value.filter { !it.isWhitespace() }.uppercase()
        if (iban.length !in 15..34) return false
        val moved = iban.drop(4) + iban.take(4)
        var rest = 0
        for (c in moved) {
            val digits = if (c.isDigit()) c.toString() else if (c in 'A'..'Z') (c - 'A' + 10).toString() else return false
            for (d in digits) rest = (rest * 10 + (d - '0')) % 97
        }
        return rest == 1
    }

    fun luhnValid(value: String): Boolean {
        val digits = value.filter(Char::isDigit)
        if (digits.length !in 13..19) return false
        var sum = 0
        for ((i, c) in digits.reversed().withIndex()) {
            var d = c - '0'
            if (i % 2 == 1) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
        }
        return sum % 10 == 0
    }

    private val PRIORITY = listOf(
        SensitiveKind.Custom, SensitiveKind.Account, SensitiveKind.IdNumber, SensitiveKind.Insurance, SensitiveKind.Medical,
        SensitiveKind.Email, SensitiveKind.BirthDate, SensitiveKind.Phone, SensitiveKind.Address, SensitiveKind.Company, SensitiveKind.Person,
    )

    /** Un numero annonce par un mot (« Police n° », « Compte : ») : 4 a 25 caracteres, chiffres compris. */
    private const val TOKEN = "([A-Z0-9][A-Z0-9 ./-]{2,24}[A-Z0-9])"
    private const val LEAD = "[ \\t]*(?:n[°o]\\.?|no\\.?|nr\\.?|num(?:éro|ero|ber)?\\.?|#)?[ \\t]*[:.]?[ \\t]*"

    private val EMAIL = Regex("[\\p{L}0-9._%+-]+@[\\p{L}0-9-]+(?:\\.[\\p{L}0-9-]+)*\\.\\p{L}{2,}")
    private val PHONE = Regex(
        "(?<![\\p{L}\\d])(?:(?:\\+|00)\\d{1,3}[ .\\-/]?(?:\\(0\\)[ .\\-]?)?\\d{1,4}|0\\d{1,3})(?:[ .\\-/]?\\d{2,4}){2,4}(?![\\d])",
    )
    private val IBAN = Regex("(?<![A-Z0-9])[A-Z]{2}\\d{2}(?:[ ]?[A-Z0-9]{4}){2,7}(?:[ ]?[A-Z0-9]{1,3})?(?![A-Za-z0-9])")
    private val CARD = Regex("(?<!\\d)(?:\\d[ -]?){12,18}\\d(?!\\d)")
    private val AVS = Regex("(?<!\\d)756[. ]?\\d{4}[. ]?\\d{4}[. ]?\\d{2}(?!\\d)")
    private val NIR = Regex("(?<!\\d)[12] ?\\d{2} ?(?:0[1-9]|1[0-2]) ?(?:\\d{2}|2A|2B) ?\\d{3} ?\\d{3}(?: ?\\d{2})?(?!\\d)")
    private val ID_DOCUMENT = Regex(
        "(?i:passeport|passport|reisepass|passaporto|pasaporte|carte d['’]identit[ée]|pi[èe]ce d['’]identit[ée]|identity card|id card|" +
            "personalausweis|identit[äa]tskarte|ausweis|carta d['’]identit[àa]|documento|permis de conduire|driving licen[cs]e|f[üu]hrerschein)" +
            LEAD + TOKEN,
    )
    private val ACCOUNT = Regex(
        "(?i:num[ée]ro de compte|n° de compte|compte(?: bancaire| postal| courant)?|account(?: number)?|konto(?:nummer)?|conto|cuenta|" +
            "num[ée]ro client|n° client|client n°|kundennummer|customer (?:number|id))" + LEAD + TOKEN,
    )
    private val INSURANCE = Regex(
        "(?i:num[ée]ro de police|n° de police|police(?: d['’]assurance)?|policy(?: number)?|versicherungs(?:nummer|police)|polizza|p[óo]liza|" +
            "num[ée]ro d['’]assur[ée]e?|n° d['’]assur[ée]e?|assur[ée]e? n°|versichertennummer|insured (?:number|id)|n° de contrat|contrat|contract(?: number)?)" +
            LEAD + TOKEN,
    )
    private val MEDICAL = Regex(
        "(?i:dossier m[ée]dical|n° de dossier|num[ée]ro de dossier|dossier|n° patient|num[ée]ro (?:de )?patient|patient (?:number|id)|patiente?|" +
            "medical record(?: number)?|mrn|patientennummer|fallnummer|cartella clinica|historia cl[íi]nica|ipp)" + LEAD + TOKEN,
    )

    private const val MONTHS = "janv|févr|fév|mars|avr|mai|juin|juil|août|sept|oct|nov|déc|jan|feb|mar|apr|may|jun|jul|aug|sep|okt|dez|" +
        "gen|mag|giu|lug|ago|set|ott|dic|ene|abr|janvier|février|avril|juillet|septembre|octobre|novembre|décembre|january|february|march|" +
        "april|june|july|august|september|october|november|december|januar|februar|märz|juni|juli|oktober|dezember|gennaio|febbraio|marzo|" +
        "aprile|maggio|giugno|luglio|agosto|settembre|ottobre|dicembre|enero|febrero|abril|mayo|junio|julio|septiembre|octubre|noviembre|diciembre"
    private const val DATE = "(\\d{1,2}[./-]\\d{1,2}[./-]\\d{2,4}|\\d{1,2}\\.?[ \\t]+(?i:$MONTHS)\\.?[ \\t]+\\d{4}|(?i:$MONTHS)[ \\t]+\\d{1,2},?[ \\t]+\\d{4})"
    private val BIRTH_DATE = Regex(
        "(?i:n[ée]e?(?:\\(e\\))? le|n[ée]e?(?:\\(e\\))?|date de naissance|naissance|date of birth|born(?: on)?|dob|d\\.o\\.b\\.|birth|" +
            "geboren(?: am)?|geb\\.|geburtsdatum|nato il|nata il|nato|nata|data di nascita|nacido el|nacida el|fecha de nacimiento)" +
            "[ \\t]*:?[ \\t]*$DATE",
    )

    private const val STREET_WORDS = "rue|avenue|av\\.|chemin|ch\\.|route|rte|boulevard|bd|place|pl\\.|impasse|all[ée]e|quai|sentier|ruelle|" +
        "cours|via|viale|corso|piazza|vicolo|street|st\\.|road|rd\\.|lane|drive|calle|carrer"
    private val STREET_NUMBER_FIRST = Regex("(?<![\\p{L}\\d])\\d{1,4}[ \\t]?[a-zA-Z]?,?[ \\t]+(?i:$STREET_WORDS)[ \\t]+[^\\n,;]{2,40}")
    private val STREET_NUMBER_LAST = Regex("(?<![\\p{L}])(?i:$STREET_WORDS)[ \\t]+[^\\n,;0-9]{2,40}?[ \\t]\\d{1,4}[ \\t]?[a-zA-Z]?(?![\\d\\p{L}])")
    private val STREET_GERMAN = Regex(
        "(?<![\\p{L}])\\p{Lu}[\\p{L}-]*(?i:strasse|straße|str\\.|weg|gasse|platz|allee|ring|rain|halde|matte|acker)[ \\t]?\\d{1,4}[ \\t]?[a-zA-Z]?(?![\\d\\p{L}])",
    )
    private val POSTCODE_CITY = Regex("(?m)^(?:CH-|F-|D-|A-|FL-)?\\d{4,5}[ \\t]+\\p{Lu}[\\p{L}'’-]+(?:[ \\t-]\\p{Lu}[\\p{L}'’-]+){0,2}(?=[ \\t]*(?:\\n|$|,))")

    private val COMPANY = Regex(
        "(?<![\\p{L}])(?:\\p{Lu}[\\p{L}&'’.-]*[ \\t]+){1,4}(?:SA|S\\.A\\.|Sàrl|SARL|S\\.à r\\.l\\.|AG|GmbH|Ltd\\.?|LLC|Inc\\.?|SAS|SASU|EURL|SNC|KG|Sagl|SpA|" +
            "S\\.p\\.A\\.|Srl|S\\.r\\.l\\.|BV|NV|plc|& Cie|& Co\\.?)(?![\\p{L}])",
    )
    private const val NAME = "((?:\\p{Lu}[\\p{Ll}'’-]+|\\p{Lu}{2,})(?:[ \\t]+(?:\\p{Lu}[\\p{Ll}'’-]+|\\p{Lu}{2,}|de|du|von|van|da|di|del|le|la)){0,3})"
    private val PERSON_TITLE = Regex(
        "(?<![\\p{L}])(?:Madame|Monsieur|Mme|Mlle|M\\.|Mr\\.?|Mrs\\.?|Ms\\.?|Miss|Dr\\.?|Docteur|Maître|Me|Herr|Frau|Hr\\.|Fr\\.|" +
            "Sig\\.(?:ra)?|Signor(?:a)?|Señor(?:a)?|Sr\\.|Sra\\.)[ \\t]+$NAME",
    )
    private val PERSON_LABEL = Regex(
        "(?<![\\p{L}])(?i:nom|pr[ée]nom|name|vorname|nachname|nome|cognome|nombre|apellidos?|client|cliente|kunde|kundin|patient|patiente|" +
            "destinataire|exp[ée]diteur|titulaire|assur[ée]e?|versicherte[r]?|employ[ée]e?|mitarbeiter(?:in)?|signataire|contact|attn|z\\.[ ]?hd\\.?)" +
            "[ \\t]*:[ \\t]*$NAME",
    )

    /** Des mots en majuscule qui suivent souvent « Madame » ou « Client : » mais ne sont pas des noms. */
    private val NOT_NAMES = setOf(
        "Adresse", "Address", "Rue", "Date", "Datum", "Tel", "Tél", "Téléphone", "Telefon", "Email", "E-mail", "Facture", "Invoice",
        "Rechnung", "Total", "Montant", "Objet", "Concerne", "Betreff", "Madame", "Monsieur", "Mesdames", "Messieurs", "Le", "La", "Les",
    )
}
