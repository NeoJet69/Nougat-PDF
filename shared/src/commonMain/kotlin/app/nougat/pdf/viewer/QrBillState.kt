package app.nougat.pdf.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.nougat.pdf.engine.QR_BILL_HEIGHT
import app.nougat.pdf.engine.QrAddress
import app.nougat.pdf.engine.QrLanguage
import app.nougat.pdf.engine.QrProblem
import app.nougat.pdf.engine.SwissQr
import app.nougat.pdf.engine.SwissQrBill
import app.nougat.pdf.i18n.Lang
import kotlinx.coroutines.CancellationException

/** Une QR-facture trouvee sur la page [page] du document. */
class FoundQrBill(val page: Int, val bill: SwissQrBill) {
    /** L'IBAN et la reference passent les controles (sinon : prudence avant de payer). */
    val valid: Boolean = SwissQr.looksValid(bill)
}

/** Ce que l'utilisateur remplit pour ajouter une QR-facture. */
data class QrBillForm(
    val iban: String = "",
    val name: String = "",
    val street: String = "",
    val number: String = "",
    val postalCode: String = "",
    val town: String = "",
    val country: String = "CH",
    val amount: String = "",
    val currency: String = "CHF",
    val reference: String = "",
    val message: String = "",
    val withDebtor: Boolean = false,
    val debtor: QrAddress = QrAddress(),
    val newPage: Boolean = true,
    val language: QrLanguage = QrLanguage.Fr,
) {
    fun bill() = SwissQrBill(
        iban = iban,
        creditor = QrAddress(name.trim(), street.trim(), number.trim(), postalCode.trim(), town.trim(), country.trim().uppercase()),
        amount = amount.takeIf { it.isNotBlank() }?.let { SwissQr.parseAmount(it) ?: it },
        currency = currency,
        debtor = if (withDebtor) debtor.copy(country = debtor.country.trim().uppercase()) else null,
        reference = reference,
        message = message.trim(),
    )
}

/**
 * La QR-facture du document affiche : celles qu'on y trouve (montant, compte, reference a copier
 * pour l'e-banking), et le formulaire pour en ajouter une a un devis ou une facture. Les
 * coordonnees de l'utilisateur sont gardees sur l'appareil pour la prochaine fois.
 */
class QrBillState(private val files: PlatformFiles) {
    var dialogOpen by mutableStateOf(false)

    /** Le formulaire « Ajouter une QR-facture » est affiche (sinon : ce qui a ete trouve). */
    var creating by mutableStateOf(false)

    var searching by mutableStateOf(false)
        private set

    /** Pages deja regardees, nombre de pages a regarder. */
    var progress by mutableStateOf(0 to 0)
        private set

    val found = mutableStateListOf<FoundQrBill>()

    var form by mutableStateOf(QrBillForm())

    var problems by mutableStateOf<List<QrProblem>>(emptyList())

    /** La derniere page est au format A4 et son bas est libre : la section paiement peut y aller. */
    var lastPageFits by mutableStateOf(false)
        private set

    fun close() {
        dialogOpen = false
        creating = false
        searching = false
        problems = emptyList()
    }

    /** Cherche les codes QR des pages, en commencant par la fin (la ou se trouve la section paiement). */
    suspend fun search(s: DocumentSession) {
        found.clear()
        val pages = (s.pageCount - 1 downTo 0).take(MAX_PAGES)
        searching = true
        progress = 0 to pages.size
        try {
            for ((done, page) in pages.withIndex()) {
                val codes = try {
                    s.document.qrCodes(page)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    emptyList()
                }
                for (code in codes) SwissQr.parse(code)?.let { bill ->
                    if (found.none { it.bill == bill }) {
                        found += FoundQrBill(page, bill)
                        found.sortBy { it.page }
                    }
                }
                progress = done + 1 to pages.size
            }
        } finally {
            searching = false
        }
    }

