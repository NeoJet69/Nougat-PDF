package app.nougat.pdf.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwissQrBillTest {
    // L'exemple des directives suisses (QR-IBAN avec reference QR).
    private val example = listOf(
        "SPC", "0200", "1", "CH4431999123000889012",
        "S", "Robert Schneider AG", "Rue du Lac", "1268", "2501", "Biel", "CH",
        "", "", "", "", "", "", "",
        "1949.75", "CHF",
        "S", "Pia-Maria Rutschmann-Schnyder", "Grosse Marktgasse", "28", "9400", "Rorschach", "CH",
        "QRR", "210000000003139471430009017",
        "Order of 15 June 2020",
        "EPD",
        "//S1/10/10201409/11/200701/20/140.000-53/30/102673831/31/200615/32/7.7/33/7.7:4.95",
    ).joinToString("\r\n")

    @Test
    fun readsTheExampleOfTheGuidelines() {
        val bill = assertNotNull(SwissQr.parse(example))
        assertEquals("CH4431999123000889012", bill.iban)
        assertEquals("Robert Schneider AG", bill.creditor.name)
        assertEquals(listOf("Robert Schneider AG", "Rue du Lac 1268", "2501 Biel"), bill.creditor.lines())
        assertEquals("1949.75", bill.amount)
        assertEquals("CHF", bill.currency)
        assertEquals("Rorschach", bill.debtor?.town)
        assertEquals(QrReferenceType.QRR, bill.referenceType)
        assertEquals("Order of 15 June 2020", bill.message)
        assertTrue(bill.billInfo.startsWith("//S1/10/"))
        assertTrue(SwissQr.looksValid(bill))
        assertEquals(emptyList(), SwissQr.problems(bill))
    }

    @Test
    fun buildThenParseGivesTheSameBill() {
        val bill = assertNotNull(SwissQr.parse(example))
        assertEquals(bill, SwissQr.parse(SwissQr.build(bill)))
        val noAmount = bill.copy(amount = null, debtor = null, reference = "", iban = "CH5800791123000889012", message = "")
        val again = assertNotNull(SwissQr.parse(SwissQr.build(noAmount)))
        assertNull(again.amount)
        assertNull(again.debtor)
        assertEquals(QrReferenceType.NON, again.referenceType)
    }

    @Test
    fun otherQrCodesAreNotBills() {
        assertNull(SwissQr.parse("https://artisango.ch"))
        assertNull(SwissQr.parse(example.replace("CHF", "USD")))
    }

    @Test
    fun checksIbanAndReferences() {
        assertTrue(SwissQr.isQrIban("CH44 3199 9123 0008 8901 2"))
        assertFalse(SwissQr.isQrIban("CH93 0076 2011 6238 5295 7"))
        assertTrue(SwissQr.qrrValid("21 00000 00003 13947 14300 09017"))
        assertFalse(SwissQr.qrrValid("210000000003139471430009018"))
        assertTrue(SwissQr.scorValid("RF18 5390 0754 7034"))
        assertFalse(SwissQr.scorValid("RF19539007547034"))

        val base = assertNotNull(SwissQr.parse(example))
        assertEquals(listOf(QrProblem.NeedQrr), SwissQr.problems(base.copy(reference = "")))
        assertEquals(listOf(QrProblem.QrrNeedsQrIban), SwissQr.problems(base.copy(iban = "CH9300762011623852957")))
        assertEquals(listOf(QrProblem.Iban), SwissQr.problems(base.copy(iban = "CH4431999123000889013")))
        assertEquals(listOf(QrProblem.IbanCountry), SwissQr.problems(base.copy(iban = "DE89370400440532013000", reference = "")))
        assertEquals(listOf(QrProblem.Amount), SwissQr.problems(base.copy(amount = "12,345")))
        assertEquals(listOf(QrProblem.Creditor), SwissQr.problems(base.copy(creditor = base.creditor.copy(town = ""))))
    }

    @Test
    fun formatsLikeThePaymentPart() {
        assertEquals("CH44 3199 9123 0008 8901 2", SwissQr.formatIban("CH4431999123000889012"))
        assertEquals("21 00000 00003 13947 14300 09017", SwissQr.formatReference("210000000003139471430009017"))
        assertEquals("RF18 5390 0754 7034", SwissQr.formatReference("RF18539007547034"))
        assertEquals("1 949.75", SwissQr.formatAmount("1949.75"))
        assertEquals("1234567.50", SwissQr.parseAmount("1'234'567,5"))
        assertEquals("12.00", SwissQr.parseAmount("12"))
        assertNull(SwissQr.parseAmount("0"))
        assertNull(SwissQr.parseAmount("abc"))
    }

    @Test
    fun findsTheTotalOfAnInvoice() {
        val text = "Carrelage 1 200.00\nDéplacement 60.00\nTotal TTC CHF 1'357.02\nMerci"
        assertEquals("1357.02", SwissQr.suggestAmount(text))
        assertNull(SwissQr.suggestAmount("Bonjour, voici le plan."))
    }
}
