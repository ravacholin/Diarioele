package com.capo.diarioclase.processing.editorial

import com.capo.diarioclase.processing.semantic.EphemeralCredential
import com.capo.diarioclase.processing.semantic.INFERENCE_JSON
import com.capo.diarioclase.processing.semantic.InferenceHttpTransport
import com.capo.diarioclase.processing.semantic.OpenAiCompatibleProfile
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

class OpenAiEditorialProviderClient(
    private val profile: OpenAiCompatibleProfile,
    private val transport: InferenceHttpTransport,
    private val promptFactory: EditorialReportPromptFactory = EditorialReportPromptFactory(),
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
                profile.endpoint,
                mapOf("Authorization" to "Bearer ${credential.value}"),
                buildBody(prompt),
            )
        ) {
            is TransportAttempt.Error -> return ProviderOutcome.Failure(
                profile.provider,
                attempt.code,
                attempt.retryable,
            )
            is TransportAttempt.Ok -> attempt.value
        }
        if (response.status !in 200..299) {
            return mapHttpStatusToFailure(
                profile.provider,
                response.status,
                headerIgnoreCase(response.headers, "Retry-After"),
            )
        }
        if (response.body.isBlank()) return failure(ProviderFailure.EMPTY_RESPONSE)
        val json = extractMessage(response.body) ?: return failure(ProviderFailure.INVALID_RESPONSE)
        return ProviderOutcome.Success(profile.provider, profile.modelId, json)
    }

    private fun buildBody(prompt: EditorialPrompt): String = buildJsonObject {
        put("model", profile.modelId)
        put("temperature", 0)
        putJsonArray("messages") {
            add(buildJsonObject { put("role", "system"); put("content", prompt.systemInstruction) })
            add(buildJsonObject { put("role", "user"); put("content", prompt.userText) })
        }
        if (profile.strictJsonSchema) {
            putJsonObject("response_format") {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", "editorial_report")
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

    private fun extractMessage(body: String): String? = runCatching {
        val root = INFERENCE_JSON.parseToJsonElement(body).jsonObject
        val content = root["choices"]?.jsonArray?.getOrNull(0)?.jsonObject
            ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content
            ?: return@runCatching null
        stripJsonFences(content).takeIf(String::isNotBlank)
    }.getOrNull()

    private fun failure(code: ProviderFailure) =
        ProviderOutcome.Failure(profile.provider, code, retryable = false)
}