    /** Prepare le formulaire : coordonnees enregistrees, montant trouve dans le texte, place libre. */
    suspend fun startCreate(s: DocumentSession, lang: Lang) {
        val last = s.pageCount - 1
        val size = s.pageSizes.getOrNull(last)
        val a4 = size != null && kotlin.math.abs(size.width - 595.28f) < 12f && size.height > size.width
        val blocks = if (last >= 0) runCatching { s.textBlocks(last) }.getOrDefault(emptyList()) else emptyList()
        val limit = if (size != null) 1f - (QR_BILL_HEIGHT + 12f) / size.height else 0f
        lastPageFits = a4 && blocks.isNotEmpty() && blocks.none { it.rect.bottom > limit }

        val text = StringBuilder()
        for (page in 0 until s.pageCount.coerceAtMost(MAX_PAGES)) {
            runCatching { text.append(s.pageTextOf(page).text).append('\n') }
        }
        val saved = loadCreditor()
        form = saved.copy(
            amount = SwissQr.suggestAmount(text.toString())?.let(SwissQr::formatAmount).orEmpty(),
            newPage = !lastPageFits,
            language = saved.language.takeIf { load(KEY_LANGUAGE) != null } ?: languageFor(lang),
        )
        problems = emptyList()
        creating = true
    }

    private fun load(key: String): String? = runCatching { files.loadSetting(key) }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun loadCreditor() = QrBillForm(
        iban = load(KEY_IBAN).orEmpty(),
        name = load(KEY_NAME).orEmpty(),
        street = load(KEY_STREET).orEmpty(),
        number = load(KEY_NUMBER).orEmpty(),
        postalCode = load(KEY_POSTAL).orEmpty(),
        town = load(KEY_TOWN).orEmpty(),
        country = load(KEY_COUNTRY) ?: "CH",
        currency = load(KEY_CURRENCY) ?: "CHF",
        language = load(KEY_LANGUAGE)?.let { code -> QrLanguage.entries.firstOrNull { it.code == code } } ?: QrLanguage.Fr,
    )

    /** Garde les coordonnees de l'utilisateur pour la prochaine QR-facture. */
    fun saveCreditor() {
        val f = form
        runCatching {
            files.saveSetting(KEY_IBAN, SwissQr.formatIban(f.iban))
            files.saveSetting(KEY_NAME, f.name.trim())
            files.saveSetting(KEY_STREET, f.street.trim())
            files.saveSetting(KEY_NUMBER, f.number.trim())
            files.saveSetting(KEY_POSTAL, f.postalCode.trim())
            files.saveSetting(KEY_TOWN, f.town.trim())
            files.saveSetting(KEY_COUNTRY, f.country.trim().uppercase())
            files.saveSetting(KEY_CURRENCY, f.currency)
            files.saveSetting(KEY_LANGUAGE, f.language.code)
        }
    }

    companion object {
        /** Au-dela, on ne regarde que les dernieres pages (une facture n'en a pas tant). */
        const val MAX_PAGES = 30

        private const val KEY_IBAN = "qrbill.iban"
        private const val KEY_NAME = "qrbill.name"
        private const val KEY_STREET = "qrbill.street"
        private const val KEY_NUMBER = "qrbill.number"
        private const val KEY_POSTAL = "qrbill.postalCode"
        private const val KEY_TOWN = "qrbill.town"
        private const val KEY_COUNTRY = "qrbill.country"
        private const val KEY_CURRENCY = "qrbill.currency"
        private const val KEY_LANGUAGE = "qrbill.language"

        /** La langue de la section paiement qui va avec la langue de l'app. */
        fun languageFor(lang: Lang): QrLanguage = when (lang) {
            Lang.De, Lang.Gsw, Lang.Rm, Lang.Als -> QrLanguage.De
            Lang.It -> QrLanguage.It
            Lang.Fr, Lang.Qc, Lang.Oc, Lang.Br, Lang.Co, Lang.Miaou -> QrLanguage.Fr
            else -> QrLanguage.En
        }
    }
}
