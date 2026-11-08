package app.nougat.pdf.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LetterTasksTest {
    @Test
    fun readsTheAnswerOfTheAi() {
        val answer = """
            SUMMARY: Les impôts demandent votre déclaration 2025.
            Il manque le certificat de salaire.
            TASK: 2026-12-15 | Envoyer le certificat de salaire
            TASK: - | Signer la déclaration
            TASK: 2026-02-30 | Date impossible
        """.trimIndent()
        val digest = assertNotNull(LetterTasks.parseAnswer(answer))
        assertEquals("Les impôts demandent votre déclaration 2025. Il manque le certificat de salaire.", digest.summary)
        assertEquals(TodoItem("Envoyer le certificat de salaire", "2026-12-15"), digest.tasks[0])
        assertEquals(TodoItem("Signer la déclaration", null), digest.tasks[1])
        assertNull(digest.tasks[2].date, "une date qui n'existe pas est retiree")
        assertNull(LetterTasks.parseAnswer("Bonjour, je ne sais pas."))
        assertEquals(emptyList(), assertNotNull(LetterTasks.parseAnswer("SUMMARY: Rien.\nTASK: NONE")).tasks)
    }

    @Test
    fun findsDatesInManyForms() {
        val text = "Lausanne, le 3 novembre 2026. Payable jusqu'au 30.11.2026, sinon rappel le 15/12/26. " +
            "Termin am 7. Januar 2027. Meeting on December 31, 2026. Ref 2026-11-20."
        val dates = LetterTasks.findDates(text).map { it.second }
        assertEquals(listOf("2026-11-03", "2026-11-30", "2026-12-15", "2027-01-07", "2026-12-31", "2026-11-20"), dates)
        assertEquals(emptyList(), LetterTasks.findDates("Facture 31.02.2026 et 45/13/2026"))
    }

    @Test
    fun deadlinesWithoutAi() {
        val text = """
            Lausanne, le 3 novembre 2026
            Concerne : facture 2026-114
            Montant à payer avant le 30.11.2026.
            Rendez-vous de contrôle le 12 janvier 2027 à 9 h.
            Ancienne facture du 01.10.2026.
        """.trimIndent()
        val items = LetterTasks.findDeadlines(text, "2026-11-08")
        assertEquals(listOf("2026-11-30", "2027-01-12"), items.map { it.date })
        assertTrue(items[0].what.startsWith("Montant à payer"))
    }

    @Test
    fun nextDayCrossesMonthsAndYears() {
        assertEquals("2026-12-01", LetterTasks.nextDay("2026-11-30"))
        assertEquals("2027-01-01", LetterTasks.nextDay("2026-12-31"))
        assertEquals("2028-02-29", LetterTasks.nextDay("2028-02-28"))
    }
}

class LanguageGuessTest {
    @Test
    fun guessesTheMainLanguages() {
        kotlin.test.assertEquals("fr", LanguageGuess.guess("Nous vous remercions pour votre commande et vous prions de trouver ci-joint la facture."))
        kotlin.test.assertEquals("de", LanguageGuess.guess("Wir danken Ihnen für die Bestellung und bitten Sie, die Rechnung bis zum Ende des Monats zu bezahlen."))
        kotlin.test.assertEquals("it", LanguageGuess.guess("La ringraziamo per il suo ordine e le inviamo la fattura che è da pagare entro la fine del mese."))
        kotlin.test.assertEquals("en", LanguageGuess.guess("Thank you for your order. Please find the invoice attached and pay it by the end of the month."))
        kotlin.test.assertNull(LanguageGuess.guess("12.03.2026 CHF 450.00"))
    }
}
