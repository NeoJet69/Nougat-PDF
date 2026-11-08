package app.nougat.pdf.assistant

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Base64
import app.nougat.pdf.i18n.Str
import app.nougat.pdf.i18n.tr

/**
 * L'assistant Nougat avec le SDK Java officiel d'Anthropic : boucle d'outils manuelle
 * (Claude demande un outil -> Nougat l'execute sur le document -> on renvoie le resultat).
 */
class ClaudeAssistantEngine : AssistantEngine {
    /** La conversation, telle qu'envoyee a l'API (les blocs de reflexion sont renvoyes tels quels). */
    private val history = ArrayList<MessageParam>()
    private var client: AnthropicClient? = null
    private var clientKey: String? = null

    override fun reset() {
        history.clear()
    }

    override suspend fun send(
        userText: String,
        settings: AssistantSettings,
        toolbox: AssistantToolbox,
        onItem: (ChatItem) -> Unit,
    ) {
        val client = clientFor(settings.apiKey)
        val tools = toolbox.specs.map(::toTool)
        val turnStart = history.size
        history.add(MessageParam.ofUser(userText))
        try {
            repeat(MAX_STEPS) {
                val response = withContext(Dispatchers.IO) {
                    client.messages().create(request(settings, toolbox.systemPrompt, tools))
                }
                // La reponse complete (reflexion comprise) repart telle quelle dans l'historique.
                history.add(response.toParam())

                val toolUses = ArrayList<ToolUseBlock>()
                for (block in response.content()) {
                    block.text().ifPresent { t -> if (t.text().isNotBlank()) onItem(ChatItem.Assistant(t.text().trim())) }
                    block.toolUse().ifPresent { toolUses.add(it) }
                }

                when (response.stopReason().orElse(null)) {
                    StopReason.REFUSAL -> {
                        onItem(ChatItem.Error(tr(Str.ClaudeRefused)))
                        return
                    }
                    StopReason.MAX_TOKENS -> if (toolUses.isEmpty()) {
                        onItem(ChatItem.Error(tr(Str.ReplyTooLong)))
                        return
                    }
                    StopReason.TOOL_USE -> Unit
                    else -> if (toolUses.isEmpty()) return
                }
                if (toolUses.isEmpty()) return

                // Tous les resultats d'outils repartent ensemble, dans un seul message.
                val results = ArrayList<ContentBlockParam>()
                for (use in toolUses) {
                    val input = inputOf(use)
                    val output = toolbox.run(use.name(), input)
                    val failed = output is ToolOutput.Text && output.isError
                    onItem(ChatItem.Action(toolbox.label(use.name(), input), ok = !failed, detail = (output as? ToolOutput.Text)?.text?.takeIf { failed }))
                    results.add(ContentBlockParam.ofToolResult(resultOf(use.id(), output)))
                }
                history.add(MessageParam.ofUserOfContentBlockParams(results))
            }
            onItem(ChatItem.Error(tr(Str.TooManySteps, MAX_STEPS)))
        } catch (e: CancellationException) {
            dropTurn(turnStart)
            throw e
        } catch (e: Throwable) {
            dropTurn(turnStart)
            throw IllegalStateException(friendlyMessage(e), e)
        }
    }

    override suspend fun translate(texts: List<String>, language: String, settings: AssistantSettings): List<String> {
        val client = clientFor(settings.apiKey)
        val result = ArrayList<String>(texts.size)
        // Par paquets d'environ 6000 caracteres : une reponse ne doit jamais etre coupee.
        var start = 0
        while (start < texts.size) {
            var end = start
            var chars = 0
            while (end < texts.size && (end == start || chars + texts[end].length <= 6000)) {
                chars += texts[end].length
                end++
            }
            result.addAll(translateBatch(client, texts.subList(start, end), language, settings.model))
            start = end
        }
        return result
    }

