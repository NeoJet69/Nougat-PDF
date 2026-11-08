package app.nougat.pdf.engine

/*
 * QR-facture suisse : lire le contenu du code QR (montant, compte, reference...) et dessiner la
 * section paiement (recepisse + section paiement, 210 x 105 mm en bas d'une page A4), selon les
 * directives suisses (Swiss Implementation Guidelines QR-bill, version 2.3).
 */

/** Une adresse de la QR-facture (adresse structuree, type « S »). */
data class QrAddress(
    val name: String = "",
    val street: String = "",
    val number: String = "",
    val postalCode: String = "",
    val town: String = "",
    val country: String = "CH",
) {
    val isEmpty: Boolean get() = listOf(name, street, number, postalCode, town).all { it.isBlank() }

    /** Les lignes a imprimer : nom, rue et numero, NPA et localite (pays devant pour l'etranger). */
    fun lines(): List<String> {
        val streetLine = listOf(street, number).filter { it.isNotBlank() }.joinToString(" ")
        val place = listOf(postalCode, town).filter { it.isNotBlank() }.joinToString(" ")
        val foreign = country.isNotBlank() && country.uppercase() !in setOf("CH", "LI")
        val placeLine = if (foreign && place.isNotEmpty()) "${country.uppercase()}-$place" else place
        return listOf(name, streetLine, placeLine).map { it.trim() }.filter { it.isNotEmpty() }
    }
}

enum class QrReferenceType { QRR, SCOR, NON }

/** Le contenu d'une QR-facture. [amount] : « 1949.75 », ou null si le payeur saisit le montant. */
data class SwissQrBill(
    val iban: String,
    val creditor: QrAddress,
    val amount: String? = null,
    val currency: String = "CHF",
    val debtor: QrAddress? = null,
    val reference: String = "",
    val message: String = "",
    val billInfo: String = "",
) {
    val referenceType: QrReferenceType get() = SwissQr.referenceType(reference)
}

/** Ce qui ne va pas dans une QR-facture a creer. */
enum class QrProblem { Iban, IbanCountry, NeedQrr, QrrNeedsQrIban, Reference, Amount, Creditor, Debtor }

/** Langue de la section paiement : les directives n'en connaissent que quatre. */
enum class QrLanguage(
    val code: String,
    val receipt: String,
    val paymentPart: String,
    val account: String,
    val reference: String,
    val additionalInfo: String,
    val payableBy: String,
    val payableByBlank: String,
    val currency: String,
    val amount: String,
    val acceptancePoint: String,
    val separate: String,
) {
    Fr(
        "FR", "Récépissé", "Section paiement", "Compte / Payable à", "Référence", "Informations supplémentaires",
        "Payable par", "Payable par (nom/adresse)", "Monnaie", "Montant", "Point de dépôt", "À détacher avant le versement",
    ),
    De(
        "DE", "Empfangsschein", "Zahlteil", "Konto / Zahlbar an", "Referenz", "Zusätzliche Informationen",
        "Zahlbar durch", "Zahlbar durch (Name/Adresse)", "Währung", "Betrag", "Annahmestelle", "Vor der Einzahlung abzutrennen",
    ),
    It(
        "IT", "Ricevuta", "Sezione pagamento", "Conto / Pagabile a", "Riferimento", "Informazioni supplementari",
        "Pagabile da", "Pagabile da (nome/indirizzo)", "Valuta", "Importo", "Punto di accettazione", "Da staccare prima del versamento",
    ),
    En(
        "EN", "Receipt", "Payment part", "Account / Payable to", "Reference", "Additional information",
        "Payable by", "Payable by (name/address)", "Currency", "Amount", "Acceptance point", "Separate before paying in",
    ),
}

