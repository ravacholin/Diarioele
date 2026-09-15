package com.capo.diarioclase.processing.semantic

import java.security.MessageDigest

/** Builds a deterministic, globally scoped id without exposing provider text. */
object ClaimIdentity {
    fun id(
        runId: String,
        packetId: String,
        provider: InferenceProvider,
        providerClaimKey: String,
    ): String {
        val canonical = listOf(runId, packetId, provider.name, providerClaimKey)
            .joinToString("|") { value -> "${value.length}:$value" }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
