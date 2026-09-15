package com.capo.diarioclase.processing.semantic

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Adaptador de Gemini (Task 4). Envía la clave en el header `x-goog-api-key`, nunca en la
 * query string. Pide salida `application/json` con el esquema lógico incrustado y extrae
 * el JSON del primer candidato. La validación real la hace Task 5.
 */
class GeminiProviderClient(
    private val transport: InferenceHttpTransport,
    private val promptFactory: InterpretationPromptFactory = InterpretationPromptFactory(),
    private val modelId: String = DEFAULT_MODEL,
    private val endpoint: String =
        "https://generativelanguage.googleapis.com/v1beta/models/$DEFAULT_MODEL:generateContent",
) : InferenceProviderClient {

    override suspend fun infer(
        request: InterpretationRequest,
        credential: EphemeralCredential,
    ): ProviderOutcome {
        val prompt = promptFactory.create(request)
        val body = buildBody(prompt)
        val headers = mapOf("x-goog-api-key" to credential.value)

        val response = when (val attempt = runTransport(transport, endpoint, headers, body)) {
            is TransportAttempt.Error ->
                return ProviderOutcome.Failure(InferenceProvider.GEMINI, attempt.code, attempt.retryable)
            is TransportAttempt.Ok -> attempt.value
        }
        if (response.status !in 200..299) {
            return mapHttpStatusToFailure(
                InferenceProvider.GEMINI,
                response.status,
                headerIgnoreCase(response.headers, "Retry-After"),
            )
        }
        if (response.body.isBlank()) return failure(ProviderFailure.EMPTY_RESPONSE, retryable = false)

        val json = extractCandidateJson(response.body)
            ?: return failure(ProviderFailure.INVALID_RESPONSE, retryable = false)
        return ProviderOutcome.Success(InferenceProvider.GEMINI, modelId, json)
    }

    private fun buildBody(prompt: InterpretationPrompt): String {
        val userText = prompt.userText +
            "\n\nRespondé con un único JSON que cumpla este esquema:\n" + prompt.jsonSchema
        return buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { add(buildJsonObject { put("text", prompt.systemInstruction) }) }
            }
            putJsonArray("contents") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") { add(buildJsonObject { put("text", userText) }) }
                    },
                )
            }
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                put("temperature", 0)
            }
        }.toString()
    }

    private fun extractCandidateJson(body: String): String? = runCatching {
        val root = INFERENCE_JSON.parseToJsonElement(body).jsonObject
        val candidate = root["candidates"]?.jsonArray?.getOrNull(0)?.jsonObject
            ?: return@runCatching null
        val parts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray
            ?: return@runCatching null
        val text = parts.getOrNull(0)?.jsonObject?.get("text")?.jsonPrimitive?.content
            ?: return@runCatching null
        stripJsonFences(text).takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun failure(code: ProviderFailure, retryable: Boolean) =
        ProviderOutcome.Failure(InferenceProvider.GEMINI, code, retryable)

    companion object {
        // `gemini-2.5-flash` es un modelo estable con free tier real (mejores límites
        // gratuitos: 15 pedidos/min, 1500/día). El anterior `gemini-3-flash-preview`
        // no existe en la API y devolvía 404.
        private const val DEFAULT_MODEL = "gemini-2.5-flash"
    }
}
