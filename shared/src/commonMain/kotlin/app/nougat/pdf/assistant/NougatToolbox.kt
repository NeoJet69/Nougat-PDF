package app.nougat.pdf.assistant

import app.nougat.pdf.engine.BoxMark
import app.nougat.pdf.engine.FIELD_OFF
import app.nougat.pdf.engine.FieldKind
import app.nougat.pdf.engine.InkMark
import app.nougat.pdf.engine.NPoint
import app.nougat.pdf.engine.NormRect
import app.nougat.pdf.engine.TextBlock
import app.nougat.pdf.engine.TextMark
import app.nougat.pdf.engine.TextSearch
import app.nougat.pdf.viewer.DocumentSession
import app.nougat.pdf.viewer.InkColors
import app.nougat.pdf.viewer.ViewerState
import kotlinx.coroutines.CancellationException
import kotlin.math.roundToInt
import app.nougat.pdf.i18n.Str
import app.nougat.pdf.i18n.tr

/** Les outils de Claude sur le document ouvert dans [state]. Pages numerotees a partir de 1. */
class NougatToolbox(private val state: ViewerState) : AssistantToolbox {

    override val systemPrompt: String = """
Tu es l'assistant intégré à Nougat PDF, un éditeur de PDF pour Windows et Android.
Tu aides l'utilisateur à lire, comprendre et modifier le document ouvert. Réponds dans la langue de l'utilisateur (le français par défaut), brièvement et simplement : l'utilisateur n'est pas un informaticien.

Ce que tu peux faire avec les outils :
- get_document_info, read_page, view_page, search_text : lire le document. read_page donne les lignes de texte d'une page avec leur numéro (line), leur position (en % de la page) et leur taille. view_page montre l'image de la page : utilise-la pour les documents scannés, pour juger de la mise en page, des couleurs ou du style des polices.
- replace_text : remplacer le texte d'une ligne existante. Le texte est réécrit au même endroit, dans la même police, la même taille et la même couleur. C'est l'outil à préférer pour corriger, traduire ou changer un texte.
- add_text, cover_area, highlight_text : ajouter du texte, masquer une zone (rectangle blanc), surligner une ligne.
- rotate_pages, delete_pages, move_page, insert_blank_page, go_to_page : organiser les pages et naviguer.
- recognize_text : reconnaitre le texte des pages scannees (OCR, sans Internet). Si read_page ne trouve pas de texte sur une page qui en montre (scan, photo), lance recognize_text, puis relis la page : les lignes deviennent lisibles ET modifiables avec replace_text (Nougat efface l'image de la ligne et la reecrit dans la police la plus ressemblante).
- list_form_fields, fill_form_field : remplir un formulaire PDF (champs à remplir, cases à cocher, listes). Pour un formulaire, commence toujours par list_form_fields ; si le document n'a pas de champs, écris avec add_text aux bons endroits.

Règles :
- Les pages sont numérotées à partir de 1. Les numéros de ligne viennent de read_page : après avoir modifié une page, relis-la avant de la modifier à nouveau (les numéros peuvent changer).
- Une ligne de read_page est un morceau de texte d'un seul style. Il n'y a pas de retour à la ligne automatique : le nouveau texte ne doit pas être beaucoup plus long que l'ancien, sinon il déborde. Pour un texte long, répartis-le sur plusieurs lignes existantes ou préviens l'utilisateur.
- Si une ligne n'est pas modifiable (texte scanné, dans une image ou un formulaire), lis-la avec view_page, puis masque-la avec cover_area et réécris-la avec add_text, en estimant la position et la taille.
- replace_text et les opérations sur les pages modifient le document tout de suite ; add_text, cover_area et highlight_text sont des annotations, gravées à l'enregistrement. L'utilisateur peut tout annuler avec le bouton « Annuler ». Il enregistre lui-même le fichier.
- Ne supprime pas de pages et ne fais pas de modification massive si l'utilisateur ne l'a pas clairement demandé. En cas de doute, demande.
- Quand tu as fini, dis en une ou deux phrases ce que tu as fait.
""".trim()

