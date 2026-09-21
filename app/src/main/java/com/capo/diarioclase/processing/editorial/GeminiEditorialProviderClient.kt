package com.capo.diarioclase.processing.editorial

import com.capo.diarioclase.processing.semantic.EphemeralCredential
import com.capo.diarioclase.processing.semantic.INFERENCE_JSON
import com.capo.diarioclase.processing.semantic.InferenceHttpTransport
import com.capo.diarioclase.processing.semantic.InferenceProvider
import com.capo.diarioclase.processing.semantic.ProviderFailure
import com.capo.diarioclase.processing.semantic.ProviderOutcome
import com.capo.diarioclase.processing.semantic.TransportAttempt
import com.capo.diarioclase.processing.semantic.headerIgnoreCase
import com.capo.diarioclase.processing.semantic.mapHttpStatusToFailure
import com.capo.diarioclase.processing.semantic.runTransport
import com.capo.diarioclase.processing.semantic.stripJsonFences
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class GeminiEditorialProviderClient(
    private val transport: InferenceHttpTransport,
    private val promptFactory: EditorialReportPromptFactory = EditorialReportPromptFactory(),
    private val modelId: String = DEFAULT_MODEL,
    private val endpoint: String =
        "https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent",
) : EditorialProviderClient {
    override suspend fun generate(
        request: EditorialReportRequest,
        credential: EphemeralCredential,
        repair: EditorialRepair?,
    ): ProviderOutcome {
        val prompt = promptFactory.create(request, repair)
        val response = when (
            val attempt = runTransport(
                transport,
                endpoint,
                mapOf("x-goog-api-key" to credential.value),
                buildBody(prompt),
            )
        ) {
            is TransportAttempt.Error -> return ProviderOutcome.Failure(
                InferenceProvider.GEMINI,
                attempt.code,
                attempt.retryable,
            )
            is TransportAttempt.Ok -> attempt.value
        }
        if (response.status !in 200..299) {
            return mapHttpStatusToFailure(
                InferenceProvider.GEMINI,
                response.status,
                headerIgnoreCase(response.headers, "Retry-After"),
            )
        }
        if (response.body.isBlank()) return failure(ProviderFailure.EMPTY_RESPONSE)
        val json = extractCandidate(response.body) ?: return failure(ProviderFailure.INVALID_RESPONSE)
        return ProviderOutcome.Success(InferenceProvider.GEMINI, modelId, json)
    }

    private fun buildBody(prompt: EditorialPrompt): String = buildJsonObject {
        putJsonObject("systemInstruction") {
            putJsonArray("parts") { add(buildJsonObject { put("text", prompt.systemInstruction) }) }
        }
        putJsonArray("contents") {
            add(
                buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { add(buildJsonObject { put("text", prompt.userText) }) }
                },
            )
        }
        putJsonObject("generationConfig") {
            put("responseMimeType", "application/json")
            put("responseJsonSchema", INFERENCE_JSON.parseToJsonElement(prompt.jsonSchema))
            put("temperature", 0)
        }
    }.toString()

    private fun extractCandidate(body: String): String? = runCatching {
        val root = INFERENCE_JSON.parseToJsonElement(body).jsonObject
        val parts = root["candidates"]?.jsonArray?.getOrNull(0)?.jsonObject
            ?.get("content")?.jsonObject?.get("parts")?.jsonArray
            ?: return@runCatching null
        val content = parts.getOrNull(0)?.jsonObject?.get("text")?.jsonPrimitive?.content
            ?: return@runCatching null
        stripJsonFences(content).takeIf(String::isNotBlank)
    }.getOrNull()

    private fun failure(code: ProviderFailure) =
        ProviderOutcome.Failure(InferenceProvider.GEMINI, code, retryable = false)

    companion object {
        const val DEFAULT_MODEL = "gemini-2.5-flash"
    }
}