object SwissQr {
    /** Lit le texte d'un code QR ; null si ce n'est pas une QR-facture suisse. */
    fun parse(payload: String): SwissQrBill? {
        val lines = payload.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        fun l(i: Int) = lines.getOrNull(i)?.trim().orEmpty()
        if (lines.size < 31 || l(0) != "SPC" || !l(1).startsWith("02") || l(2) != "1") return null
        fun address(at: Int): QrAddress? {
            if ((at..at + 6).all { l(it).isEmpty() }) return null
            // Type « K » (ancien) : deux lignes libres ; on les garde comme rue et localite.
            return if (l(at) == "K") QrAddress(l(at + 1), l(at + 2), "", "", l(at + 3), l(at + 6))
            else QrAddress(l(at + 1), l(at + 2), l(at + 3), l(at + 4), l(at + 5), l(at + 6))
        }
        val creditor = address(4) ?: return null
        val currency = l(19)
        if (currency != "CHF" && currency != "EUR") return null
        val trailer = (30 until lines.size).firstOrNull { l(it) == "EPD" } ?: return null
        return SwissQrBill(
            iban = compact(l(3)),
            creditor = creditor,
            amount = l(18).ifEmpty { null },
            currency = currency,
            debtor = address(20),
            reference = compact(l(28)),
            message = (29 until trailer).joinToString(" ") { l(it) }.trim(),
            billInfo = l(trailer + 1),
        )
    }

    /** Le texte du code QR (version 2.0, UTF-8, adresses structurees). */
    fun build(bill: SwissQrBill): String {
        val out = ArrayList<String>()
        fun clean(s: String) = s.replace('\n', ' ').replace('\r', ' ').trim()
        fun addr(a: QrAddress?) {
            if (a == null || a.isEmpty) repeat(7) { out += "" }
            else out += listOf("S", a.name, a.street, a.number, a.postalCode, a.town, a.country.uppercase()).map(::clean)
        }
        out += listOf("SPC", "0200", "1", compact(bill.iban))
        addr(bill.creditor)
        repeat(7) { out += "" } // createur final : reserve, toujours vide
        out += bill.amount?.let(::parseAmount).orEmpty()
        out += bill.currency
        addr(bill.debtor)
        out += bill.referenceType.name
        out += compact(bill.reference)
        out += clean(bill.message)
        out += "EPD"
        if (bill.billInfo.isNotBlank()) out += clean(bill.billInfo)
        return out.joinToString("\n")
    }

    fun problems(bill: SwissQrBill): List<QrProblem> {
        val out = ArrayList<QrProblem>()
        val iban = compact(bill.iban)
        if (!SensitiveData.ibanValid(iban)) out += QrProblem.Iban
        else if (!(iban.startsWith("CH") || iban.startsWith("LI")) || iban.length != 21) out += QrProblem.IbanCountry
        val qrIban = isQrIban(iban)
        when (bill.referenceType) {
            QrReferenceType.QRR -> if (!qrrValid(bill.reference)) out += QrProblem.Reference else if (!qrIban) out += QrProblem.QrrNeedsQrIban
            QrReferenceType.SCOR -> if (!scorValid(bill.reference)) out += QrProblem.Reference else if (qrIban) out += QrProblem.NeedQrr
            QrReferenceType.NON -> if (bill.reference.isNotBlank()) out += QrProblem.Reference else if (qrIban) out += QrProblem.NeedQrr
        }
        if (bill.amount != null && bill.amount.isNotBlank() && parseAmount(bill.amount) == null) out += QrProblem.Amount
        if (!addressComplete(bill.creditor)) out += QrProblem.Creditor
        bill.debtor?.takeIf { !it.isEmpty }?.let { if (!addressComplete(it)) out += QrProblem.Debtor }
        return out
    }

    private fun addressComplete(a: QrAddress) = a.name.isNotBlank() && a.postalCode.isNotBlank() && a.town.isNotBlank() &&
        a.country.trim().length == 2 && a.name.length <= 70 && a.street.length <= 70 && a.number.length <= 16 &&
        a.postalCode.length <= 16 && a.town.length <= 35

    /** Une QR-facture lue sur un document passe-t-elle les controles (IBAN, reference) ? */
    fun looksValid(bill: SwissQrBill): Boolean {
        val iban = compact(bill.iban)
        if (!SensitiveData.ibanValid(iban)) return false
        return when (bill.referenceType) {
            QrReferenceType.QRR -> qrrValid(bill.reference)
            QrReferenceType.SCOR -> scorValid(bill.reference)
            QrReferenceType.NON -> true
        }
    }

    fun referenceType(reference: String): QrReferenceType {
        val r = compact(reference)
        return when {
            r.isEmpty() -> QrReferenceType.NON
            r.startsWith("RF") -> QrReferenceType.SCOR
            r.all(Char::isDigit) -> QrReferenceType.QRR
            else -> QrReferenceType.NON
        }
    }

