package app.nougat.pdf.assistant

import app.nougat.pdf.i18n.Str

/*
 * L'assistant Nougat : Claude, branche sur le document ouvert.
 *
 * Chaque utilisateur utilise SA propre cle API Anthropic (console.anthropic.com) : c'est lui qui
 * paie sa consommation, jamais l'auteur de l'app. La cle reste sur l'appareil.
 *
 * Le code commun decrit les outils (lire une page, modifier une ligne, ajouter du texte...) et
 * l'interface ; la plateforme fournit [AssistantEngine], qui parle a l'API avec le SDK officiel.
 */

data class AssistantSettings(val apiKey: String, val model: String)

object AssistantModels {
    const val DEFAULT = "claude-opus-5-5"

    /** Identifiant, nom affiche, description courte (traduite). */
    val choices = listOf(
        Triple("claude-opus-5-5", "Claude Opus 5.5", Str.ModelOpusDesc),
        Triple("claude-sonnet-5-5", "Claude Sonnet 5.5", Str.ModelSonnetDesc),
        Triple("claude-haiku-5-5", "Claude Haiku 5.5", Str.ModelHaikuDesc),
    )

    fun label(id: String) = choices.firstOrNull { it.first == id }?.second ?: id
}

/** Un element de la conversation affichee. */
sealed interface ChatItem {
    data class User(val text: String) : ChatItem
    data class Assistant(val text: String) : ChatItem

    /** Une action de l'assistant sur le document (« Modifie la ligne 3 de la page 1 »). */
    data class Action(val label: String, val ok: Boolean? = null, val detail: String? = null) : ChatItem
    data class Error(val text: String) : ChatItem
}

/** Description d'un outil, en JSON Schema simplifie (types JSON et descriptions). */
class ToolSpec(
    val name: String,
    val description: String,
    /** Nom du parametre -> schema JSON (ex. mapOf("type" to "integer", "description" to "...")). */
    val properties: Map<String, Map<String, Any>>,
    val required: List<String>,
)

/** Resultat d'un outil, renvoye a Claude. */
sealed interface ToolOutput {
    data class Text(val text: String, val isError: Boolean = false) : ToolOutput

    /** Une image (JPEG) de la page, avec une courte legende. */
    class Image(val jpeg: ByteArray, val caption: String) : ToolOutput
}

/** Les outils mis a disposition de Claude, et ce qu'il doit savoir. */
interface AssistantToolbox {
    val systemPrompt: String
    val specs: List<ToolSpec>

    /** Libelle lisible d'un appel d'outil, pour la conversation. */
    fun label(name: String, input: Map<String, Any?>): String

    /** Execute l'outil. Ne leve pas d'exception : les erreurs deviennent ToolOutput.Text(isError = true). */
    suspend fun run(name: String, input: Map<String, Any?>): ToolOutput
}

/** La partie qui parle a l'API Claude (fournie par la plateforme, avec le SDK officiel). */
interface AssistantEngine {
    /**
     * Envoie [userText] puis laisse Claude travailler (outils compris) jusqu'a sa reponse finale.
     * Chaque etape est signalee par [onItem].
     */
    suspend fun send(userText: String, settings: AssistantSettings, toolbox: AssistantToolbox, onItem: (ChatItem) -> Unit)

    /** Oublie la conversation en cours. */
    fun reset()
}
