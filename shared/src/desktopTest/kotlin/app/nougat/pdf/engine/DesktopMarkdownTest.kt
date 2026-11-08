package app.nougat.pdf.engine

import kotlinx.coroutines.test.runTest
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopMarkdownTest {
    private val dir = createTempDirectory("nougat-md").toFile()
    private val engine = DesktopPdfEngine()

    private val sample = """
        # Devis de rénovation

        Merci pour votre demande. Voici le **détail** des travaux, avec un *lien* vers [notre site](https://artisango.ch).

        ## Travaux

        - Pose de carrelage
        - Fourniture des joints
          - joint gris

        1. Préparation
        2. Pose

        | Poste | Montant |
        | --- | --- |
        | Carrelage | 1 200.00 |
        | Déplacement | 60.00 |

        > Devis valable 30 jours.

        ```
        code: 42
        ```

        ---

        Fin du document.
    """.trimIndent()

    @Test
    fun parserFindsEveryKindOfBlock() {
        val blocks = MarkdownParser.parse(sample)
        assertEquals(2, blocks.count { it is MdBlock.Heading })
        assertEquals(5, blocks.count { it is MdBlock.ListItem })
        assertEquals(1, blocks.count { it is MdBlock.Table })
        assertEquals(1, blocks.count { it is MdBlock.Quote })
        assertEquals(1, blocks.count { it is MdBlock.Code })
        assertEquals(1, blocks.count { it is MdBlock.Rule })
        val intro = blocks.filterIsInstance<MdBlock.Paragraph>().first().spans
        assertTrue(intro.any { it.text == "détail" && it.font == FontKind.Bold })
        assertTrue(intro.any { it.text.contains("notre site (https://artisango.ch)") })
    }

    @Test
    fun markdownBecomesAPdfAndBack() = runTest {
        val pdf = File(dir, "devis.pdf").path
        engine.create(pdf) { MarkdownLayout.render(it, MarkdownParser.parse(sample)) }
        val text = Loader.loadPDF(File(pdf)).use { PDFTextStripper().getText(it) }
        assertTrue("Devis de rénovation" in text && "Carrelage" in text && "Fin du document." in text, text)

        val pages = engine.open(pdf).use { doc -> (0 until doc.pageCount).map { PageLines(doc.textBlocks(it), doc.pageSizes[it].width, doc.pageSizes[it].height) } }
        val md = PdfToMarkdown.convert(pages)
        assertTrue(md.contains("# Devis de rénovation"), md)
        assertTrue(md.contains("## Travaux"), md)
        assertTrue(md.contains("| Carrelage | 1 200.00 |"), md)
        assertTrue(md.contains("- Pose de carrelage") || md.contains("• Pose de carrelage"), md)
    }

    @Test
    fun longTextFlowsOnSeveralPages() = runTest {
        val long = (1..120).joinToString("\n\n") { "Paragraphe $it : un texte assez long pour remplir plusieurs pages du document, avec des mots." }
        val pdf = File(dir, "long.pdf").path
        engine.create(pdf) { MarkdownLayout.render(it, MarkdownParser.parse(long)) }
        Loader.loadPDF(File(pdf)).use { doc ->
            assertTrue(doc.numberOfPages >= 4, "${doc.numberOfPages} pages")
            assertTrue("Paragraphe 120" in PDFTextStripper().getText(doc))
        }
    }
}
