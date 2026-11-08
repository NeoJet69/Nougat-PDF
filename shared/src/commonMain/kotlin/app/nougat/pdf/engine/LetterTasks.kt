package app.nougat.pdf.engine

/*
 * « Ce que je dois faire » : le resume simple d'une lettre et ce qu'il faut faire, avec les dates.
 * L'IA (Gemini Nano sur le telephone, ou Claude) repond dans un format de lignes tres simple, lu
 * ici ; sans IA, on reprend les dates du texte et la phrase qui les entoure.
 */

/** Une chose a faire ; [date] au format AAAA-MM-JJ, ou null. */
data class TodoItem(val what: String, val date: String?)

/** Le resume ([summary] vide sans IA) et les choses a faire. */
data class LetterDigest(val summary: String, val tasks: List<TodoItem>, val fromAi: Boolean)

object LetterTasks {
    /** Ce qu'on envoie a l'IA, au plus. */
    const val MAX_TEXT = 12_000

    /** La consigne pour l'IA : reponse en [languageName], aujourd'hui = [todayIso]. */
    fun instructions(languageName: String, todayIso: String): String =
        "Read the letter or document below and tell a busy person, in very simple words, what it is about and what they must do. " +
            "Answer in $languageName. Use exactly this format, nothing else:\n" +
            "SUMMARY: two or three short sentences.\n" +
            "TASK: YYYY-MM-DD | what to do\n" +
            "TASK: - | what to do (when there is no date)\n" +
            "Only list real actions for the reader (pay, reply, send a document, go to an appointment, sign...), " +
            "most urgent first, at most 6. Write dates as YYYY-MM-DD, taken from the document" +
            (if (todayIso.isNotBlank()) " (today is $todayIso, use it for delays like \"within 30 days\")" else "") +
            ". If there is nothing to do, write TASK: NONE."