    override val specs: List<ToolSpec> = listOf(
        ToolSpec("get_document_info", "Nom du document, nombre de pages, page affichée, modifications non enregistrées.", emptyMap(), emptyList()),
        ToolSpec(
            "read_page",
            "Lit les lignes de texte d'une page : numéro de ligne, position (% de la largeur et de la hauteur, depuis le haut à gauche), taille en points, si elle est modifiable, et le texte.",
            mapOf("page" to int("Numéro de page (1 = première)")),
            listOf("page"),
        ),
        ToolSpec(
            "view_page",
            "Renvoie l'image de la page (pour lire un scan, voir la mise en page, les couleurs, le style des polices).",
            mapOf("page" to int("Numéro de page (1 = première)")),
            listOf("page"),
        ),
        ToolSpec(
            "search_text",
            "Cherche un texte dans tout le document (sans tenir compte des majuscules ni des accents). Renvoie les pages et les lignes trouvées.",
            mapOf("query" to str("Texte à chercher")),
            listOf("query"),
        ),
        ToolSpec(
            "replace_text",
            "Remplace le texte d'une ligne existante (lue avec read_page), au même endroit et dans le même style. Mettre new_text vide pour effacer la ligne.",
            mapOf(
                "page" to int("Numéro de page"),
                "line" to int("Numéro de ligne donné par read_page"),
                "old_text" to str("Texte actuel de la ligne (pour vérifier qu'il s'agit de la bonne)"),
                "new_text" to str("Nouveau texte de la ligne"),
            ),
            listOf("page", "line", "old_text", "new_text"),
        ),
        ToolSpec(
            "add_text",
            "Ajoute du texte sur une page, à une position donnée (coin haut gauche, en % de la page).",
            mapOf(
                "page" to int("Numéro de page"),
                "x" to num("Position horizontale du coin haut gauche, en % de la largeur (0 à 100)"),
                "y" to num("Position verticale du coin haut gauche, en % de la hauteur (0 à 100)"),
                "text" to str("Texte (\\n pour aller à la ligne)"),
                "size" to num("Taille en points (par défaut 12)"),
                "color" to str("Couleur : noir, bleu, rouge, vert, orange, blanc (par défaut noir)"),
            ),
            listOf("page", "x", "y", "text"),
        ),
        ToolSpec(
            "cover_area",
            "Masque une zone de la page avec un rectangle blanc (en % de la page). Utile avant add_text pour remplacer un texte non modifiable.",
            mapOf(
                "page" to int("Numéro de page"),
                "x" to num("Gauche, en % de la largeur"),
                "y" to num("Haut, en % de la hauteur"),
                "width" to num("Largeur, en % de la largeur"),
                "height" to num("Hauteur, en % de la hauteur"),
            ),
            listOf("page", "x", "y", "width", "height"),
        ),
        ToolSpec(
            "highlight_text",
            "Surligne une ligne de texte (en jaune).",
            mapOf("page" to int("Numéro de page"), "line" to int("Numéro de ligne donné par read_page")),
            listOf("page", "line"),
        ),
        ToolSpec(
            "rotate_pages",
            "Fait pivoter des pages dans le sens des aiguilles d'une montre.",
            mapOf("pages" to intList("Numéros des pages"), "degrees" to int("90, 180 ou 270")),
            listOf("pages", "degrees"),
        ),
        ToolSpec(
            "delete_pages",
            "Supprime des pages. Seulement si l'utilisateur l'a demandé.",
            mapOf("pages" to intList("Numéros des pages à supprimer")),
            listOf("pages"),
        ),
        ToolSpec(
            "move_page",
            "Déplace une page à une nouvelle position.",
            mapOf("from" to int("Numéro actuel de la page"), "to" to int("Nouvelle position (1 = en premier)")),
            listOf("from", "to"),
        ),
        ToolSpec(
            "insert_blank_page",
            "Insère une page blanche après la page indiquée (0 = au début).",
            mapOf("after" to int("Numéro de la page après laquelle insérer")),
            listOf("after"),
        ),
        ToolSpec(
            "go_to_page",
            "Affiche une page à l'écran pour l'utilisateur.",
            mapOf("page" to int("Numéro de page")),
            listOf("page"),
        ),
        ToolSpec(
            "recognize_text",
            "Reconnait le texte des pages scannees (OCR) et l'ajoute au document, invisible sur l'image. Ensuite read_page, search_text et replace_text fonctionnent sur ces pages. Sans « page », tout le document.",
            mapOf("page" to int("Numéro de page (1 = première) ; absent = tout le document")),
            emptyList(),
        ),
        ToolSpec(
            "list_form_fields",
            "Liste les champs du formulaire PDF : nom (name, à passer à fill_form_field), libellé, page, type, valeur actuelle et choix possibles.",
            emptyMap(),
            emptyList(),
        ),
        ToolSpec(
            "fill_form_field",
            "Remplit un champ du formulaire. Texte : le texte. Case à cocher : « oui » ou « non ». Boutons radio ou liste : un des choix donnés par list_form_fields.",
            mapOf("name" to str("Nom du champ (name), donné par list_form_fields"), "value" to str("Valeur")),
            listOf("name", "value"),
        ),
    )

