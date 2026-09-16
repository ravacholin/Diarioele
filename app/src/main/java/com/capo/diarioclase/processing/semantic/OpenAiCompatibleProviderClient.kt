package com.capo.diarioclase.processing.semantic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Perfil fijo de un proveedor compatible con la API de OpenAI. */
data class OpenAiCompatibleProfile(
    val provider: InferenceProvider,
    val endpoint: String,
    val modelId: String,
    val strictJsonSchema: Boolean,
) {
    companion object {
        val GROQ = OpenAiCompatibleProfile(
            provider = InferenceProvider.GROQ,
            endpoint = "https://api.groq.com/openai/v1/chat/completions",
            modelId = "openai/gpt-oss-20b",
            strictJsonSchema = true,
        )

        val OPENROUTER = OpenAiCompatibleProfile(
            provider = InferenceProvider.OPENROUTER,
            endpoint = "https://openrouter.ai/api/v1/chat/completions",
            modelId = "openrouter/free",
            strictJsonSchema = false,
        )
    }
}

/**
 * Adaptador compatible con OpenAI (Task 4) para Groq y OpenRouter.
 *
 * Groq usa `response_format.type = json_schema` con `strict = true`. OpenRouter pide JSON
 * por prompt y `response_format.type = json_object`, envía `provider.data_collection =
 * "deny"` y precio máximo cero, y queda siempre último. La clave viaja en
 * `Authorization: Bearer`. La validación real es de Task 5.
 */
class OpenAiCompatibleProviderClient(
    private val profile: OpenAiCompatibleProfile,
    private val transport: InferenceHttpTransport,
    private val promptFactory: InterpretationPromptFactory = InterpretationPromptFactory(),
) : InferenceProviderClient {

    override suspend fun infer(
        request: InterpretationRequest,
        credential: EphemeralCredential,
        attempt: InferenceAttemptContext,
    ): ProviderOutcome {
        val prompt = promptFactory.create(request)
        val body = buildBody(prompt)
        val headers = mapOf("Authorization" to "Bearer ${credential.value}")

        val response = when (val attempt = runTransport(transport, profile.endpoint, headers, body)) {
            is TransportAttempt.Error ->
                return ProviderOutcome.Failure(profile.provider, attempt.code, attempt.retryable)
            is TransportAttempt.Ok -> attempt.value
        }
        if (response.status !in 200..299) {
            return mapHttpStatusToFailure(
                profile.provider,
                response.status,
                headerIgnoreCase(response.headers, "Retry-After"),
            )
        }
        if (response.body.isBlank()) {
            return ProviderOutcome.Failure(profile.provider, ProviderFailure.EMPTY_RESPONSE, retryable = false)
        }
        val json = extractMessageJson(response.body)
            ?: return ProviderOutcome.Failure(profile.provider, ProviderFailure.INVALID_RESPONSE, retryable = false)
        return ProviderOutcome.Success(profile.provider, profile.modelId, json)
    }

    private fun buildBody(prompt: InterpretationPrompt): String {
        val userText = prompt.userText +
            "\n\nRespondé con un único JSON que cumpla este esquema:\n" + prompt.jsonSchema
        return buildJsonObject {
            put("model", profile.modelId)
            put("temperature", 0)
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "system"); put("content", prompt.systemInstruction) })
                add(buildJsonObject { put("role", "user"); put("content", userText) })
            }
            if (profile.strictJsonSchema) {
                putJsonObject("response_format") {
                    put("type", "json_schema")
                    putJsonObject("json_schema") {
                        put("name", "claims")
                        put("strict", true)
                        put("schema", INFERENCE_JSON.parseToJsonElement(prompt.jsonSchema))
                    }
                }
            } else {
                putJsonObject("response_format") { put("type", "json_object") }
                putJsonObject("provider") {
                    put("data_collection", "deny")
                    putJsonObject("max_price") {
                        put("prompt", 0)
                        put("completion", 0)
                    }
                }
            }
        }.toString()
    }

    private fun extractMessageJson(body: String): String? = runCatching {
        val root = INFERENCE_JSON.parseToJsonElement(body).jsonObject
        val choice = root["choices"]?.jsonArray?.getOrNull(0)?.jsonObject
            ?: return@runCatching null
        val content = choice["message"]?.jsonObject?.get("content")?.jsonPrimitive?.content
            ?: return@runCatching null
        stripJsonFences(content).takeIf { it.isNotBlank() }
    }.getOrNull()
}

// --- Helpers compartidos por los adaptadores (Task 4) ----------------------------------

internal val INFERENCE_JSON = Json { ignoreUnknownKeys = true }

internal sealed interface TransportAttempt {
    data class Ok(val value: HttpTransportResult) : TransportAttempt
    data class Error(val code: ProviderFailure, val retryable: Boolean) : TransportAttempt
}

/** Ejecuta el transporte y traduce excepciones de red a fallas tipificadas, sin secretos. */
internal suspend fun runTransport(
    transport: InferenceHttpTransport,
    url: String,
    headers: Map<String, String>,
    body: String,
): TransportAttempt = try {
    TransportAttempt.Ok(transport.request(url, headers, body))
} catch (e: SocketTimeoutException) {
    TransportAttempt.Error(ProviderFailure.TIMEOUT, retryable = true)
} catch (e: UnknownHostException) {
    TransportAttempt.Error(ProviderFailure.NO_NETWORK, retryable = false)
} catch (e: ConnectException) {
    TransportAttempt.Error(ProviderFailure.NO_NETWORK, retryable = false)
} catch (e: TransportPolicyException) {
    TransportAttempt.Error(ProviderFailure.INVALID_RESPONSE, retryable = false)
} catch (e: IOException) {
    TransportAttempt.Error(ProviderFailure.SERVER_UNAVAILABLE, retryable = true)
}

internal fun mapHttpStatusToFailure(
    provider: InferenceProvider,
    status: Int,
    retryAfterHeader: String?,
): ProviderOutcome.Failure {
    val (code, retryable) = when (status) {
        401, 403 -> ProviderFailure.AUTHENTICATION to false
        402 -> ProviderFailure.BILLING_RISK to false
        429 -> ProviderFailure.QUOTA to false
        500, 502, 503, 504 -> ProviderFailure.SERVER_UNAVAILABLE to true
        408 -> ProviderFailure.TIMEOUT to true
        400, 404, 422 -> ProviderFailure.INVALID_RESPONSE to false
        else -> ProviderFailure.INTERNAL to false
    }
    return ProviderOutcome.Failure(
        provider = provider,
        code = code,
        retryable = retryable,
        httpStatus = status,
        retryAfterMs = parseRetryAfterMs(retryAfterHeader),
    )
}

/** Segundos de `Retry-After` a milisegundos, con tope de 5 s. Ignora fechas HTTP. */
internal fun parseRetryAfterMs(header: String?): Long? {
    val seconds = header?.trim()?.toLongOrNull() ?: return null
    if (seconds < 0) return null
    return (seconds * 1_000).coerceAtMost(5_000)
}

internal fun headerIgnoreCase(headers: Map<String, String>, name: String): String? =
    headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

internal fun stripJsonFences(text: String): String {
    val trimmed = text.trim()
    if (!trimmed.startsWith("```")) return trimmed
    return trimmed
        .removePrefix("```json")
        .removePrefix("```JSON")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()
}
