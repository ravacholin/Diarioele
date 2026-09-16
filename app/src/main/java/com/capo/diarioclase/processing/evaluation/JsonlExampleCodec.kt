package com.capo.diarioclase.processing.evaluation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Códec JSONL versionado del corpus local (Fase 6, Q6). Un ejemplo por línea. No usa
 * `@Serializable` (no hay plugin de serialization): arma el JSON a mano con kotlinx, apto para
 * JVM puro sin Robolectric. Round-trip estable.
 */
class JsonlExampleCodec {

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(example: LocalEvaluationExample): String = buildJsonObject {
        put("formatVersion", example.formatVersion)
        putJsonObject("pipelineVersions") {
            put("appVersion", example.pipelineVersions.appVersion)
            put("whisperVersion", example.pipelineVersions.whisperVersion)
            put("promptVersion", example.pipelineVersions.promptVersion)
            put("schemaVersion", example.pipelineVersions.schemaVersion)
            put("validatorVersion", example.pipelineVersions.validatorVersion)
            put("scoreVersion", example.pipelineVersions.scoreVersion)
        }
        putJsonArray("spans") {
            example.spans.forEach { span ->
                add(
                    buildJsonObject {
                        put("publicId", span.publicId)
                        put("text", span.text)
                        put("contextOnly", span.contextOnly)
                    },
                )
            }
        }
        putJsonArray("automaticClaims") {
            example.automaticClaims.forEach { claim ->
                add(
                    buildJsonObject {
                        put("category", claim.category)
                        put("normalizedValue", claim.normalizedValue)
                        put("status", claim.status)
                        put("provenance", claim.provenance)
                        put("effectiveConfidence", claim.effectiveConfidence)
                        put("evidencePublicIds", buildJsonArray { claim.evidencePublicIds.forEach { add(it) } })
                    },
                )
            }
        }
        putJsonArray("revisions") {
            example.revisions.forEach { revision ->
                add(
                    buildJsonObject {
                        put("field", revision.field)
                        put("beforeValue", revision.beforeValue)
                        put("afterValue", revision.afterValue)
                        put("action", revision.action)
                    },
                )
            }
        }
        putJsonObject("finalFields") {
            example.finalFields.forEach { (key, value) -> put(key, value) }
        }
    }.toString()

    fun encodeAll(examples: List<LocalEvaluationExample>): String =
        examples.joinToString("\n") { encode(it) }

    fun decode(line: String): LocalEvaluationExample {
        val root = json.parseToJsonElement(line).jsonObject
        val versions = root.getValue("pipelineVersions").jsonObject
        return LocalEvaluationExample(
            formatVersion = root.getValue("formatVersion").jsonPrimitive.int,
            pipelineVersions = PipelineVersions(
                appVersion = versions.getValue("appVersion").jsonPrimitive.content,
                whisperVersion = versions.getValue("whisperVersion").jsonPrimitive.content,
                promptVersion = versions.getValue("promptVersion").jsonPrimitive.content,
                schemaVersion = versions.getValue("schemaVersion").jsonPrimitive.content,
                validatorVersion = versions.getValue("validatorVersion").jsonPrimitive.content,
                scoreVersion = versions.getValue("scoreVersion").jsonPrimitive.content,
            ),
            spans = root.getValue("spans").jsonArray.map {
                val obj = it.jsonObject
                ExampleSpan(
                    obj.getValue("publicId").jsonPrimitive.content,
                    obj.getValue("text").jsonPrimitive.content,
                    obj.getValue("contextOnly").jsonPrimitive.boolean,
                )
            },
            automaticClaims = root.getValue("automaticClaims").jsonArray.map {
                val obj = it.jsonObject
                ExampleClaim(
                    obj.getValue("category").jsonPrimitive.content,
                    obj.getValue("normalizedValue").jsonPrimitive.content,
                    obj.getValue("status").jsonPrimitive.content,
                    obj.getValue("provenance").jsonPrimitive.content,
                    obj.getValue("effectiveConfidence").jsonPrimitive.double,
                    obj.getValue("evidencePublicIds").jsonArray.map { id -> id.jsonPrimitive.content },
                )
            },
            revisions = root.getValue("revisions").jsonArray.map {
                val obj = it.jsonObject
                ExampleRevision(
                    obj.getValue("field").jsonPrimitive.content,
                    obj.getValue("beforeValue").jsonPrimitive.content,
                    obj.getValue("afterValue").jsonPrimitive.content,
                    obj.getValue("action").jsonPrimitive.content,
                )
            },
            finalFields = root.getValue("finalFields").jsonObject.mapValues { it.value.jsonPrimitive.content },
        )
    }

    fun decodeAll(jsonl: String): List<LocalEvaluationExample> =
        jsonl.split("\n").filter { it.isNotBlank() }.map { decode(it) }
}
