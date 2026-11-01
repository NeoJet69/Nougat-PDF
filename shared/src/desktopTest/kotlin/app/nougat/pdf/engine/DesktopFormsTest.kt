package app.nougat.pdf.engine

import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopFormsTest {
    private val dir = createTempDirectory("nougat-forms").toFile()

    private fun fields(path: String) = Loader.loadPDF(File(path)).use { pageFormFields(it, 0) }

    @Test
    fun readsEveryKindOfField() {
        val src = makeSampleForm(File(dir, "form.pdf"))
        val fields = fields(src)

        val nom = fields.single { it.name == "nom" }
        assertEquals(FieldKind.Text, nom.kind)
        assertEquals("Nom complet", nom.label)
        assertEquals("Jean Dupont", nom.value)
        assertEquals(12f, nom.fontSize)
        assertEquals(50f / 595f, nom.rect.left, 0.001f)
        assertEquals(350f / 595f, nom.rect.right, 0.001f)
        assertEquals(1f - 727f / 842f, nom.rect.top, 0.001f)
        assertEquals(1f - 705f / 842f, nom.rect.bottom, 0.001f)

        assertTrue(fields.single { it.name == "remarques" }.multiline)
        assertTrue(fields.single { it.name == "dossier" }.readOnly)

        val accord = fields.single { it.name == "accord" }
        assertEquals(FieldKind.CheckBox, accord.kind)
        assertEquals("Oui", accord.onValue)
        assertFalse(accord.checked)

        val formule = fields.filter { it.name == "formule" }
        assertEquals(listOf("Mensuelle", "Annuelle"), formule.map { it.onValue })
        assertEquals(listOf(true, false), formule.map { it.checked })

        val pays = fields.single { it.name == "pays" }
        assertEquals(FieldKind.Choice, pays.kind)
        assertEquals(listOf("France", "Suisse", "Belgique"), pays.options)
        assertEquals(listOf("FR", "CH", "BE"), pays.optionValues)
        assertEquals("France", pays.displayValue)

        assertTrue(Loader.loadPDF(File(src)).use { documentHasForm(it) })
    }

    @Test
    fun fillsAndKeepsTheValues() {
        val src = makeSampleForm(File(dir, "form.pdf"))
        val dst = File(dir, "rempli.pdf").absolutePath
        runBlocking {
            DesktopPdfEngine().edit(src, null, dst) { doc ->
                doc.setFieldValue("nom", "Marie Nougat")
                doc.setFieldValue("email", "marie@nougat.ch")
                doc.setFieldValue("remarques", "Première ligne\nDeuxième ligne : é à ç €")
                doc.setFieldValue("accord", "Oui")
                doc.setFieldValue("formule", "Annuelle")
                doc.setFieldValue("pays", "CH")
            }
        }
        val after = fields(dst)
        assertEquals("Marie Nougat", after.single { it.name == "nom" }.value)
        assertEquals("marie@nougat.ch", after.single { it.name == "email" }.value)
        assertEquals("Première ligne\nDeuxième ligne : é à ç €", after.single { it.name == "remarques" }.value)
        assertTrue(after.single { it.name == "accord" }.checked)
        assertEquals(listOf(false, true), after.filter { it.name == "formule" }.map { it.checked })
        assertEquals("Suisse", after.single { it.name == "pays" }.displayValue)

        // Le texte rempli est bien dessine sur la page (apparence regeneree).
        val doc = runBlocking { DesktopPdfEngine().open(dst) }
        doc.use { assertEquals(after.size, runBlocking { it.formFields(0) }.size) }

        // Decocher
        val dst2 = File(dir, "decoche.pdf").absolutePath
        runBlocking { DesktopPdfEngine().edit(dst, null, dst2) { it.setFieldValue("accord", FIELD_OFF) } }
        assertFalse(fields(dst2).single { it.name == "accord" }.checked)
    }

    @Test
    fun refusesWithAClearMessage() {
        val src = makeSampleForm(File(dir, "form.pdf"))
        assertFailsWith<FormFieldException> {
            runBlocking { DesktopPdfEngine().edit(src, null, File(dir, "a.pdf").absolutePath) { it.setFieldValue("dossier", "1") } }
        }
        assertFailsWith<FormFieldException> {
            runBlocking { DesktopPdfEngine().edit(src, null, File(dir, "b.pdf").absolutePath) { it.setFieldValue("inconnu", "1") } }
        }
        // Caractere absent de la police du formulaire (Helvetica, WinAnsi)
        val e = assertFailsWith<FormFieldException> {
            runBlocking { DesktopPdfEngine().edit(src, null, File(dir, "c.pdf").absolutePath) { it.setFieldValue("nom", "Łódź") } }
        }
        assertTrue(e.message!!.contains("police"))
    }

    @Test
    fun missingFontAndRotatedPage() {
        val src = makeSampleForm(File(dir, "tourne.pdf"), withFont = false, rotation = 90)
        val nom = fields(src).single { it.name == "nom" }
        // Page tournee de 90 degres : affichee en 842 x 595.
        assertEquals(705f / 842f, nom.rect.left, 0.001f)
        assertEquals(727f / 842f, nom.rect.right, 0.001f)
        assertEquals(1f - (595f - 50f) / 595f, nom.rect.top, 0.001f)
        assertEquals(1f - (595f - 350f) / 595f, nom.rect.bottom, 0.001f)

        val dst = File(dir, "tourne-rempli.pdf").absolutePath
        runBlocking { DesktopPdfEngine().edit(src, null, dst) { it.setFieldValue("nom", "Nougat") } }
        assertEquals("Nougat", fields(dst).single { it.name == "nom" }.value)
    }

    @Test
    fun noFormNoFields() {
        val plain = File(dir, "simple.pdf")
        org.apache.pdfbox.pdmodel.PDDocument().use { it.addPage(org.apache.pdfbox.pdmodel.PDPage()); it.save(plain) }
        val doc = runBlocking { DesktopPdfEngine().open(plain.absolutePath) }
        doc.use {
            assertFalse(runBlocking { it.hasForm() })
            assertTrue(runBlocking { it.formFields(0) }.isEmpty())
        }
    }

    @Test
    fun tabGoesToTheNextTextField() {
        val fields = fields(makeSampleForm(File(dir, "form.pdf")))
        val nom = fields.single { it.name == "nom" }
        // Lecture seule (dossier) sautee ; de haut en bas puis de gauche a droite.
        assertEquals("email", nextFieldAfter(nom, fields)?.name)
        assertEquals("remarques", nextFieldAfter(fields.single { it.name == "email" }, fields)?.name)
    }
}