    /** QR-IBAN : l'identifiant de la banque (5e a 9e caracteres) est entre 30000 et 31999. */
    fun isQrIban(iban: String): Boolean {
        val c = compact(iban)
        if (c.length != 21) return false
        val iid = c.substring(4, 9).toIntOrNull() ?: return false
        return iid in 30000..31999
    }

    private val QRR_TABLE = intArrayOf(0, 9, 4, 6, 8, 2, 7, 1, 3, 5)

    /** Chiffre de controle d'une reference QR (modulo 10 recursif) pour les 26 premiers chiffres. */
    fun qrrCheckDigit(digits: String): Int {
        var carry = 0
        for (c in digits) carry = QRR_TABLE[(carry + (c - '0')) % 10]
        return (10 - carry) % 10
    }

    fun qrrValid(reference: String): Boolean {
        val r = compact(reference)
        return r.length == 27 && r.all(Char::isDigit) && qrrCheckDigit(r.take(26)) == r.last() - '0'
    }

    /** Reference creancier ISO 11649 (« RF » + 2 chiffres de controle + 1 a 21 caracteres). */
    fun scorValid(reference: String): Boolean {
        val r = compact(reference).uppercase()
        if (r.length !in 5..25 || !r.startsWith("RF") || !r.substring(2, 4).all(Char::isDigit)) return false
        if (!r.drop(4).all { it in '0'..'9' || it in 'A'..'Z' }) return false
        var rest = 0
        for (c in r.drop(4) + r.take(4)) {
            val digits = if (c.isDigit()) c.toString() else (c - 'A' + 10).toString()
            for (d in digits) rest = (rest * 10 + (d - '0')) % 97
        }
        return rest == 1
    }

    /**
     * « 1'949.75 », « 1 949,75 », « 1949.7 » → « 1949.75 » ; null si ce n'est pas un montant
     * entre 0.01 et 999 999 999.99.
     */
    fun parseAmount(text: String): String? {
        val t = text.filter { it !in " '’  " }
        val m = Regex("""^(\d{1,9})(?:[.,](\d{1,2}))?$""").matchEntire(t) ?: return null
        val units = m.groupValues[1].trimStart('0').ifEmpty { "0" }
        val cents = m.groupValues[2].padEnd(2, '0')
        if (units == "0" && cents == "00") return null
        return "$units.$cents"
    }

    /** « 1949.75 » → « 1 949.75 » (espace entre les milliers, comme sur la section paiement). */
    fun formatAmount(amount: String): String {
        val normalized = parseAmount(amount) ?: return amount
        val units = normalized.substringBefore('.')
        val grouped = units.reversed().chunked(3).joinToString(" ").reversed()
        return grouped + "." + normalized.substringAfter('.')
    }

    fun formatIban(iban: String) = compact(iban).chunked(4).joinToString(" ")

    /** Reference QR par blocs de 5 depuis la droite, reference creancier par blocs de 4. */
    fun formatReference(reference: String): String {
        val r = compact(reference)
        return when (referenceType(r)) {
            QrReferenceType.QRR -> r.reversed().chunked(5).joinToString(" ").reversed()
            QrReferenceType.SCOR -> r.chunked(4).joinToString(" ")
            QrReferenceType.NON -> r
        }
    }

    private fun compact(s: String) = s.filter { !it.isWhitespace() }.uppercase()

    /**
     * Un montant a payer trouve dans le texte d'une facture (apres « Total », « Montant »,
     * « à payer »...), pour proposer de le reprendre ; null si rien de clair.
     */
    fun suggestAmount(text: String): String? {
        val key = Regex(
            """(?i)(total|totale|gesamt|montant|betrag|importo|amount|à payer|a payer|zu zahlen|da pagare|due)""" +
                """[^\n\d]{0,40}?(\d{1,3}(?:['’   ]?\d{3})*[.,]\d{2})(?!\d)""",
        )
        val last = key.findAll(text).lastOrNull() ?: return null
        return parseAmount(last.groupValues[2])
    }
}

// ---- Dessin de la section paiement --------------------------------------------------------------

/** Hauteur de la section paiement en points (105 mm). */
const val QR_BILL_HEIGHT = 105f * 72f / 25.4f

