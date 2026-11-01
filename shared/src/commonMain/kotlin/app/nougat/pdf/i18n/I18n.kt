package app.nougat.pdf.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.intl.Locale

/**
 * Les langues de Nougat : 16 langues et 8 dialectes. Un dialecte (ou une traduction incomplete)
 * reprend les textes de sa langue de [fallback], puis l'anglais, puis le francais.
 */
enum class Lang(
    val code: String,
    val nativeName: String,
    val dialect: Boolean = false,
    /** Ecriture de droite a gauche (arabe). */
    val rtl: Boolean = false,
    val fallback: String? = null,
) {
    Fr("fr", "Français"),
    En("en", "English"),
    De("de", "Deutsch"),
    It("it", "Italiano"),
    Es("es", "Español"),
    Pt("pt", "Português"),
    Nl("nl", "Nederlands"),
    Pl("pl", "Polski"),
    Tr("tr", "Türkçe"),
    Ru("ru", "Русский"),
    Uk("uk", "Українська"),
    Ar("ar", "العربية", rtl = true),
    Zh("zh", "中文"),
    Sv("sv", "Svenska"),
    El("el", "Ελληνικά"),
    Ro("ro", "Română"),

    Gsw("gsw", "Schwiizerdütsch", dialect = true, fallback = "de"),
    Rm("rm", "Rumantsch", dialect = true, fallback = "de"),
    Qc("fr-CA", "Français québécois", dialect = true, fallback = "fr"),
    Als("als", "Elsässisch", dialect = true, fallback = "fr"),
    Oc("oc", "Occitan", dialect = true, fallback = "fr"),
    Br("br", "Brezhoneg", dialect = true, fallback = "fr"),
    Co("co", "Corsu", dialect = true, fallback = "fr"),
    Miaou("miaou", "Miaou 🐱", dialect = true, fallback = "fr");

    companion object {
        fun fromCode(code: String?): Lang? = entries.firstOrNull { it.code == code }

        /** La langue de l'appareil si Nougat la parle, sinon l'anglais. */
        fun fromSystem(): Lang {
            val locale = runCatching { Locale.current }.getOrNull() ?: return En
            val language = locale.language.lowercase()
            if (language == "fr" && locale.region.uppercase() == "CA") return Qc
            return entries.firstOrNull { it.code == language } ?: En
        }
    }
}

/** Les textes d'une langue. Les valeurs peuvent contenir {0}, {1}... et des formes du pluriel « a|b ». */
class Strings internal constructor(
    val lang: Lang,
    private val table: Map<Str, String>,
    private val fallback: Strings?,
) {
    /** Le texte brut (avec ses {0} et ses formes du pluriel). */
    fun raw(key: Str): String = table[key] ?: fallback?.raw(key) ?: key.name

    fun get(key: Str, vararg args: Any?): String = format(firstForm(raw(key)), args)

    /** Le texte qui convient au nombre [n] (qui remplace {0}) ; [args] remplacent {1}, {2}... */
    fun plural(key: Str, n: Int, vararg args: Any?): String {
        val own = table[key] ?: return fallback?.plural(key, n, *args) ?: key.name
        val forms = own.split('|')
        val form = forms[pluralIndex(lang, n).coerceAtMost(forms.size - 1)]
        return format(form, arrayOf<Any?>(n, *args))
    }

    fun has(key: Str): Boolean = table.containsKey(key)

    internal fun keys(): Set<Str> = table.keys

    private fun firstForm(text: String) = if ('|' in text) text.substringBefore('|') else text

    private fun format(text: String, args: Array<out Any?>): String {
        if (args.isEmpty() || '{' !in text) return text
        var out = text
        args.forEachIndexed { i, a -> out = out.replace("{$i}", a?.toString() ?: "") }
        return out
    }
}

