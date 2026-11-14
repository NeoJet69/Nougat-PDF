package app.nougat.pdf.engine

import kotlin.math.abs
import kotlin.math.max

/*
 * Deplacer et agrandir des lignes choisies (mode Editer) : l'encre de chaque ligne est decoupee sur
 * le rendu de la page, la ligne est effacee (texte retire, pixels du scan repeints), puis l'encre est
 * recollee a la nouvelle place, avec le texte invisible pour rester cherchable. Commun aux deux
 * plateformes ; le rendu et l'ecriture du PDF sont propres a chacune.
 */
internal object BlockMove {
    /** Une ligne : son cadre d'origine (avec une petite marge) et sa nouvelle place. */
    class Target(val block: TextBlock, val from: NormRect, val to: NormRect)

    /**
     * Les nouvelles places : decalage [dx], [dy] (fractions de la page) et taille [scale], autour du
     * coin haut gauche de la selection pour garder la mise en page des lignes entre elles.
     */
    fun targets(blocks: List<TextBlock>, dx: Float, dy: Float, scale: Float, pageWidth: Float, pageHeight: Float): List<Target> {
        if (blocks.isEmpty()) return emptyList()
        val padded = blocks.map { b ->
            val padX = b.fontSize * 0.15f / pageWidth
            val padTop = b.fontSize * 0.32f / pageHeight
            val padBottom = b.fontSize * 0.15f / pageHeight
            b to NormRect(
                (b.rect.left - padX).coerceAtLeast(0f), (b.rect.top - padTop).coerceAtLeast(0f),
                (b.rect.right + padX).coerceAtMost(1f), (b.rect.bottom + padBottom).coerceAtMost(1f),
            )
        }
        val ox = padded.minOf { it.second.left }
        val oy = padded.minOf { it.second.top }
        return padded.map { (b, r) ->
            Target(
                b, r,
                NormRect(ox + (r.left - ox) * scale + dx, oy + (r.top - oy) * scale + dy, ox + (r.right - ox) * scale + dx, oy + (r.bottom - oy) * scale + dy),
            )
        }
    }

    /** Les pixels du cadre [r] d'une image [iw] x [ih] ; largeur, hauteur et pixels ARGB. */
    fun crop(pixels: IntArray, iw: Int, ih: Int, r: NormRect): Triple<Int, Int, IntArray> {
        val x0 = (r.left * iw).toInt().coerceIn(0, iw - 1)
        val y0 = (r.top * ih).toInt().coerceIn(0, ih - 1)
        val x1 = (r.right * iw).toInt().coerceIn(x0 + 1, iw)
        val y1 = (r.bottom * ih).toInt().coerceIn(y0 + 1, ih)
        val w = x1 - x0
        val h = y1 - y0
        val out = IntArray(w * h)
        for (y in 0 until h) System.arraycopy(pixels, (y0 + y) * iw + x0, out, y * w, w)
        return Triple(w, h, out)
    }

    /**
     * Garde seulement l'encre : le papier (la couleur du bord du morceau) devient transparent, ce qui
     * est loin du papier reste opaque. Ainsi la ligne recollee ne fait pas de cadre sur un fond colore.
     */
    fun inkOnly(pixels: IntArray, w: Int, h: Int): IntArray {
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0
        fun add(c: Int) {
            r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
        }
        for (x in 0 until w) { add(pixels[x]); add(pixels[(h - 1) * w + x]) }
        for (y in 0 until h) { add(pixels[y * w]); add(pixels[y * w + w - 1]) }
        val pr = (r / n).toInt()
        val pg = (g / n).toInt()
        val pb = (b / n).toInt()
        return IntArray(pixels.size) { i ->
            val c = pixels[i]
            val d = max(abs(((c shr 16) and 0xFF) - pr), max(abs(((c shr 8) and 0xFF) - pg), abs((c and 0xFF) - pb)))
            val alpha = ((d - 24) * 255 / 70).coerceIn(0, 255)
            (alpha shl 24) or (c and 0xFFFFFF)
        }
    }
}