    override fun label(name: String, input: Map<String, Any?>): String {
        val p = input["page"]?.let { intOf(it) }
        return when (name) {
            "get_document_info" -> tr(Str.ActLookDocument)
            "read_page" -> tr(Str.ActReadPage, p)
            "view_page" -> tr(Str.ActViewPage, p)
            "search_text" -> tr(Str.ActSearch, input["query"])
            "replace_text" -> tr(Str.ActReplace, short(input["old_text"]), short(input["new_text"]), p)
            "add_text" -> tr(Str.ActAddText, short(input["text"]), p)
            "cover_area" -> tr(Str.ActCover, p)
            "highlight_text" -> tr(Str.ActHighlight, p)
            "rotate_pages" -> tr(Str.ActRotate)
            "delete_pages" -> tr(Str.ActDelete)
            "move_page" -> tr(Str.ActMove)
            "insert_blank_page" -> tr(Str.ActInsertBlank)
            "go_to_page" -> tr(Str.ActGoTo, p)
            "recognize_text" -> tr(Str.ActRecognize)
            "list_form_fields" -> tr(Str.ActListFields)
            "fill_form_field" -> tr(Str.ActFillField, short(input["name"]), short(input["value"]))
            else -> name
        }
    }

    override suspend fun run(name: String, input: Map<String, Any?>): ToolOutput = try {
        val s = state.session ?: throw ToolError("Aucun document n'est ouvert.")
        when (name) {
            "get_document_info" -> info(s)
            "read_page" -> readPage(s, page(s, input))
            "view_page" -> viewPage(s, page(s, input))
            "search_text" -> search(s, input.string("query"))
            "replace_text" -> replaceText(s, page(s, input), input.int("line"), input.string("old_text"), input.string("new_text", allowEmpty = true))
            "add_text" -> addText(s, page(s, input), input)
            "cover_area" -> coverArea(s, page(s, input), input)
            "highlight_text" -> highlight(s, page(s, input), input.int("line"))
            "rotate_pages" -> rotate(s, input)
            "delete_pages" -> deletePages(s, input)
            "move_page" -> movePage(s, input)
            "insert_blank_page" -> insertBlank(s, input)
            "go_to_page" -> {
                val p = page(s, input)
                s.goToPage(p)
                ToolOutput.Text("Page ${p + 1} affichée.")
            }
            "recognize_text" -> recognizeText(s, input)
            "list_form_fields" -> listFormFields(s)
            "fill_form_field" -> fillFormField(s, input.string("name"), input.string("value", allowEmpty = true))
            else -> throw ToolError("Outil inconnu : $name")
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: ToolError) {
        ToolOutput.Text(e.message ?: "Erreur", isError = true)
    } catch (e: Throwable) {
        ToolOutput.Text("Erreur : ${e.message ?: e::class.simpleName}", isError = true)
    }

    // ---- Reconnaissance de texte -------------------------------------------------------------------

    private suspend fun recognizeText(s: DocumentSession, input: Map<String, Any?>): ToolOutput {
        if (!state.canOcr) throw ToolError("La reconnaissance de texte n'est pas disponible sur cet appareil.")
        val pages = if (input["page"] != null) listOf(page(s, input)) else null
        val summary = state.recognizeTextAwait(pages)
            ?: return ToolOutput.Text("Rien à reconnaître : ces pages contiennent déjà du texte (ou ont déjà été reconnues).")
        return ToolOutput.Text(
            "Texte reconnu sur ${summary.recognized} page(s), ${summary.words} mots" +
                (if (summary.rotated > 0) ", ${summary.rotated} page(s) remise(s) droite(s)" else "") +
                (if (summary.skipped > 0) ", ${summary.skipped} page(s) laissée(s) (déjà du texte)" else "") +
                ". Relis les pages avec read_page.",
        )
    }

    // ---- Formulaires ----------------------------------------------------------------------------

    private suspend fun listFormFields(s: DocumentSession): ToolOutput {
        val all = s.allFormFields()
        if (all.isEmpty()) {
            return ToolOutput.Text("Ce document n'a pas de champs de formulaire. Pour remplir un formulaire « à plat », écris avec add_text aux bons endroits (regarde la page avec view_page).")
        }
        return ToolOutput.Text(
            buildString {
                for ((name, group) in all.groupBy { it.name }) {
                    val f = group.first()
                    append("- name=\"").append(name).append('"')
                    if (f.label != name) append(" (libellé : ").append(f.label).append(')')
                    append(", page ").append(f.page + 1).append(", ")
                    when (f.kind) {
                        FieldKind.Text -> {
                            append(if (f.multiline) "texte sur plusieurs lignes" else "texte")
                            if (f.maxLength > 0) append(" (").append(f.maxLength).append(" caractères max)")
                            append(", valeur : « ").append(f.value).append(" »")
                        }
                        FieldKind.CheckBox -> append("case à cocher, ").append(if (group.any { it.checked }) "cochée" else "non cochée")
                        FieldKind.Radio -> {
                            append("boutons radio, choix : ").append(group.mapNotNull { it.onValue }.distinct().joinToString())
                            append(", choisi : ").append(group.firstOrNull { it.checked }?.onValue ?: "aucun")
                        }
                        FieldKind.Choice -> {
                            append("liste, choix : ")
                            append(f.options.zip(f.optionValues).joinToString { (shown, stored) -> if (shown == stored) shown else "$shown [$stored]" })
                            append(", valeur : « ").append(f.displayValue).append(" »")
                        }
                    }
                    if (f.readOnly) append(" (lecture seule)")
                    appendLine()
                }
            }.trim(),
        )
    }

    private suspend fun fillFormField(s: DocumentSession, name: String, raw: String): ToolOutput {
        val group = s.allFormFields().filter { it.name == name }
        val f = group.firstOrNull() ?: throw ToolError("Champ introuvable : « $name ». Utilise list_form_fields pour avoir les noms exacts.")
        if (f.readOnly) throw ToolError("Le champ « ${f.label} » est en lecture seule.")
        val wanted = raw.trim()
        val value = when (f.kind) {
            FieldKind.Text -> raw
            FieldKind.CheckBox -> {
                val yes = wanted.lowercase() in setOf("oui", "yes", "true", "1", "x", "on", "coché", "cochée", "cocher") || wanted == f.onValue
                if (yes) f.onValue ?: "Yes" else FIELD_OFF
            }
            FieldKind.Radio -> group.mapNotNull { it.onValue }.firstOrNull { it.equals(wanted, ignoreCase = true) }
                ?: throw ToolError("Choix inconnu pour « ${f.label} ». Choix possibles : ${group.mapNotNull { it.onValue }.distinct().joinToString()}")
            FieldKind.Choice -> {
                val i = f.options.indexOfFirst { it.equals(wanted, ignoreCase = true) }
                    .takeIf { it >= 0 } ?: f.optionValues.indexOfFirst { it.equals(wanted, ignoreCase = true) }
                if (i < 0) throw ToolError("Choix inconnu pour « ${f.label} ». Choix possibles : ${f.options.joinToString()}")
                f.optionValues[i]
            }
        }
        state.setFieldValueAwait(name, value)
        return ToolOutput.Text("Champ « ${f.label} » rempli.")
    }

    // ---- Lecture -------------------------------------------------------------------------------

    private fun info(s: DocumentSession) = ToolOutput.Text(
        buildString {
            appendLine("Nom : ${s.name}")
            appendLine("Pages : ${s.pageCount}")
            appendLine("Page affichée : ${s.visiblePage + 1}")
            appendLine("Modifications non enregistrées : ${if (state.modified) "oui" else "non"}")
            appendLine("Protégé par mot de passe : ${if (s.password != null) "oui" else "non"}")
            val size = s.pageSizes.firstOrNull()
            if (size != null) append("Taille de la page 1 : ${size.width.roundToInt()} × ${size.height.roundToInt()} points")
        },
    )

    private suspend fun readPage(s: DocumentSession, page: Int): ToolOutput {
        val blocks = s.textBlocks(page)
        if (blocks.isEmpty()) {
            return ToolOutput.Text("La page ${page + 1} ne contient pas de texte lisible (page vide, ou scannée : utilise view_page).")
        }
        val text = buildString {
            appendLine("Page ${page + 1}, ${blocks.size} lignes :")
            blocks.take(MAX_LINES).forEachIndexed { i, b -> appendLine(describe(i, b)) }
            if (blocks.size > MAX_LINES) appendLine("… (${blocks.size - MAX_LINES} lignes de plus non affichées)")
        }
        return ToolOutput.Text(text.trimEnd())
    }

    private fun describe(i: Int, b: TextBlock): String {
        val x = (b.rect.left * 100).roundToInt()
        val y = (b.rect.top * 100).roundToInt()
        val flag = if (b.editable) "modifiable" else "NON modifiable"
        return "ligne $i · x $x% y $y% · ${b.fontSize.roundToInt()} pt · $flag · ${b.text}"
    }

    private suspend fun viewPage(s: DocumentSession, page: Int): ToolOutput {
        val jpeg = s.document.renderPageJpeg(page, VIEW_WIDTH)
        return ToolOutput.Image(jpeg, "Page ${page + 1} sur ${s.pageCount}")
    }

    private suspend fun search(s: DocumentSession, query: String): ToolOutput {
        val results = ArrayList<String>()
        for (page in 0 until s.pageCount) {
            val hits = TextSearch.find(page, s.document.pageText(page), query)
            if (hits.isEmpty()) continue
            val blocks = s.textBlocks(page)
            for (hit in hits) {
                val r = hit.rects.firstOrNull() ?: continue
                val index = blocks.indexOfFirst { contains(it.rect, r) }
                results += if (index >= 0) "page ${page + 1}, ligne $index : ${blocks[index].text}" else "page ${page + 1}"
                if (results.size >= MAX_HITS) break
            }
            if (results.size >= MAX_HITS) break
        }
        return ToolOutput.Text(
            if (results.isEmpty()) "Aucun résultat pour « $query »."
            else "${results.size} résultat(s) pour « $query » :\n" + results.distinct().joinToString("\n"),
        )
    }

    // ---- Modification ---------------------------------------------------------------------------

    private suspend fun replaceText(s: DocumentSession, page: Int, line: Int, oldText: String, newText: String): ToolOutput {
        val blocks = s.textBlocks(page)
        val wanted = normalize(oldText)
        val block = blocks.getOrNull(line)?.takeIf { normalize(it.text) == wanted }
            ?: blocks.firstOrNull { normalize(it.text) == wanted }
            ?: blocks.firstOrNull { wanted.isNotEmpty() && wanted in normalize(it.text) }
            ?: throw ToolError("Ligne introuvable sur la page ${page + 1} : relis la page avec read_page.")
        if (!block.editable) {
            throw ToolError("Cette ligne n'est pas modifiable directement. Utilise cover_area puis add_text.")
        }
        val result = state.replaceTextAwait(block, newText)
        val font = when (result) {
            app.nougat.pdf.engine.TextEditResult.SameFont -> "police d'origine"
            app.nougat.pdf.engine.TextEditResult.InstalledFont -> "police d'origine installée sur l'ordinateur"
            app.nougat.pdf.engine.TextEditResult.SimilarFont -> "police standard proche (l'originale n'a pas ces caractères)"
            app.nougat.pdf.engine.TextEditResult.ScanText -> "ligne scannée effacée et réécrite dans la police la plus ressemblante"
        }
        return ToolOutput.Text("Fait ($font). Les numéros de ligne de la page ${page + 1} ont pu changer.")
    }

    private fun addText(s: DocumentSession, page: Int, input: Map<String, Any?>): ToolOutput {
        val size = s.pageSizes[page]
        val text = input.string("text")
        val fontSize = (input["size"]?.let { numOf(it) } ?: 12f).coerceIn(4f, 144f)
        s.marks.add(
            TextMark(
                page = page,
                x = (input.num("x") / 100f).coerceIn(0f, 1f),
                y = (input.num("y") / 100f).coerceIn(0f, 1f),
                text = text,
                size = fontSize,
                argb = colorOf(input["color"] as? String),
                pageWidth = size.width,
                pageHeight = size.height,
            ),
        )
        s.goToPage(page)
        return ToolOutput.Text("Texte ajouté sur la page ${page + 1} (gravé à l'enregistrement).")
    }

    private fun coverArea(s: DocumentSession, page: Int, input: Map<String, Any?>): ToolOutput {
        val x = input.num("x") / 100f
        val y = input.num("y") / 100f
        val w = input.num("width") / 100f
        val h = input.num("height") / 100f
        if (w <= 0f || h <= 0f) throw ToolError("Largeur et hauteur doivent être positives.")
        s.marks.add(BoxMark(page, NormRect(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), (x + w).coerceIn(0f, 1f), (y + h).coerceIn(0f, 1f)), InkColors.White))
        s.goToPage(page)
        return ToolOutput.Text("Zone masquée sur la page ${page + 1}.")
    }

