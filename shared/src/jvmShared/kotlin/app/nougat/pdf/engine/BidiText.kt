package app.nougat.pdf.engine

import java.text.Bidi

/*
 * Ordre d'affichage d'une ligne qui melange l'arabe (de droite a gauche) et les chiffres ou les
 * mots latins (de gauche a droite). Un PDF ecrit toujours de gauche a droite : on lui donne les
 * lettres dans l'ordre ou elles apparaissent a l'ecran, apres les avoir liees (ArabicShaping).
 */
internal object BidiText {
    private val MIRROR = mapOf('(' to ')', ')' to '(', '[' to ']', ']' to '[', '{' to '}', '}' to '{', '<' to '>', '>' to '<', '«' to '»', '»' to '«')

    /** [text] (ordre logique) dans l'ordre visuel, pour une ligne de base [rtl] ou non. */
    fun visual(text: String, rtl: Boolean): String {
        val shaped = ArabicShaping.shape(text)
        if (!rtl && !Bidi.requiresBidi(shaped.toCharArray(), 0, shaped.length)) return shaped
        val bidi = Bidi(shaped, if (rtl) Bidi.DIRECTION_RIGHT_TO_LEFT else Bidi.DIRECTION_LEFT_TO_RIGHT)
        if (bidi.isLeftToRight) return shaped
        val n = bidi.runCount
        val levels = ByteArray(n) { bidi.getRunLevel(it).toByte() }
        val runs = Array<Any>(n) { i -> Run(shaped.substring(bidi.getRunStart(i), bidi.getRunLimit(i)), bidi.getRunLevel(i)) }
        Bidi.reorderVisually(levels, 0, runs, 0, n)
        return buildString {
            for (r in runs) {
                val run = r as Run
                if (run.level % 2 == 1) {
                    for (c in StringBuilder(run.text).reverse()) append(MIRROR[c] ?: c)
                } else {
                    append(run.text)
                }
            }
        }
    }

    private class Run(val text: String, val level: Int)
}
