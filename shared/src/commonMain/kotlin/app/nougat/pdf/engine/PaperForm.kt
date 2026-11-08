package app.nougat.pdf.engine

import kotlin.math.max
import kotlin.math.min

/*
 * Remplir un formulaire papier scanne : les champs sont reperes dans le texte de la page (reconnu
 * par l'OCR pour un scan) : un libelle suivi de « ______ », de « ...... » ou de deux-points sans
 * rien apres. Les informations de l'utilisateur (profil garde sur l'appareil) remplissent ceux
 * qu'on reconnait : nom, prenom, adresse, date de naissance, telephone...
 */

/** Ce qu'un champ demande, pour le remplir avec le profil. */
enum class ProfileKey {
    FullName, LastName, FirstName, Company, Street, PostalTown, PostalCode, Town, BirthDate,
    Nationality, Phone, Email, AvsNumber, Iban, PlaceDate, Date,
}

/** Un champ a remplir : son libelle et la place ou ecrire (fractions de la page). */
data class PaperField(val page: Int, val label: String, val rect: NormRect, val fontSize: Float, val key: ProfileKey?)

object PaperForms {
    private val FILLER = Regex("""[_.…·\-]{4,}""")
    private val LABEL_FILLER = Regex("""^(.{2,48}?)\s*[:：]?\s*[_.…·]{4,}[_.…·\s]*$""")
    private val LABEL_COLON = Regex("""^(.{2,48}?)\s*[:：]\s*$""")

    /** Les champs de la page [page] trouves dans ses lignes de texte [blocks]. */
    fun detect(page: Int, blocks: List<TextBlock>, pageWidth: Float, pageHeight: Float): List<PaperField> {
        val out = ArrayList<PaperField>()
        val sorted = blocks.filter { it.text.isNotBlank() }.sortedWith(compareBy({ it.rect.top }, { it.rect.left }))
        for (block in sorted) {
            val text = block.text.trim()
            val r = block.rect
            val size = block.fontSize.coerceIn(8f, 12f)
            // « Nom : ______ » : on ecrit a la place des traits.
            LABEL_FILLER.matchEntire(text)?.let { m ->
                val label = cleanLabel(m.groupValues[1])
                if (label.isNotEmpty()) {
                    val start = text.indexOfFirst { it == '_' || it == '.' || it == '…' || it == '·' }.let { i ->
                        val filler = FILLER.find(text)
                        filler?.range?.first ?: i
                    }
                    val x = r.left + (r.right - r.left) * start / text.length.coerceAtLeast(1)
                    out += PaperField(page, label, NormRect(x + 2f / pageWidth, r.top, r.right, r.bottom), size, keyFor(label))
                    return@let
                }
            } ?: run {
                // « Nom : » sans rien a droite sur la meme ligne : on ecrit juste apres.
                val m = LABEL_COLON.matchEntire(text) ?: return@run
                val label = cleanLabel(m.groupValues[1])
                if (label.isEmpty() || label.any { it.isDigit() } && label.length < 4) return@run
                val sameLine = sorted.filter { other ->
                    other !== block && other.rect.left > r.right &&
                        min(other.rect.bottom, r.bottom) - max(other.rect.top, r.top) > (r.bottom - r.top) * 0.4f
                }
                val nextLeft = sameLine.minOfOrNull { it.rect.left }
                // Quelque chose ecrit juste apres : le champ est deja rempli (ou ce n'est pas un champ).
                if (nextLeft != null && nextLeft - r.right < 40f / pageWidth) return@run
                val right = min(nextLeft?.minus(6f / pageWidth) ?: 0.94f, 0.94f)
                if (right - r.right < 30f / pageWidth) return@run
                out += PaperField(page, label, NormRect(r.right + 6f / pageWidth, r.top, right, r.bottom), size, keyFor(label))
            }
        }
        return out
    }

    private fun cleanLabel(s: String) = s.trim().trimEnd(':', '：', '.', '_', ' ').trim()