    private suspend fun translateBatch(client: AnthropicClient, texts: List<String>, language: String, model: String): List<String> {
        val input = buildString {
            texts.forEachIndexed { i, t -> append("<p id=\"").append(i + 1).append("\">").append(escapeXml(t)).append("</p>\n") }
        }
        val system = "You translate documents. The user sends the paragraphs of one PDF page, each in <p id=\"N\">...</p>. " +
            "Translate every paragraph into " + language + ". Answer with the same <p id=\"N\">...</p> elements, in the same order, " +
            "one translation per paragraph, and nothing else. Keep the meaning, the tone and the register of the original. " +
            "Keep names, brands, numbers, amounts, dates, codes, e-mail and web addresses exactly as they are. " +
            "A paragraph that is already in " + language + ", or that has nothing to translate, is returned unchanged. " +
            "Escape <, > and & as &lt;, &gt; and &amp;."
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(16000L)
            .system(system)
            .addUserMessage(input)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .build()
        val response = try {
            withContext(Dispatchers.IO) { client.messages().create(params) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw IllegalStateException(friendlyMessage(e), e)
        }
        if (response.stopReason().orElse(null) == StopReason.REFUSAL) throw IllegalStateException(tr(Str.ClaudeRefused))
        val answer = response.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
        val found = HashMap<Int, String>()
        for (m in Regex("<p id=\"(\\d+)\">(.*?)</p>", RegexOption.DOT_MATCHES_ALL).findAll(answer)) {
            found[m.groupValues[1].toInt()] = unescapeXml(m.groupValues[2]).trim()
        }
        return texts.mapIndexed { i, t -> found[i + 1]?.takeIf { it.isNotBlank() } ?: t }
    }

    override suspend fun findSensitive(text: String, kinds: List<String>, settings: AssistantSettings): List<Pair<String, String>> {
        if (text.isBlank() || kinds.isEmpty()) return emptyList()
        val client = clientFor(settings.apiKey)
        val system = "You find personal and confidential information in the text of a document, so that it can be redacted. " +
            "Answer with one item per line, as: KIND, a tab, then the item copied exactly as it appears in the text. " +
            "KIND is one of: " + kinds.joinToString(", ") + ". Only report items of these kinds. " +
            "Report every occurrence form once (the same name written differently counts twice). " +
            "If there is nothing, answer NONE. No other text."
        val params = MessageCreateParams.builder()
            .model(settings.model)
            .maxTokens(4000L)
            .system(system)
            .addUserMessage(text.take(30_000))
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .build()
        val response = try {
            withContext(Dispatchers.IO) { client.messages().create(params) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw IllegalStateException(friendlyMessage(e), e)
        }
        val answer = response.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
        return answer.lines().mapNotNull { line ->
            val parts = line.split('\t', limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val kind = parts[0].trim().lowercase()
            val item = parts[1].trim()
            if (kind in kinds && item.length >= 2) kind to item else null
        }
    }

    override suspend fun digest(text: String, instructions: String, settings: AssistantSettings): String {
        val client = clientFor(settings.apiKey)
        val params = MessageCreateParams.builder()
            .model(settings.model)
            .maxTokens(2000L)
            .system(instructions)
            .addUserMessage(text)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
            .build()
        val response = try {
            withContext(Dispatchers.IO) { client.messages().create(params) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw IllegalStateException(friendlyMessage(e), e)
        }
        return response.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
    }

    private fun escapeXml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun unescapeXml(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")

    private fun request(settings: AssistantSettings, system: String, tools: List<Tool>): MessageCreateParams {
        val builder = MessageCreateParams.builder()
            .model(settings.model)
            .maxTokens(16000L)
            // Consignes et outils identiques d'un appel a l'autre : mis en cache.
            .systemOfTextBlockParams(
                listOf(
                    TextBlockParam.builder()
                        .text(system)
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build(),
                ),
            )
            .messages(history)
            .cacheControl(CacheControlEphemeral.builder().build())
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM).build())
        tools.forEach { builder.addTool(it) }
        if (settings.model.startsWith("claude-opus") || settings.model.startsWith("claude-sonnet")) {
            // Si une demande est refusee par erreur, l'API la repasse a un autre modele.
            builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        return builder.build()
    }

    private fun clientFor(apiKey: String): AnthropicClient {
        val current = client
        if (current != null && clientKey == apiKey) return current
        current?.close()
        return AnthropicOkHttpClient.builder().apiKey(apiKey).build().also {
            client = it
            clientKey = apiKey
        }
    }

    private fun toTool(spec: ToolSpec): Tool {
        val properties = Tool.InputSchema.Properties.builder()
        spec.properties.forEach { (name, schema) -> properties.putAdditionalProperty(name, JsonValue.from(schema)) }
        return Tool.builder()
            .name(spec.name)
            .description(spec.description)
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(properties.build())
                    .required(spec.required)
                    .build(),
            )
            .build()
    }

    @Suppress("UNCHECKED_CAST")
    private fun inputOf(use: ToolUseBlock): Map<String, Any?> =
        runCatching { use._input().convert(Map::class.java) as? Map<String, Any?> }.getOrNull() ?: emptyMap()

    private fun resultOf(toolUseId: String, output: ToolOutput): ToolResultBlockParam = when (output) {
        is ToolOutput.Text -> ToolResultBlockParam.builder()
            .toolUseId(toolUseId)
            .content(output.text)
            .isError(output.isError)
            .build()
        is ToolOutput.Image -> ToolResultBlockParam.builder()
            .toolUseId(toolUseId)
            .contentOfBlocks(
                listOf(
                    ToolResultBlockParam.Content.Block.ofText(output.caption),
                    ToolResultBlockParam.Content.Block.ofImage(
                        Base64ImageSource.builder()
                            .data(Base64.getEncoder().encodeToString(output.jpeg))
                            .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                            .build(),
                    ),
                ),
            )
            .build()
    }

    /** Un echange interrompu ne doit pas laisser de demande d'outil sans reponse dans l'historique. */
    private fun dropTurn(turnStart: Int) {
        while (history.size > turnStart) history.removeAt(history.lastIndex)
    }

    private fun friendlyMessage(e: Throwable): String = when (e) {
        is UnauthorizedException -> tr(Str.ErrApiKey)
        is PermissionDeniedException -> tr(Str.ErrNoModelAccess)
        is NotFoundException -> tr(Str.ErrModelNotFound)
        is RateLimitException -> tr(Str.ErrRateLimit)
        is InternalServerException -> tr(Str.ErrOverloaded)
        is BadRequestException -> tr(Str.ErrBadRequest, e.message)
        is AnthropicServiceException -> tr(Str.ErrApi, e.statusCode(), e.message)
        is AnthropicIoException -> tr(Str.ErrNoConnection)
        else -> e.message ?: e::class.simpleName ?: tr(Str.UnknownError)
    }

    private companion object {
        const val MAX_STEPS = 30
    }
}