    private suspend fun highlight(s: DocumentSession, page: Int, line: Int): ToolOutput {
        val block = s.textBlocks(page).getOrNull(line) ?: throw ToolError("Ligne $line introuvable : relis la page.")
        val size = s.pageSizes[page]
        val r = block.rect
        val mid = (r.top + r.bottom) / 2f
        s.marks.add(
            InkMark(
                page = page,
                points = listOf(NPoint(r.left, mid), NPoint(r.right, mid)),
                argb = InkColors.Yellow,
                width = (r.bottom - r.top) * size.height,
                opacity = 0.4f,
            ),
        )
        s.goToPage(page)
        return ToolOutput.Text("Ligne surlignée.")
    }

    private suspend fun rotate(s: DocumentSession, input: Map<String, Any?>): ToolOutput {
        val pages = pagesOf(s, input)
        val degrees = input.int("degrees")
        if (degrees % 90 != 0) throw ToolError("L'angle doit être 90, 180 ou 270.")
        state.editAwait("Rotation…") { doc -> for (p in pages) doc.setRotation(p, doc.geometry(p).rotation + degrees) }
        return ToolOutput.Text("Pages ${pages.map { it + 1 }} pivotées de $degrees°.")
    }

    private suspend fun deletePages(s: DocumentSession, input: Map<String, Any?>): ToolOutput {
        val pages = pagesOf(s, input)
        if (pages.size >= s.pageCount) throw ToolError("Impossible de supprimer toutes les pages.")
        state.editAwait("Suppression…") { doc -> doc.reorder((0 until doc.pageCount).filter { it !in pages }) }
        return ToolOutput.Text("Pages ${pages.map { it + 1 }} supprimées. Le document a maintenant ${s.pageCount - pages.size} pages.")
    }

