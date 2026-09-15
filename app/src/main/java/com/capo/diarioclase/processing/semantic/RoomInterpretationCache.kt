package com.capo.diarioclase.processing.semantic

import com.capo.diarioclase.data.db.InterpretationCacheEntity
import com.capo.diarioclase.data.db.SessionDao
import java.security.MessageDigest

/** Respuesta reutilizada desde la caché, ya revalidada estructuralmente. */
data class CachedInterpretation(
    val provider: InferenceProvider,
    val modelId: String,
    val validatedJson: String,
)

/**
 * Caché por paquete y proveedor (Task 6).
 *
 * La clave combina sesión, paquete, proveedor, modelo, versiones de prompt/esquema y la
 * versión del validador: cambiar el texto (que cambia `packetId`), el prompt, el esquema o
 * el validador invalida la entrada. Toda consulta queda acotada a la sesión y el JSON se
 * revalida estructuralmente al leer. La búsqueda es transversal: una respuesta previa de un
 * proveedor de mayor prioridad evita consultar a los siguientes.
 */
class RoomInterpretationCache(
    private val dao: SessionDao,
    private val validatorVersion: String = VALIDATOR_VERSION,
) {

    suspend fun store(
        sessionId: String,
        packet: InterpretationRequest,
        provider: InferenceProvider,
        modelId: String,
        validatedJson: String,
        nowEpochMs: Long,
    ) {
        dao.saveInterpretationCache(
            InterpretationCacheEntity(
                cacheId = cacheId(sessionId, packet, provider, modelId),
                packetId = packet.packetId,
                sessionId = sessionId,
                provider = provider.name,
                modelId = modelId,
                promptVersion = packet.promptVersion,
                schemaVersion = packet.schemaVersion,
                validatedJson = validatedJson,
                createdAtEpochMs = nowEpochMs,
            ),
        )
    }

    /**
     * Busca cualquier respuesta válida del paquete para los proveedores habilitados, en
     * orden. Devuelve la primera que exista y cuyo JSON siga siendo estructuralmente válido.
     */
    suspend fun find(
        sessionId: String,
        packet: InterpretationRequest,
        providers: List<ProviderModel>,
    ): CachedInterpretation? {
        val rows = dao.interpretationCache(sessionId, packet.packetId)
        for ((provider, modelId) in providers) {
            val id = cacheId(sessionId, packet, provider, modelId)
            val row = rows.firstOrNull {
                it.cacheId == id &&
                    it.promptVersion == packet.promptVersion &&
                    it.schemaVersion == packet.schemaVersion
            } ?: continue
            val stillValid = runCatching { ProviderClaimsCodec.decode(row.validatedJson) }.isSuccess
            if (stillValid) return CachedInterpretation(provider, modelId, row.validatedJson)
        }
        return null
    }

    private fun cacheId(
        sessionId: String,
        packet: InterpretationRequest,
        provider: InferenceProvider,
        modelId: String,
    ): String {
        val material = listOf(
            sessionId,
            packet.packetId,
            provider.name,
            modelId,
            packet.promptVersion,
            packet.schemaVersion,
            validatorVersion,
        ).joinToString("|")
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    companion object {
        const val VALIDATOR_VERSION = "claims-validator-v1"
    }
}

/** Proveedor y su modelo del catálogo, en el orden efectivo de la cadena. */
data class ProviderModel(val provider: InferenceProvider, val modelId: String)
