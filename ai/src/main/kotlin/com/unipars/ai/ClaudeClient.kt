package com.unipars.ai

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.BetaWebSearchTool20260209
import com.anthropic.models.beta.messages.MessageCreateParams
import java.time.Duration

/**
 * Claude via the official Anthropic Java SDK, with the user's own API key (BYOK).
 * Only used during setup and on explicit "repair with AI"; background runs never call it.
 */
class ClaudeClient(
    apiKey: String,
    private val model: String = DEFAULT_MODEL,
    /** "low" | "medium" | "high" — thinking depth and token spend. */
    effortLevel: String = "medium",
) : AiClient {

    private val effort = when (effortLevel) {
        "low" -> BetaOutputConfig.Effort.LOW
        "high" -> BetaOutputConfig.Effort.HIGH
        else -> BetaOutputConfig.Effort.MEDIUM
    }

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .timeout(Duration.ofMinutes(5))
        .maxRetries(2)
        .build()

    override fun complete(request: AiRequest): AiResponse {
        val output = BetaOutputConfig.builder().effort(effort)
        request.jsonSchema?.let { schema ->
            val s = BetaJsonOutputFormat.Schema.builder()
            schema.forEach { (k, v) -> s.putAdditionalProperty(k, JsonValue.from(v)) }
            output.format(BetaJsonOutputFormat.builder().schema(s.build()).build())
        }
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(request.maxTokens)
            .system(request.system)
            .outputConfig(output.build())
            // Server-side fallback: if a safety classifier declines, the server retries on a suitable model.
            .addBeta(FALLBACK_BETA)
            .fallbacksDefault()
        if (request.webSearch) params.addTool(BetaWebSearchTool20260209.builder().maxUses(5).build())
        request.messages.forEach { m ->
            when (m.role) {
                Role.USER -> params.addUserMessage(m.text)
                Role.ASSISTANT -> params.addAssistantMessage(m.text)
            }
        }

        val response = try {
            client.beta().messages().create(params.build())
        } catch (e: UnauthorizedException) {
            throw AiException("Неверный API-ключ", AiException.Kind.AUTH, e)
        } catch (e: PermissionDeniedException) {
            throw AiException("Ключ не имеет доступа к модели $model", AiException.Kind.AUTH, e)
        } catch (e: RateLimitException) {
            throw AiException("Превышен лимит запросов API, попробуйте позже", AiException.Kind.RATE_LIMIT, e)
        } catch (e: AnthropicServiceException) {
            throw AiException("Ошибка API (${e.statusCode()}): ${e.message}", AiException.Kind.OTHER, e)
        } catch (e: AnthropicIoException) {
            throw AiException("Нет связи с API: ${e.message}", AiException.Kind.NETWORK, e)
        }

        val usage = response.usage().let { AiUsage(it.inputTokens(), it.outputTokens()) }
        val stop = response.stopReason().orElse(null)
        if (stop == BetaStopReason.REFUSAL) {
            throw AiException("Модель отказалась выполнять запрос", AiException.Kind.REFUSAL)
        }
        val text = response.content().mapNotNull { block -> block.text().map { it.text() }.orElse(null) }
            .joinToString("")
        if (stop == BetaStopReason.MAX_TOKENS && request.jsonSchema != null) {
            throw AiException("Ответ модели обрезан по лимиту токенов", AiException.Kind.BAD_OUTPUT)
        }
        return AiResponse(text, usage)
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
    }
}
