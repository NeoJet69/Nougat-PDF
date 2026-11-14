package app.nougat.pdf.viewer

import app.nougat.pdf.engine.imagePageLayout
import app.nougat.pdf.i18n.Str
import app.nougat.pdf.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory
import java.awt.Frame
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import javax.swing.JOptionPane

/**
 * Scanner sur Windows : le scanner ou l'imprimante multifonction branche a l'ordinateur, par WIA
 * (le service de numerisation de Windows, appele par PowerShell). La fenetre de Windows permet de
 * choisir l'appareil, la couleur et la resolution. Une page a la fois ; apres chaque page, Nougat
 * demande s'il faut en scanner une autre, puis toutes les pages forment un PDF.
 */
internal object WindowsScanner {
    val supported: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")

    /** Scanne une page dans [target] ; faux si l'utilisateur annule. Leve une exception sans scanner. */
    private fun scanPage(target: File): Boolean {
        val path = target.absolutePath.replace("'", "''")
        val script = """
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}dialog = New-Object -ComObject WIA.CommonDialog
            ${'$'}image = ${'$'}dialog.ShowAcquireImage(1, 0, 131072, '{B96B3CAE-0728-11D3-9D7B-0000F81EF32E}', ${'$'}false, ${'$'}true, ${'$'}false)
            if (${'$'}image -eq ${'$'}null) { exit 2 }
            ${'$'}image.SaveFile('$path')
            exit 0
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        return when (process.waitFor()) {
            0 -> target.isFile && target.length() > 0
            2 -> false
            else -> throw IllegalStateException(output.lines().firstOrNull { it.isNotBlank() } ?: "WIA")
        }
    }

    /** Scanne une ou plusieurs pages et renvoie un PDF (dans [tempDir]) ; null si rien n'a ete scanne. */
    suspend fun scan(tempDir: String, frame: Frame?): String? = withContext(Dispatchers.IO) {
        val pages = ArrayList<File>()
        try {
            while (true) {
                val file = File(tempDir, "scan-${System.nanoTime()}.img")
                if (!scanPage(file)) break
                pages += file
                val more = withContext(Dispatchers.Main) {
                    JOptionPane.showConfirmDialog(
                        frame, tr(Str.ScanAnotherPage, pages.size), tr(Str.ScanDocument), JOptionPane.YES_NO_OPTION,
                    ) == JOptionPane.YES_OPTION
                }
                if (!more) break
            }
            if (pages.isEmpty()) return@withContext null
            val out = File(tempDir, "scan-${System.nanoTime()}.pdf")
            PDDocument().use { doc ->
                for (file in pages) {
                    val image = ImageIO.read(file) ?: continue
                    // JPEG : un scan en BMP serait enorme dans le PDF.
                    val rgb = if (image.type == java.awt.image.BufferedImage.TYPE_INT_RGB) image else
                        java.awt.image.BufferedImage(image.width, image.height, java.awt.image.BufferedImage.TYPE_INT_RGB).also {
                            val g = it.createGraphics(); g.color = java.awt.Color.WHITE; g.fillRect(0, 0, it.width, it.height); g.drawImage(image, 0, 0, null); g.dispose()
                        }
                    val pdfImage = JPEGFactory.createFromImage(doc, rgb, 0.85f)
                    val l = imagePageLayout(rgb.width, rgb.height)
                    val page = PDPage(PDRectangle(l[0], l[1]))
                    doc.addPage(page)
                    PDPageContentStream(doc, page).use { it.drawImage(pdfImage, l[2], l[3], l[4], l[5]) }
                }
                if (doc.numberOfPages == 0) return@withContext null
                doc.save(out)
            }
            out.absolutePath
        } finally {
            pages.forEach { it.delete() }
        }
    }
}
