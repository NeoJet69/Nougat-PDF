package app.nougat.pdf.i18n

import app.nougat.pdf.viewer.copyName
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class I18nTest {
    @AfterTest
    fun backToFrench() {
        I18n.lang = Lang.Fr
    }

    private fun placeholders(text: String) = Regex("""\{\d}""").findAll(text).map { it.value }.toSortedSet()

    @Test
    fun twentyFourLanguagesSixteenAndEightDialects() {
        assertEquals(24, Lang.entries.size)
        assertEquals(16, Lang.entries.count { !it.dialect })
        assertEquals(8, Lang.entries.count { it.dialect })
        assertEquals(Lang.entries.size, Lang.entries.map { it.code }.toSet().size)
    }

    @Test
    fun everyLanguageIsCompleteAndWellFormed() {
        val french = parseTable(tableOf(Lang.Fr))
        assertEquals(Str.entries.size, french.size, "Le francais doit contenir toutes les cles")
        for (lang in Lang.entries) {
            val text = tableOf(lang)
            assertEquals(emptyList(), unknownKeys(text), "Cles inconnues en ${lang.code}")
            val table = parseTable(text)
            val missing = Str.entries.filter { it !in table }
            assertEquals(emptyList(), missing, "Textes manquants en ${lang.code}")
            for ((key, value) in table) {
                if ('|' in french.getValue(key)) continue // pluriels : {0} peut etre ecrit en toutes lettres
                assertEquals(placeholders(french.getValue(key)), placeholders(value), "{n} differents pour $key en ${lang.code}")
                assertTrue(value.isNotBlank(), "Texte vide pour $key en ${lang.code}")
            }
        }
    }

    @Test
    fun pluralsFollowEachLanguage() {
        fun pages(lang: Lang, n: Int) = I18n.stringsFor(lang).plural(Str.PagesCount, n)
        assertEquals("1 page", pages(Lang.Fr, 1))
        assertEquals("2 pages", pages(Lang.Fr, 2))
        assertEquals("1 page", pages(Lang.En, 1))
        assertEquals("3 pages", pages(Lang.En, 3))
        assertEquals("1 страница", pages(Lang.Ru, 1))
        assertEquals("3 страницы", pages(Lang.Ru, 3))
        assertEquals("5 страниц", pages(Lang.Ru, 5))
        assertEquals("21 страница", pages(Lang.Ru, 21))
        assertEquals("22 strony", pages(Lang.Pl, 22))
        assertEquals("25 stron", pages(Lang.Pl, 25))
        assertEquals("صفحتان", pages(Lang.Ar, 2))
        assertEquals("20 de pagini", pages(Lang.Ro, 20))
        assertEquals("5 页", pages(Lang.Zh, 5))
    }

    @Test
    fun argumentsAndLanguageSwitch() {
        I18n.lang = Lang.Fr
        assertEquals("Ouverture de « Facture.pdf »…", tr(Str.Opening, "Facture.pdf"))
        I18n.lang = Lang.De
        assertEquals("„Facture.pdf“ wird geöffnet…", tr(Str.Opening, "Facture.pdf"))
        I18n.lang = Lang.Ar
        assertTrue(I18n.lang.rtl)
    }

    @Test
    fun aDialectFallsBackOnItsLanguage() {
        val partial = Strings(Lang.Gsw, mapOf(Str.Ok to "Guet"), I18n.stringsFor(Lang.De))
        assertEquals("Guet", partial.get(Str.Ok))
        assertEquals("Schließen", partial.get(Str.Close))
    }

    @Test
    fun copiesAreMiaou() {
        assertEquals("Facture_miaou.pdf", copyName("Facture.pdf"))
        assertEquals("Contrat_miaou.pdf", copyName("Contrat.PDF"))
        assertEquals("notes_miaou.pdf", copyName("notes"))
    }
}
