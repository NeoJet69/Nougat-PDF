package app.nougat.pdf.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaperFormTest {
    private val w = 595f
    private val h = 842f

    private fun line(text: String, left: Float, top: Float, right: Float) =
        TextBlock(0, text, NormRect(left, top, right, top + 0.016f), 10f, editable = false, glyphs = emptyList(), scanned = true)

    @Test
    fun findsFieldsAfterLabels() {
        val blocks = listOf(
            line("Formulaire d'inscription", 0.1f, 0.05f, 0.5f),
            line("Nom : ____________________", 0.1f, 0.10f, 0.6f),
            line("Prénom ......................", 0.1f, 0.13f, 0.6f),
            line("Date de naissance :", 0.1f, 0.16f, 0.3f),
            line("NPA / Localité :", 0.1f, 0.19f, 0.27f),
            line("Téléphone :", 0.1f, 0.22f, 0.2f),
            line("Lausanne", 0.23f, 0.22f, 0.35f),
            line("Lieu et date : ____________", 0.1f, 0.30f, 0.5f),
        )
        val fields = PaperForms.detect(0, blocks, w, h)
        assertEquals(
            listOf("Nom", "Prénom", "Date de naissance", "NPA / Localité", "Lieu et date"),
            fields.map { it.label },
            "Téléphone est deja rempli (Lausanne juste apres)",
        )
        assertEquals(
            listOf(ProfileKey.LastName, ProfileKey.FirstName, ProfileKey.BirthDate, ProfileKey.PostalTown, ProfileKey.PlaceDate),
            fields.map { it.key },
        )
        val nom = fields[0]
        assertTrue(nom.rect.left > 0.15f && nom.rect.left < 0.25f, "on ecrit sur les traits, pas sur le libelle : ${nom.rect}")
        val birth = fields[2]
        assertTrue(birth.rect.left > 0.3f && birth.rect.right > 0.8f, "on ecrit apres les deux-points : ${birth.rect}")
    }

    @Test
    fun recognizesLabelsInSeveralLanguages() {
        assertEquals(ProfileKey.FirstName, PaperForms.keyFor("Vorname"))
        assertEquals(ProfileKey.LastName, PaperForms.keyFor("Cognome"))
        assertEquals(ProfileKey.FullName, PaperForms.keyFor("Nom et prénom"))
        assertEquals(ProfileKey.PostalTown, PaperForms.keyFor("PLZ / Ort"))
        assertEquals(ProfileKey.Street, PaperForms.keyFor("Adresse"))
        assertEquals(ProfileKey.AvsNumber, PaperForms.keyFor("N° AVS"))
        assertEquals(ProfileKey.Email, PaperForms.keyFor("E-mail"))
        assertEquals(ProfileKey.Date, PaperForms.keyFor("Date"))
        assertEquals(ProfileKey.BirthDate, PaperForms.keyFor("Geburtsdatum"))
        assertNull(PaperForms.keyFor("Remarques"))
    }

    @Test
    fun valuesComeFromTheProfile() {
        val profile = mapOf(ProfileKey.FirstName to "Dylan", ProfileKey.LastName to "Exemple", ProfileKey.PostalCode to "1003", ProfileKey.Town to "Lausanne")
        assertEquals("Dylan Exemple", PaperForms.valueFor(ProfileKey.FullName, profile, "08.11.2026"))
        assertEquals("1003 Lausanne", PaperForms.valueFor(ProfileKey.PostalTown, profile, "08.11.2026"))
        assertEquals("Lausanne, 08.11.2026", PaperForms.valueFor(ProfileKey.PlaceDate, profile, "08.11.2026"))
        assertEquals("08.11.2026", PaperForms.valueFor(ProfileKey.Date, profile, "08.11.2026"))
        assertEquals("", PaperForms.valueFor(ProfileKey.Phone, profile, "08.11.2026"))
    }
}