/** Forme du pluriel a utiliser (0 = singulier) selon les regles de chaque langue. */
internal fun pluralIndex(lang: Lang, n: Int): Int {
    val m10 = n % 10
    val m100 = n % 100
    return when (lang) {
        Lang.Fr, Lang.Qc, Lang.Oc, Lang.Miaou -> if (n <= 1) 0 else 1
        Lang.Ru, Lang.Uk -> when {
            m10 == 1 && m100 != 11 -> 0
            m10 in 2..4 && m100 !in 12..14 -> 1
            else -> 2
        }
        Lang.Pl -> when {
            n == 1 -> 0
            m10 in 2..4 && m100 !in 12..14 -> 1
            else -> 2
        }
        Lang.Ro -> when {
            n == 1 -> 0
            n == 0 || m100 in 1..19 -> 1
            else -> 2
        }
        Lang.Ar -> when {
            n == 1 -> 0
            n == 2 -> 1
            m100 in 3..10 -> 2
            else -> 3
        }
        Lang.Zh, Lang.Tr -> 0
        else -> if (n == 1) 0 else 1
    }
}

/** La langue choisie, pour toute l'app (l'ecran se redessine quand elle change). */
object I18n {
    var lang: Lang by mutableStateOf(Lang.Fr)

    private val all: Map<Lang, Lazy<Strings>> = Lang.entries.associateWith { l -> lazy { build(l) } }

    val strings: Strings get() = stringsFor(lang)

    fun stringsFor(l: Lang): Strings = all.getValue(l).value

    /**
     * Chaine de secours sans boucle : dialecte -> sa langue -> anglais -> francais (la reference,
     * qui n'a pas de secours).
     */
    private fun build(l: Lang): Strings {
        val fallback = when (l) {
            Lang.Fr -> null
            Lang.En -> Lang.Fr
            else -> l.fallback?.let(Lang::fromCode) ?: Lang.En
        }
        return Strings(l, parseTable(tableOf(l)), fallback?.let(::stringsFor))
    }
}

/** Le texte [key] dans la langue choisie ; {0}, {1}... remplaces par [args]. */
fun tr(key: Str, vararg args: Any?): String = I18n.strings.get(key, *args)

/** Le texte [key] accorde au nombre [n] (« 1 page », « 2 pages »). */
fun trn(key: Str, n: Int, vararg args: Any?): String = I18n.strings.plural(key, n, *args)

internal fun parseTable(text: String): Map<Str, String> {
    val byName = Str.entries.associateBy { it.name }
    val map = HashMap<Str, String>()
    for (line in text.lineSequence()) {
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#")) continue
        val eq = t.indexOf('=')
        if (eq <= 0) continue
        val key = byName[t.substring(0, eq).trim()] ?: continue
        map[key] = t.substring(eq + 1).trim().replace("\\n", "\n")
    }
    return map
}

/** Les cles presentes dans un tableau mais inconnues (fautes de frappe), pour les tests. */
internal fun unknownKeys(text: String): List<String> {
    val names = Str.entries.map { it.name }.toSet()
    return text.lineSequence().map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
        .map { it.substringBefore('=').trim() }
        .filter { it !in names }
        .toList()
}

internal fun tableOf(l: Lang): String = when (l) {
    Lang.Fr -> FR
    Lang.En -> EN
    Lang.De -> DE
    Lang.It -> IT
    Lang.Es -> ES
    Lang.Pt -> PT
    Lang.Nl -> NL
    Lang.Pl -> PL
    Lang.Tr -> TR
    Lang.Ru -> RU
    Lang.Uk -> UK
    Lang.Ar -> AR
    Lang.Zh -> ZH
    Lang.Sv -> SV
    Lang.El -> EL
    Lang.Ro -> RO
    Lang.Gsw -> GSW
    Lang.Rm -> RM
    Lang.Qc -> QC
    Lang.Als -> ALS
    Lang.Oc -> OC
    Lang.Br -> BR
    Lang.Co -> CO
    Lang.Miaou -> MIAOU
}