    /** Lit la reponse de l'IA ; null si elle ne suit pas le format. */
    fun parseAnswer(answer: String): LetterDigest? {
        val summary = StringBuilder()
        val tasks = ArrayList<TodoItem>()
        var inSummary = false
        var sawFormat = false
        for (raw in answer.lines()) {
            val line = raw.trim().removePrefix("-").removePrefix("*").trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("SUMMARY:", ignoreCase = true) -> {
                    sawFormat = true
                    inSummary = true
                    summary.append(line.substringAfter(':').trim())
                }
                line.startsWith("TASK:", ignoreCase = true) -> {
                    sawFormat = true
                    inSummary = false
                    val body = line.substringAfter(':').trim()
                    if (body.equals("NONE", ignoreCase = true) || body.isEmpty()) continue
                    val date = body.substringBefore('|', "").trim()
                    val what = if ('|' in body) body.substringAfter('|').trim() else body
                    if (what.isNotEmpty()) tasks += TodoItem(what, date.takeIf { isIsoDate(it) })
                }
                inSummary -> summary.append(' ').append(line)
            }
        }
        if (!sawFormat) return null
        return LetterDigest(summary.toString().trim(), tasks.take(8), fromAi = true)
    }

    fun isIsoDate(s: String): Boolean {
        val m = Regex("""^(\d{4})-(\d{2})-(\d{2})$""").matchEntire(s) ?: return false
        val (y, mo, d) = m.destructured
        return validDate(y.toInt(), mo.toInt(), d.toInt())
    }

    private fun validDate(y: Int, m: Int, d: Int): Boolean {
        if (y !in 1990..2100 || m !in 1..12 || d < 1) return false
        return d <= daysIn(y, m)
    }

    private fun daysIn(y: Int, m: Int) = when (m) {
        2 -> if ((y % 4 == 0 && y % 100 != 0) || y % 400 == 0) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    /** Le jour suivant (pour la fin d'un evenement d'une journee). */
    fun nextDay(iso: String): String {
        val (y, m, d) = iso.split('-').map { it.toInt() }
        var yy = y
        var mm = m
        var dd = d + 1
        if (dd > daysIn(yy, mm)) {
            dd = 1
            mm++
            if (mm > 12) {
                mm = 1
                yy++
            }
        }
        return "$yy-${mm.toString().padStart(2, '0')}-${dd.toString().padStart(2, '0')}"
    }

    private val MONTHS: Map<String, Int> = buildMap {
        val names = listOf(
            listOf("janvier", "janv", "january", "jan", "januar", "jänner", "gennaio", "genn", "enero", "janeiro"),
            listOf("février", "fevrier", "févr", "fevr", "february", "feb", "februar", "febbraio", "febrero", "fevereiro"),
            listOf("mars", "march", "mar", "märz", "marz", "marzo", "março"),
            listOf("avril", "avr", "april", "apr", "aprile", "abril"),
            listOf("mai", "may", "maggio", "mayo", "maio"),
            listOf("juin", "june", "jun", "juni", "giugno", "junio", "junho"),
            listOf("juillet", "juil", "july", "jul", "juli", "luglio", "julio", "julho"),
            listOf("août", "aout", "august", "aug", "agosto"),
            listOf("septembre", "sept", "september", "sep", "settembre", "septiembre", "setembro"),
            listOf("octobre", "oct", "october", "oktober", "ottobre", "octubre", "outubro"),
            listOf("novembre", "nov", "november", "noviembre", "novembro"),
            listOf("décembre", "decembre", "déc", "dec", "december", "dezember", "dicembre", "diciembre", "dezembro"),
        )
        names.forEachIndexed { i, list -> list.forEach { put(it, i + 1) } }
    }

    private val NUMERIC = Regex("""(?<!\d)(\d{1,2})[./](\d{1,2})[./](\d{4}|\d{2})(?!\d)""")
    private val ISO = Regex("""(?<!\d)(\d{4})-(\d{2})-(\d{2})(?!\d)""")
    private val WRITTEN = Regex("""(?i)(?<![\p{L}\d])(\d{1,2})(?:er|\.)?\s+(\p{L}{3,10})\.?\s+(\d{4})(?!\d)""")
    private val ENGLISH = Regex("""(?i)(?<![\p{L}\d])(\p{L}{3,10})\.?\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{4})(?!\d)""")

    /** Les dates ecrites dans [text] (31.12.2026, 31/12/26, 2026-12-31, 31 décembre 2026, December 31, 2026). */
    fun findDates(text: String): List<Pair<IntRange, String>> {
        val out = ArrayList<Pair<IntRange, String>>()
        fun add(range: IntRange, y: Int, m: Int, d: Int) {
            val year = if (y < 100) 2000 + y else y
            if (validDate(year, m, d) && out.none { it.first.first <= range.last && range.first <= it.first.last }) {
                out += range to "$year-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
            }
        }
        for (r in ISO.findAll(text)) add(r.range, r.groupValues[1].toInt(), r.groupValues[2].toInt(), r.groupValues[3].toInt())
        for (r in NUMERIC.findAll(text)) add(r.range, r.groupValues[3].toInt(), r.groupValues[2].toInt(), r.groupValues[1].toInt())
        for (r in WRITTEN.findAll(text)) {
            val month = MONTHS[r.groupValues[2].lowercase()] ?: continue
            add(r.range, r.groupValues[3].toInt(), month, r.groupValues[1].toInt())
        }
        for (r in ENGLISH.findAll(text)) {
            val month = MONTHS[r.groupValues[1].lowercase()] ?: continue
            add(r.range, r.groupValues[3].toInt(), month, r.groupValues[2].toInt())
        }
        return out.sortedBy { it.first.first }
    }

    private val DEADLINE = Regex(
        """(?i)(?<!\p{L})(délai|delai|avant le|au plus tard|jusqu'au|jusqu’au|échéance|echeance|payable|à payer|a payer|à retourner|rendez-vous|""" +
            """convocation|frist|bis zum|bis am|spätestens|fällig|termin|zahlbar|entro|scadenza|pagabile|appuntamento|""" +
            """deadline|due|by|no later|appointment|plazo|antes del|vencimiento|prazo|até)(?!\p{L})""",
    )

    /**
     * Sans IA : chaque date (aujourd'hui ou plus tard si [todayIso] est connu) avec la ligne qui
     * l'entoure. Les lignes qui parlent d'un delai (« avant le », « Frist », « entro »...) passent
     * seules si elles existent.
     */
    fun findDeadlines(text: String, todayIso: String): List<TodoItem> {
        val lines = text.lines()
        val found = ArrayList<Pair<TodoItem, Boolean>>()
        for (line in lines) {
            val clean = line.trim()
            if (clean.isEmpty()) continue
            for ((_, iso) in findDates(clean)) {
                if (todayIso.isNotBlank() && iso < todayIso) continue
                val what = if (clean.length > 140) clean.take(137).trimEnd() + "…" else clean
                if (found.none { it.first.date == iso && it.first.what == what }) {
                    found += TodoItem(what, iso) to DEADLINE.containsMatchIn(clean)
                }
            }
        }
        val urgent = found.filter { it.second }
        return (urgent.ifEmpty { found }).map { it.first }.sortedBy { it.date }.take(8)
    }
}
