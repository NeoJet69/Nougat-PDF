package app.nougat.pdf.engine

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Relecture des lignes douteuses par un modele qui « voit » l'image (Gemini Nano, sur Android).
 *
 * Tesseract donne la place exacte de chaque mot, mais se trompe sur les lignes abimees (tache,
 * pli, photo floue) et ne sait pas lire l'ecriture manuscrite. On lui laisse la mise en page et on
 * fait relire au modele, ligne par ligne, celles ou Tesseract doute : il recoit l'image de la ligne
 * et la lecture de Tesseract, et renvoie le texte corrige. Une reponse n'est gardee que si elle est
 * vraisemblable (proche de la lecture d'origine, ou de la bonne longueur pour une ligne illisible) :
 * un modele qui invente ne doit jamais remplacer une bonne lecture.
 */

/** Relit l'image d'une ligne ; [current] : ce que Tesseract y a lu. Null : pas de reponse. */
internal fun interface LineReader {
    fun read(line: GrayImage, current: String): String?
}

internal object OcrProofreading {
    /** Lignes relues au plus par page (chaque relecture prend environ une seconde). */
    const val MAX_LINES_PER_PAGE = 30

    /** Une ligne ou Tesseract doute : confiance moyenne basse, ou un mot tres incertain. */
    fun doubtful(line: OcrLine): Boolean {
        if (line.words.isEmpty() || line.right - line.left < 8) return false
        return line.conf < 85f || line.words.any { it.conf < 55f && it.text.any(Char::isLetterOrDigit) }
    }

    /**
     * Relit les lignes douteuses de [ocr] dans [image] (l'image droite qui a servi a la lecture).
     * Renvoie le resultat corrige et le nombre de lignes changees. S'arrete a [deadlineMs].
     */
    fun proofread(image: GrayImage, ocr: OcrResult, deadlineMs: Long = Long.MAX_VALUE, reader: LineReader): Pair<OcrResult, Int> {
        val order = ocr.lines.indices.filter { doubtful(ocr.lines[it]) }.sortedBy { ocr.lines[it].conf }.take(MAX_LINES_PER_PAGE)
        if (order.isEmpty()) return ocr to 0
        val lines = ocr.lines.toMutableList()
        var changed = 0
        for (i in order) {
            if (System.currentTimeMillis() > deadlineMs) break
            val line = lines[i]
            val current = line.text
            val answer = runCatching { reader.read(lineImage(image, line), current) }.getOrNull() ?: continue
            val text = accept(current, answer, line.conf, expectedChars(line)) ?: continue
            lines[i] = applyText(line, text)
            changed++
        }
        return OcrResult(ocr.width, ocr.height, lines) to changed
    }

    /** L'image de la ligne, avec une marge (les accents et les jambages depassent des mots). */
    fun lineImage(image: GrayImage, line: OcrLine): GrayImage {
        val h = max(4, line.bottom - line.top)
        val padX = (h * 0.6f).roundToInt()
        val padY = (h * 0.35f).roundToInt()
        return image.crop(line.left - padX, line.top - padY, line.right + padX, line.bottom + padY)
    }

    /** Nombre de lettres attendu sur la ligne, d'apres sa largeur et la taille des lettres. */
    fun expectedChars(line: OcrLine): Float = (line.right - line.left) / max(2f, estimateFontSizePx(line) * 0.5f)

    /** La reponse du modele, nettoyee : premiere ligne, sans guillemets ni prefixe. */
    fun clean(raw: String): String {
        var s = raw.trim().lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
        s = s.removePrefix("Text:").removePrefix("Texte :").removePrefix("Texte:").trim()
        val quotes = listOf("\"" to "\"", "«" to "»", "“" to "”", "'" to "'", "`" to "`")
        for ((a, b) in quotes) {
            if (s.length >= 2 && s.startsWith(a) && s.endsWith(b)) s = s.substring(a.length, s.length - b.length).trim()
        }
        s = s.removePrefix("«").removeSuffix("»").trim()
        return s.replace(Regex("\\s+"), " ")
    }

    /** Debuts de reponse qui ne sont pas une lecture (refus, commentaire sur l'image). */
    private val REFUSAL_STARTS = listOf(
        "sorry", "i'm sorry", "i can't", "i cannot", "i am unable", "unable to", "as an ai", "the image", "this image",
        "there is no", "no text", "désolé", "je suis désolé", "je ne peux", "je suis incapable", "l'image", "cette image",
        "il n'y a pas", "aucun texte",
    )

    /**
     * La correction a garder, ou null. [lineConf] : confiance de Tesseract sur la ligne ;
     * [expected] : nombre de lettres attendu d'apres la largeur de la ligne.
     */
    fun accept(current: String, raw: String, lineConf: Float, expected: Float): String? {
        val text = clean(raw)
        if (text.isEmpty() || text.length > 400 || text == current) return null
        val lower = text.lowercase()
        if (REFUSAL_STARTS.any { lower.startsWith(it) } || "as an ai" in lower) return null
        if (text.count(Char::isLetterOrDigit) < 1) return null
        val ratio = text.length.toFloat() / max(1, current.length)
        return if (lineConf >= 45f) {
            // Tesseract a lu quelque chose de sense : on n'accepte qu'une correction, pas une reecriture.
            if (ratio !in 0.7f..1.4f) return null
            if (similarity(current, text) < 0.55) return null
            text
        } else {
            // Ligne presque illisible pour Tesseract (manuscrit, tache) : il faut au moins la bonne longueur.
            val r = text.length / max(1f, expected)
            if (r !in 0.35f..2.6f) return null
            text
        }
    }

    /** Ressemblance de 0 (rien a voir) a 1 (identiques), sans tenir compte des majuscules. */
    fun similarity(a: String, b: String): Double {
        val x = a.lowercase()
        val y = b.lowercase()
        val n = max(x.length, y.length)
        if (n == 0) return 1.0
        return 1.0 - levenshtein(x, y).toDouble() / n
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[b.length]
    }

    /**
     * La ligne avec le texte corrige. Meme nombre de mots : chacun garde sa place. Sinon, les mots
     * sont repartis sur la largeur de la ligne selon leur longueur (la ligne de base ne change pas).
     */
    fun applyText(line: OcrLine, text: String): OcrLine {
        val newWords = text.split(' ').filter { it.isNotEmpty() }
        val old = line.words.sortedBy { it.left }
        val words = if (newWords.size == old.size) {
            old.mapIndexed { i, w -> OcrWord(newWords[i], 95f, w.left, w.top, w.right, w.bottom) }
        } else {
            val left = line.left
            val width = (line.right - left).toFloat()
            val total = newWords.sumOf { it.length } + (newWords.size - 1).coerceAtLeast(0)
            var at = 0
            newWords.map { word ->
                val l = left + (at * width / total).roundToInt()
                val r = left + ((at + word.length) * width / total).roundToInt()
                at += word.length + 1
                OcrWord(word, 95f, l, line.top, max(l + 1, r), line.bottom)
            }
        }
        return OcrLine(words, line.x1, line.y1, line.x2, line.y2)
    }
}