    private val KEYS: List<Pair<ProfileKey, Regex>> = listOf(
        ProfileKey.PlaceDate to "lieu,? (et )?date|ort,? (und )?datum|luogo,? (e )?data|place,? (and )?date",
        ProfileKey.BirthDate to "date de naissance|n[ée]+ le|geburtsdatum|geboren am|data di nascita|nato il|date of birth|birth ?date",
        ProfileKey.FullName to "nom et pr[ée]nom|pr[ée]nom et nom|nom complet|name und vorname|vorname und name|name, vorname|nome e cognome|cognome e nome|full name",
        ProfileKey.PostalTown to "npa,? ?(/|et)? ?localit[ée]|npa,? ?(/|et)? ?lieu|code postal,? ?(/|et)? ?ville|plz,? ?(/|und)? ?ort|cap,? ?(/|e)? ?localit[àa]|postcode and town|zip and city",
        ProfileKey.FirstName to "pr[ée]nom|vorname|\\bnome\\b(?! e)|first name|given name",
        ProfileKey.LastName to "nom de famille|^nom$|^nom |nachname|familienname|^name$|cognome|surname|last name|family name",
        ProfileKey.Company to "entreprise|soci[ée]t[ée]|raison sociale|firma|unternehmen|ditta|azienda|company",
        ProfileKey.Street to "rue|adresse|strasse|straße|adresse|via|indirizzo|street|address",
        ProfileKey.PostalCode to "^npa|code postal|^plz|^cap$|postcode|zip",
        ProfileKey.Town to "localit[ée]|^lieu$|ville|^ort$|wohnort|localit[àa]|citt[àa]|comune|town|city",
        ProfileKey.Nationality to "nationalit[ée]|nationalit[äa]t|staatsangeh|nazionalit[àa]|nationality|citizenship",
        ProfileKey.Phone to "t[ée]l[ée]phone|^t[ée]l|natel|mobile|portable|telefon|handy|telefono|cellulare|phone",
        ProfileKey.Email to "e-?mail|courriel|^mail",
        ProfileKey.AvsNumber to "avs|ahv|assicurazione vecchiaia|social security",
        ProfileKey.Iban to "iban|n[°o] de compte|kontonummer|numero di conto|account number",
        ProfileKey.Date to "^date$|^datum$|^data$|^date |^datum |^data ",
    ).map { (k, p) -> k to Regex(p, RegexOption.IGNORE_CASE) }

    /** Ce que demande le libelle [label], ou null si on ne sait pas. */
    fun keyFor(label: String): ProfileKey? {
        val l = label.trim().lowercase()
        return KEYS.firstOrNull { (_, re) -> re.containsMatchIn(l) }?.first
    }

    /** La valeur du profil pour [key] ([profile] : valeurs gardees, [today] : date du jour affichee). */
    fun valueFor(key: ProfileKey, profile: Map<ProfileKey, String>, today: String): String {
        fun p(k: ProfileKey) = profile[k].orEmpty().trim()
        return when (key) {
            ProfileKey.FullName -> listOf(p(ProfileKey.FirstName), p(ProfileKey.LastName)).filter { it.isNotEmpty() }.joinToString(" ")
                .ifEmpty { p(ProfileKey.FullName) }
            ProfileKey.PostalTown -> listOf(p(ProfileKey.PostalCode), p(ProfileKey.Town)).filter { it.isNotEmpty() }.joinToString(" ")
            ProfileKey.PlaceDate -> listOf(p(ProfileKey.Town), today).filter { it.isNotEmpty() }.joinToString(", ")
            ProfileKey.Date -> today
            else -> p(key)
        }
    }

    /** Les informations qu'on garde dans le profil (les autres en sont tirees). */
    val STORED: List<ProfileKey> = listOf(
        ProfileKey.FirstName, ProfileKey.LastName, ProfileKey.Company, ProfileKey.Street, ProfileKey.PostalCode, ProfileKey.Town,
        ProfileKey.BirthDate, ProfileKey.Nationality, ProfileKey.Phone, ProfileKey.Email, ProfileKey.AvsNumber, ProfileKey.Iban,
    )
}