    private suspend fun movePage(s: DocumentSession, input: Map<String, Any?>): ToolOutput {
        val from = input.int("from") - 1
        val to = input.int("to") - 1
        if (from !in 0 until s.pageCount || to !in 0 until s.pageCount) throw ToolError("Numéro de page invalide.")
        val order = (0 until s.pageCount).toMutableList()
        order.add(to, order.removeAt(from))
        state.editAwait("Déplacement…") { it.reorder(order) }
        return ToolOutput.Text("Page ${from + 1} déplacée en position ${to + 1}.")
    }

    private suspend fun insertBlank(s: DocumentSession, input: Map<String, Any?>): ToolOutput {
        val after = input.int("after").coerceIn(0, s.pageCount)
        val size = s.pageSizes[(after - 1).coerceIn(0, s.pageCount - 1)]
        state.editAwait("Ajout d'une page…") { it.insertBlankPage(after, size.width, size.height) }
        return ToolOutput.Text("Page blanche insérée en position ${after + 1}.")
    }

    // ---- Utilitaires --------------------------------------------------------------------------

    private class ToolError(message: String) : Exception(message)

    private fun page(s: DocumentSession, input: Map<String, Any?>): Int {
        val p = input.int("page")
        if (p < 1 || p > s.pageCount) throw ToolError("La page $p n'existe pas (le document a ${s.pageCount} pages).")
        return p - 1
    }

