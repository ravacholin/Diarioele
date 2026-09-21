package com.capo.diarioclase.processing.editorial

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class EditorialContractException(message: String, cause: Throwable? = null) : Exception(message, cause)

object EditorialReportCodec {
    private const val MAX_BYTES = 65_536
    private const val MAX_ITEMS = 200
    private const val MAX_TEXT_LENGTH = 1_000
    private val rootKeys = setOf("summary", "material", "homework", "summary_source_claim_ids", "discarded")
    private val outputKeys = setOf("text", "source_claim_ids")
    private val discardKeys = setOf("claim_id", "reason")

    fun decode(rawJson: String): EditorialReport {
        if (rawJson.toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            throw EditorialContractException("Editorial response exceeds $MAX_BYTES bytes.")
        }
        val root = try {
            JSONObject(rawJson)
        } catch (error: JSONException) {
            throw EditorialContractException("Editorial response is not valid JSON.", error)
        }
        root.requireExactKeys(rootKeys, "root")
        val summary = root.requireText("summary", allowBlank = true)
        val material = root.requireOutputItems("material")
        val homework = root.requireOutputItems("homework")
        val summaryIds = root.requireStringList("summary_source_claim_ids")
        val discarded = root.requireDiscards("discarded")
        if (material.size + homework.size + discarded.size > MAX_ITEMS) {
            throw EditorialContractException("Editorial response exceeds $MAX_ITEMS items.")
        }
        return EditorialReport(summary, material, homework, summaryIds, discarded)
    }

    fun encode(report: EditorialReport): String = JSONObject()
        .put("summary", report.summary)
        .put("material", JSONArray().also { array ->
            report.material.forEach { item ->
                array.put(
                    JSONObject()
                        .put("text", item.text)
                        .put("source_claim_ids", JSONArray(item.sourceClaimIds)),
                )
            }
        })
        .put("homework", JSONArray().also { array ->
            report.homework.forEach { item ->
                array.put(
                    JSONObject()
                        .put("text", item.text)
                        .put("source_claim_ids", JSONArray(item.sourceClaimIds)),
                )
            }
        })
        .put("summary_source_claim_ids", JSONArray(report.summarySourceClaimIds))
        .put("discarded", JSONArray().also { array ->
            report.discarded.forEach { item ->
                array.put(JSONObject().put("claim_id", item.claimId).put("reason", item.reason))
            }
        })
        .toString()

    private fun JSONObject.requireOutputItems(key: String): List<EditorialOutputItem> {
        val array = requireArray(key)
        if (array.length() > MAX_ITEMS) throw EditorialContractException("'$key' has too many items.")
        return (0 until array.length()).map { index ->
            val item = array.optJSONObject(index)
                ?: throw EditorialContractException("'$key[$index]' must be an object.")
            item.requireExactKeys(outputKeys, "$key[$index]")
            EditorialOutputItem(
                text = item.requireText("text", allowBlank = false),
                sourceClaimIds = item.requireStringList("source_claim_ids", allowEmpty = false),
            )
        }
    }

    private fun JSONObject.requireDiscards(key: String): List<EditorialDiscard> {
        val array = requireArray(key)
        if (array.length() > MAX_ITEMS) throw EditorialContractException("'$key' has too many items.")
        return (0 until array.length()).map { index ->
            val item = array.optJSONObject(index)
                ?: throw EditorialContractException("'$key[$index]' must be an object.")
            item.requireExactKeys(discardKeys, "$key[$index]")
            EditorialDiscard(
                claimId = item.requireText("claim_id", allowBlank = false),
                reason = item.requireText("reason", allowBlank = false),
            )
        }
    }

    private fun JSONObject.requireArray(key: String): JSONArray = optJSONArray(key)
        ?: throw EditorialContractException("'$key' must be an array.")

    private fun JSONObject.requireStringList(key: String, allowEmpty: Boolean = true): List<String> {
        val array = requireArray(key)
        if (array.length() > MAX_ITEMS) throw EditorialContractException("'$key' has too many ids.")
        if (!allowEmpty && array.length() == 0) throw EditorialContractException("'$key' cannot be empty.")
        return (0 until array.length()).map { index ->
            val value = array.optString(index, null)
                ?: throw EditorialContractException("'$key[$index]' must be a string.")
            if (value.isBlank() || value.length > MAX_TEXT_LENGTH) {
                throw EditorialContractException("'$key[$index]' is empty or too long.")
            }
            value
        }
    }

    private fun JSONObject.requireText(key: String, allowBlank: Boolean): String {
        if (!has(key) || isNull(key) || get(key) !is String) {
            throw EditorialContractException("'$key' must be a string.")
        }
        val value = getString(key)
        if ((!allowBlank && value.isBlank()) || value.length > MAX_TEXT_LENGTH) {
            throw EditorialContractException("'$key' is empty or too long.")
        }
        return value
    }

    private fun JSONObject.requireExactKeys(expected: Set<String>, location: String) {
        val actual = keys().asSequence().toSet()
        if (actual != expected) {
            throw EditorialContractException("'$location' must contain exactly ${expected.sorted()}.")
        }
    }
}
