package app.nougat.pdf.engine

import kotlinx.coroutines.test.runTest
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopQrBillTest {
    private val dir = createTempDirectory("nougat-qr").toFile()
    private val engine = DesktopPdfEngine()

    private val bill = SwissQrBill(
        iban = "CH4431999123000889012",
        creditor = QrAddress("Artisan Démo Sàrl", "Rue du Lac", "12", "1003", "Lausanne"),
        amount = "1357.02",
        debtor = QrAddress("Famille Exemple", "Chemin des Vignes", "4", "1800", "Vevey"),
        reference = "210000000003139471430009017",
        message = "Facture 2026-114, carrelage salle de bain",
    )

    @Test
    fun addedBillCanBeReadBack() = runTest {
        val src = File(dir, "devis.pdf")
        engine.create(src.path) { it.insertBlankPage(0, A4_WIDTH, A4_HEIGHT) }
        val out = File(dir, "devis-qr.pdf")
        engine.edit(src.path, null, out.path) { it.addQrBill(bill, newPage = true, language = QrLanguage.Fr) }
        val doc = engine.open(out.path)
        try {
            assertEquals(2, doc.pageCount)
            val codes = doc.qrCodes(1)
            assertEquals(1, codes.size, "un code QR sur la page ajoutee")
            assertEquals(bill, SwissQr.parse(codes.single()))
            assertEquals(emptyList(), doc.qrCodes(0))
        } finally {
            doc.close()
        }
        val text = Loader.loadPDF(out).use { PDFTextStripper().getText(it) }
        assertTrue("Section paiement" in text && "Récépissé" in text, text)
        assertTrue("CH44 3199 9123 0008 8901 2" in text, text)
        assertTrue("1 357.02" in text, text)
        assertTrue("21 00000 00003 13947 14300 09017" in text, text)
    }

    @Test
    fun billAtTheBottomOfTheLastPageWithoutAmount() = runTest {
        val src = File(dir, "facture.pdf")
        engine.create(src.path) { it.insertBlankPage(0, A4_WIDTH, A4_HEIGHT) }
        val out = File(dir, "facture-qr.pdf")
        val open = bill.copy(amount = null, debtor = null, iban = "CH5800791123000889012", reference = "", message = "")
        engine.edit(src.path, null, out.path) { it.addQrBill(open, newPage = false, language = QrLanguage.De) }
        val doc = engine.open(out.path)
        try {
            assertEquals(1, doc.pageCount)
            assertEquals(open, SwissQr.parse(doc.qrCodes(0).single()))
        } finally {
            doc.close()
        }
        val text = Loader.loadPDF(out).use { PDFTextStripper().getText(it) }
        assertTrue("Zahlteil" in text && "Zahlbar durch (Name/Adresse)" in text, text)
    }
}
