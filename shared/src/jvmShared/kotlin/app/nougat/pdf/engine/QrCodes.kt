package app.nougat.pdf.engine

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.qrcode.QRCodeMultiReader
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/** Codes QR (ZXing), communs a l'ordinateur et a Android : lire ceux d'une page, en fabriquer un. */
internal object QrCodes {
    /** Resolution du rendu de page pour chercher les codes QR (un module fait alors 3 a 6 pixels). */
    const val DPI = 220f

    private class GraySource(private val img: GrayImage) : LuminanceSource(img.width, img.height) {
        override fun getRow(y: Int, row: ByteArray?): ByteArray {
            val out = if (row == null || row.size < width) ByteArray(width) else row
            System.arraycopy(img.pixels, y * img.width, out, 0, width)
            return out
        }

        override fun getMatrix(): ByteArray =
            if (img.pixels.size == width * height) img.pixels else img.pixels.copyOf(width * height)
    }

    /** Les textes des codes QR trouves sur l'image (vide si aucun). */
    fun decode(img: GrayImage): List<String> {
        val hints = mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.CHARACTER_SET to "UTF-8",
        )
        return try {
            QRCodeMultiReader().decodeMultiple(BinaryBitmap(HybridBinarizer(GraySource(img))), hints)
                .mapNotNull { it.text }
                .distinct()
        } catch (e: ReaderException) {
            emptyList()
        }
    }

    /** Les modules d'un code QR (vrai = noir), sans marge ; correction M, comme le demande la QR-facture. */
    fun encode(text: String): List<BooleanArray> {
        val matrix = Encoder.encode(text, ErrorCorrectionLevel.M, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8")).matrix
        return List(matrix.height) { y -> BooleanArray(matrix.width) { x -> matrix.get(x, y).toInt() == 1 } }
    }
}

/** Ajoute la section paiement d'une QR-facture : sur une nouvelle page A4, ou en bas de la derniere page. */
internal fun EditableDoc.addSwissQrBill(bill: SwissQrBill, newPage: Boolean, language: QrLanguage) {
    val modules = QrCodes.encode(SwissQr.build(bill))
    val onNewPage = newPage || pageCount == 0
    if (onNewPage) insertBlankPage(pageCount, A4_WIDTH, A4_HEIGHT)
    drawSwissQrBill(pageCount - 1, bill, language, modules, clear = !onNewPage)
}