    private fun pagesOf(s: DocumentSession, input: Map<String, Any?>): Set<Int> {
        val raw = input["pages"] as? List<*> ?: throw ToolError("Paramètre « pages » manquant.")
        val pages = raw.mapNotNull { it?.let(::intOf) }.map { it - 1 }.toSet()
        if (pages.isEmpty() || pages.any { it !in 0 until s.pageCount }) throw ToolError("Numéros de page invalides : $raw")
        return pages
    }

    private fun Map<String, Any?>.int(key: String): Int =
        this[key]?.let(::intOf) ?: throw ToolError("Paramètre « $key » manquant ou invalide.")

    private fun Map<String, Any?>.num(key: String): Float =
        this[key]?.let(::numOf) ?: throw ToolError("Paramètre « $key » manquant ou invalide.")

    private fun Map<String, Any?>.string(key: String, allowEmpty: Boolean = false): String {
        val v = this[key] as? String ?: throw ToolError("Paramètre « $key » manquant.")
        if (!allowEmpty && v.isBlank()) throw ToolError("Paramètre « $key » vide.")
        return v
    }

    private fun intOf(v: Any): Int? = when (v) {
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull()
        else -> null
    }

    private fun numOf(v: Any): Float? = when (v) {
        is Number -> v.toFloat()
        is String -> v.trim().replace(',', '.').toFloatOrNull()
        else -> null
    }

