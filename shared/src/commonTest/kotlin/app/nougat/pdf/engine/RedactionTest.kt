package app.nougat.pdf.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedactionTest {
    private val all = SensitiveKind.smart.toSet()

    private fun found(text: String, kinds: Set<SensitiveKind> = all, words: List<String> = emptyList()) =
        SensitiveData.detect(text, kinds, words).map { it.kind to text.substring(it.start, it.end) }

    @Test
    fun lettersFromSwitzerland() {
        val text = """
            Madame Marie Nougat
            Rue du Lac 4
            1003 Lausanne
            Tél. +41 79 123 45 67
            marie.nougat@example.ch
            N° AVS : 756.1234.5678.97
            Née le 12.03.1985
            IBAN : CH93 0076 2011 6238 5295 7
            Police n° 45-889-120
            Facture de Boulangerie Dupont SA, total CHF 1'250.00
        """.trimIndent()
        val result = found(text)
        assertTrue(SensitiveKind.Person to "Marie Nougat" in result, result.toString())
        assertTrue(SensitiveKind.Address to "Rue du Lac 4" in result, result.toString())
        assertTrue(SensitiveKind.Address to "1003 Lausanne" in result, result.toString())
        assertTrue(SensitiveKind.Phone to "+41 79 123 45 67" in result, result.toString())
        assertTrue(SensitiveKind.Email to "marie.nougat@example.ch" in result, result.toString())
        assertTrue(SensitiveKind.IdNumber to "756.1234.5678.97" in result, result.toString())
        assertTrue(SensitiveKind.BirthDate to "12.03.1985" in result, result.toString())
        assertTrue(SensitiveKind.Account to "CH93 0076 2011 6238 5295 7" in result, result.toString())
        assertTrue(SensitiveKind.Insurance to "45-889-120" in result, result.toString())
        assertTrue(SensitiveKind.Company to "Boulangerie Dupont SA" in result, result.toString())
        // Le montant n'est ni un telephone ni un compte.
        assertFalse(result.any { "1'250" in it.second }, result.toString())
    }

    @Test
    fun frenchAndGermanForms() {
        val result = found(
            """
            Client : Jean Dupont
            12 rue des Lilas, Lausanne
            Patient n° 99812
            Kunde: Hans Muster
            Bahnhofstrasse 12
            8001 Zürich
            Date : 15 mars 2023
            """.trimIndent(),
        )
        assertTrue(SensitiveKind.Person to "Jean Dupont" in result, result.toString())
        assertTrue(SensitiveKind.Person to "Hans Muster" in result, result.toString())
        assertTrue(result.any { it.first == SensitiveKind.Address && it.second.startsWith("12 rue des Lilas") }, result.toString())
        assertTrue(SensitiveKind.Address to "Bahnhofstrasse 12" in result, result.toString())
        assertTrue(SensitiveKind.Address to "8001 Zürich" in result, result.toString())
        assertTrue(SensitiveKind.Medical to "99812" in result, result.toString())
        // Une date ordinaire n'est pas une date de naissance.
        assertFalse(result.any { it.first == SensitiveKind.BirthDate }, result.toString())
    }

    @Test
    fun onlyTheChosenKindsAndWords() {
        val text = "Projet Alpha : contact marie@example.ch, tel 021 123 45 67"
        assertEquals(listOf(SensitiveKind.Email to "marie@example.ch"), found(text, setOf(SensitiveKind.Email)))
        // Les mots choisis se trouvent sans tenir compte des majuscules ni des accents.
        assertEquals(listOf(SensitiveKind.Custom to "Projet Alpha"), found(text, emptySet(), listOf("projet alpha")))
    }

    @Test
    fun checksumsAvoidFalseNumbers() {
        assertTrue(SensitiveData.ibanValid("CH93 0076 2011 6238 5295 7"))
        assertFalse(SensitiveData.ibanValid("CH93 0076 2011 6238 5295 8"))
        assertTrue(SensitiveData.luhnValid("4111 1111 1111 1111"))
        assertFalse(SensitiveData.luhnValid("4111 1111 1111 1112"))
    }

    @Test
    fun lineBreaksComeFromThePositions() {
        val a = NormRect(0.1f, 0.10f, 0.2f, 0.12f)
        val b = NormRect(0.1f, 0.15f, 0.2f, 0.17f)
        val page = PageText("Ab Cd", listOf(a, a, null, b, b))
        assertEquals("Ab\nCd", SensitiveData.linedText(page))
    }
}
