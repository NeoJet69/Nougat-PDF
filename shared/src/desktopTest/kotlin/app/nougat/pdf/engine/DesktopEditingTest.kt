package app.nougat.pdf.engine

import kotlinx.coroutines.test.runTest
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopEditingTest {
    private val dir = createTempDirectory("nougat-edit").toFile()
    private val engine = DesktopPdfEngine()

    /** PDF dont chaque page porte son numero : « Page 1 », « Page 2 »... */
    private fun numbered(pages: Int, name: String = "src.pdf"): String {
        val file = File(dir, name)
        PDDocument().use { doc ->
            repeat(pages) { i ->
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 20f)
                    cs.newLineAtOffset(72f, 700f)
                    cs.showText("Page ${i + 1}")
                    cs.endText()
                }
            }
            doc.save(file)
        }
        return file.path
    }

    private fun out(name: String) = File(dir, name).path

    private fun texts(path: String, password: String = ""): List<String> = Loader.loadPDF(File(path), password).use { doc ->
        (1..doc.numberOfPages).map { p ->
            PDFTextStripper().apply { startPage = p; endPage = p }.getText(doc).trim()
        }
    }

    @Test
    fun reorderDeletesAndMovesPages() = runTest {
        val dst = out("reorder.pdf")
        engine.edit(numbered(4), null, dst) { it.reorder(listOf(3, 0, 2)) }
        assertEquals(listOf("Page 4", "Page 1", "Page 3"), texts(dst))
    }

    @Test
    fun rotatesPages() = runTest {
        val dst = out("rotate.pdf")
        engine.edit(numbered(2), null, dst) { it.setRotation(1, 450) }
        Loader.loadPDF(File(dst)).use { assertEquals(listOf(0, 90), listOf(it.getPage(0).rotation, it.getPage(1).rotation)) }
        engine.open(dst).use { doc ->
            // Page pivotee : largeur et hauteur echangees a l'affichage.
            assertEquals(PDRectangle.A4.height, doc.pageSizes[1].width, 0.5f)
        }
    }

    @Test
    fun burnsTextAtTheRightPlaceEvenOnRotatedPages() = runTest {
        for (rotation in listOf(0, 90, 180, 270)) {
            val rotated = out("r$rotation.pdf")
            engine.edit(numbered(1), null, rotated) { it.setRotation(0, rotation) }
            val dst = out("text$rotation.pdf")
            val size = engine.open(rotated).use { it.pageSizes[0] }
            engine.edit(rotated, null, dst) { doc ->
                doc.burn(listOf(TextMark(0, 0.5f, 0.1f, "Nougat", 18f, 0xFF000000.toInt(), size.width, size.height)))
            }
            engine.open(dst).use { doc ->
                val hit = TextSearch.find(0, doc.pageText(0), "nougat").single().rects.first()
                assertTrue(hit.left in 0.45f..0.55f && hit.top in 0.05f..0.2f, "rotation $rotation : $hit")
            }
        }
    }

    @Test
    fun burnsInkWhiteoutAndSignature() = runTest {
        val dst = out("marks.pdf")
        engine.edit(numbered(1), null, dst) { doc ->
            doc.burn(
                listOf(
                    InkMark(0, listOf(NPoint(0.1f, 0.1f), NPoint(0.5f, 0.5f)), 0xFF1E5BD8.toInt(), 2f),
                    InkMark(0, listOf(NPoint(0.2f, 0.2f), NPoint(0.6f, 0.2f)), 0xFFFFE14D.toInt(), 14f, 0.4f),
                    BoxMark(0, NormRect(0.1f, 0.1f, 0.4f, 0.2f), 0xFFFFFFFF.toInt()),
                    SignatureMark(0, NormRect(0.6f, 0.8f, 0.9f, 0.9f), listOf(listOf(NPoint(0f, 0f), NPoint(1f, 1f))), 0xFF1E5BD8.toInt()),
                ),
            )
        }
        engine.open(dst).use { assertEquals(1, it.pageCount); it.renderPage(0, 300) }
    }

    @Test
    fun insertsBlankPageAndAppendsAnotherPdf() = runTest {
        val dst = out("append.pdf")
        val other = numbered(2, "autre.pdf")
        engine.edit(numbered(2), null, dst) { doc ->
            doc.insertBlankPage(1, 595f, 842f)
            doc.appendPdf(other)
        }
        assertEquals(listOf("Page 1", "", "Page 2", "Page 1", "Page 2"), texts(dst))
    }

    @Test
    fun protectsAndUnprotects() = runTest {
        val locked = out("locked.pdf")
        engine.edit(numbered(1), null, locked) { it.setPassword("miaou") }
        assertFailsWith<PasswordRequiredException> { engine.open(locked) }
        // Une modification garde le mot de passe...
        val edited = out("edited.pdf")
        engine.edit(locked, "miaou", edited) { it.insertBlankPage(1, 100f, 100f) }
        assertFailsWith<PasswordRequiredException> { engine.open(edited) }
        // ...jusqu'a ce qu'on le retire.
        val open = out("open.pdf")
        engine.edit(edited, "miaou", open) { it.setPassword(null) }
        engine.open(open).use { assertEquals(2, it.pageCount) }
    }
}