/**
 * Dessine la section paiement en bas de la page [index] (format A4 attendu). [modules] : le code QR
 * (vrai = noir), sans marge. [clear] : peindre d'abord la zone en blanc (page deja remplie).
 */
fun EditableDoc.drawSwissQrBill(index: Int, bill: SwissQrBill, language: QrLanguage, modules: List<BooleanArray>, clear: Boolean) {
    draw(index) { canvas -> QrBillPainter(canvas, bill, language, modules).paint(clear) }
}

private class QrBillPainter(
    private val c: PageCanvas,
    private val bill: SwissQrBill,
    private val lang: QrLanguage,
    private val modules: List<BooleanArray>,
) {
    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private fun mm(v: Float) = v * 72f / 25.4f

    /** Ordonnee (points, depuis le bas de la page) d'une hauteur [t] en mm depuis le haut de la section. */
    private fun y(t: Float) = QR_BILL_HEIGHT - mm(t)

    private fun text(x: Float, baseline: Float, s: String, size: Float, bold: Boolean = false) =
        c.styledText(x, baseline, s, size, black, if (bold) FontKind.Bold else FontKind.Regular)

    private fun width(s: String, size: Float, bold: Boolean = false) = c.styledWidth(s, size, if (bold) FontKind.Bold else FontKind.Regular)

    /** Coupe [s] en lignes de largeur [maxWidth] au plus (par mots). */
    private fun wrap(s: String, size: Float, maxWidth: Float): List<String> {
        val out = ArrayList<String>()
        var line = ""
        for (word in s.split(' ').filter { it.isNotEmpty() }) {
            val tryLine = if (line.isEmpty()) word else "$line $word"
            if (line.isNotEmpty() && width(tryLine, size) > maxWidth) {
                out += line
                line = word
            } else {
                line = tryLine
            }
        }
        if (line.isNotEmpty()) out += line
        return out
    }

    fun paint(clear: Boolean) {
        if (clear) c.fillRect(0f, 0f, mm(210f), QR_BILL_HEIGHT, white)
        // Lignes de separation, et la mention pour un document envoye en PDF.
        c.stroke(floatArrayOf(0f, QR_BILL_HEIGHT, mm(210f), QR_BILL_HEIGHT), black, 0.5f)
        c.stroke(floatArrayOf(mm(62f), 0f, mm(62f), QR_BILL_HEIGHT), black, 0.5f)
        val note = lang.separate
        text((mm(210f) - width(note, 7f)) / 2f, QR_BILL_HEIGHT + 3f, note, 7f)
        receipt()
        paymentPart()
    }

    /** Cadre vide (montant ou payeur a remplir a la main) : seulement les quatre coins. */
    private fun corners(x: Float, top: Float, w: Float, h: Float) {
        val l = mm(3f)
        val b = top - h
        val t = 0.75f
        c.stroke(floatArrayOf(x, top - l, x, top, x + l, top), black, t)
        c.stroke(floatArrayOf(x + w - l, top, x + w, top, x + w, top - l), black, t)
        c.stroke(floatArrayOf(x, b + l, x, b, x + l, b), black, t)
        c.stroke(floatArrayOf(x + w - l, b, x + w, b, x + w, b + l), black, t)
    }

    private fun accountLines() = listOf(SwissQr.formatIban(bill.iban)) + bill.creditor.lines()

    private fun receipt() {
        val x = mm(5f)
        val w = mm(52f)
        text(x, y(5f) - 11f * 0.8f, lang.receipt, 11f, bold = true)
        var cursor = y(12f)
        fun heading(s: String) {
            text(x, cursor - 6f, s, 6f, bold = true); cursor -= 9f
        }
        fun value(s: String) {
            for (line in wrap(s, 8f, w)) {
                text(x, cursor - 8f, line, 8f); cursor -= 9f
            }
        }
        heading(lang.account)
        accountLines().forEach(::value)
        if (bill.reference.isNotBlank()) {
            cursor -= 9f
            heading(lang.reference)
            value(SwissQr.formatReference(bill.reference))
        }
        cursor -= 9f
        val debtor = bill.debtor?.takeIf { !it.isEmpty }
        if (debtor != null) {
            heading(lang.payableBy)
            debtor.lines().forEach(::value)
        } else {
            heading(lang.payableByBlank)
            corners(x, cursor - 2f, w, mm(20f))
        }
        // Montant
        val top = y(68f)
        text(x, top - 6f, lang.currency, 6f, bold = true)
        text(x, top - 6f - 11f, bill.currency, 8f)
        val amountX = x + mm(12f)
        text(amountX, top - 6f, lang.amount, 6f, bold = true)
        val amount = bill.amount?.let(SwissQr::parseAmount)
        if (amount != null) text(amountX, top - 6f - 11f, SwissQr.formatAmount(amount), 8f)
        else corners(x + w - mm(30f), top - 8f, mm(30f), mm(10f))
        // Point de depot
        val accept = lang.acceptancePoint
        text(x + w - width(accept, 6f, bold = true), y(82f) - 6f, accept, 6f, bold = true)
    }

    private fun paymentPart() {
        val x = mm(67f)
        text(x, y(5f) - 11f * 0.8f, lang.paymentPart, 11f, bold = true)
        qrCode(x, y(17f), mm(46f))
        // Montant, sous le code QR
        val top = y(68f)
        text(x, top - 8f, lang.currency, 8f, bold = true)
        text(x, top - 8f - 13f, bill.currency, 10f)
        val amountX = x + mm(14f)
        text(amountX, top - 8f, lang.amount, 8f, bold = true)
        val amount = bill.amount?.let(SwissQr::parseAmount)
        if (amount != null) text(amountX, top - 8f - 13f, SwissQr.formatAmount(amount), 10f)
        else corners(x + mm(12f), top - 11f, mm(40f), mm(15f))
        // Informations, a droite
        val ix = mm(118f)
        val iw = mm(87f)
        var cursor = y(5f)
        fun heading(s: String) {
            text(ix, cursor - 8f, s, 8f, bold = true); cursor -= 11f
        }
        fun value(s: String) {
            for (line in wrap(s, 10f, iw)) {
                text(ix, cursor - 10f, line, 10f); cursor -= 11f
            }
        }
        heading(lang.account)
        accountLines().forEach(::value)
        if (bill.reference.isNotBlank()) {
            cursor -= 11f
            heading(lang.reference)
            value(SwissQr.formatReference(bill.reference))
        }
        if (bill.message.isNotBlank() || bill.billInfo.isNotBlank()) {
            cursor -= 11f
            heading(lang.additionalInfo)
            if (bill.message.isNotBlank()) value(bill.message)
            if (bill.billInfo.isNotBlank()) value(bill.billInfo)
        }
        cursor -= 11f
        val debtor = bill.debtor?.takeIf { !it.isEmpty }
        if (debtor != null) {
            heading(lang.payableBy)
            debtor.lines().forEach(::value)
        } else {
            heading(lang.payableByBlank)
            corners(ix, cursor - 2f, mm(65f), mm(25f))
        }
    }

    /** Le code QR de [size] points, coin haut gauche en ([x], [top]), avec la croix suisse au milieu. */
    private fun qrCode(x: Float, top: Float, size: Float) {
        val n = modules.size
        if (n == 0) return
        val m = size / n
        for (row in 0 until n) {
            val line = modules[row]
            var col = 0
            while (col < n) {
                if (!line[col]) {
                    col++
                    continue
                }
                val start = col
                while (col < n && line[col]) col++
                // Un rien plus haut que le module : pas de fines lignes blanches entre les rangees.
                c.fillRect(x + start * m, top - (row + 1) * m - 0.02f, (col - start) * m, m + 0.04f, black)
            }
        }
        // Croix suisse : 7 mm, bord blanc, carre noir, croix blanche (proportions du drapeau).
        val cross = mm(7f)
        val cx = x + size / 2f
        val cy = top - size / 2f
        c.fillRect(cx - cross / 2f, cy - cross / 2f, cross, cross, white)
        val inner = mm(6f)
        c.fillRect(cx - inner / 2f, cy - inner / 2f, inner, inner, black)
        val arm = inner * 6f / 32f
        val len = inner * 20f / 32f
        c.fillRect(cx - arm / 2f, cy - len / 2f, arm, len, white)
        c.fillRect(cx - len / 2f, cy - arm / 2f, len, arm, white)
    }
}