    private fun colorOf(name: String?): Int = when (name?.lowercase()?.trim()) {
        "bleu", "blue" -> InkColors.Blue
        "rouge", "red" -> InkColors.Red
        "vert", "green" -> InkColors.Green
        "orange" -> InkColors.Orange
        "blanc", "white" -> InkColors.White
        else -> InkColors.Black
    }

    private fun normalize(s: String) = s.trim().replace(Regex("\\s+"), " ")

    private fun short(v: Any?): String {
        val s = (v as? String ?: "").replace('\n', ' ')
        return if (s.length > 40) s.take(39) + "…" else s
    }

    private fun contains(outer: NormRect, inner: NormRect): Boolean {
        val cx = (inner.left + inner.right) / 2
        val cy = (inner.top + inner.bottom) / 2
        return cx >= outer.left && cx <= outer.right && cy >= outer.top && cy <= outer.bottom
    }

    private companion object {
        const val MAX_LINES = 400
        const val MAX_HITS = 40
        const val VIEW_WIDTH = 1100

        fun int(description: String) = mapOf<String, Any>("type" to "integer", "description" to description)
        fun num(description: String) = mapOf<String, Any>("type" to "number", "description" to description)
        fun str(description: String) = mapOf<String, Any>("type" to "string", "description" to description)
        fun intList(description: String) =
            mapOf<String, Any>("type" to "array", "items" to mapOf("type" to "integer"), "description" to description)
    }
}
