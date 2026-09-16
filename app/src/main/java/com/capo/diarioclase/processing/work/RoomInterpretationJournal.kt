package com.capo.diarioclase.processing.work

import androidx.room.withTransaction
import com.capo.diarioclase.core.clock.Clock
import com.capo.diarioclase.data.db.ClaimEvidenceEntity
import com.capo.diarioclase.data.db.ClaimSupersessionEntity
import com.capo.diarioclase.data.db.DiarioDatabase
import com.capo.diarioclase.data.db.EvidenceClaimEntity
import com.capo.diarioclase.data.db.InterpretationPacketEntity
import com.capo.diarioclase.data.db.InterpretationRunEntity
import com.capo.diarioclase.data.db.ProviderAttemptEntity
import com.capo.diarioclase.processing.evidence.ClaimCategory
import com.capo.diarioclase.processing.evidence.ClaimOrigin
import com.capo.diarioclase.processing.evidence.ClaimStatus
import com.capo.diarioclase.processing.evidence.InterpretationMode
import com.capo.diarioclase.processing.semantic.InferenceProvider

/**
 * Versiones congeladas de una corrida (Task I7b). Se registran para poder reconstruir con qué
 * app, modelo y contratos se produjo cada ficha. Valores por defecto neutrales para tests.
 */
data class InterpretationRunVersions(
    val appVersion: String = "unknown",
    val whisperVersion: String = "ggml-base",
    val promptVersion: String = "free-ele-v1",
    val schemaVersion: String = "claims-v1",
    val validatorVersion: String = "v1",
)

/**
 * Datos de apertura de una corrida. Reúne la identidad y las versiones congeladas sobre las
 * que se infirió, más el hash del transcripto. Nunca transporta texto de proveedor.
 */
data class InterpretationRunRecord(
    val id: String,
    val sessionId: String,
    val appVersion: String,
    val whisperVersion: String,
    val promptVersion: String,
    val schemaVersion: String,
    val validatorVersion: String,
    val transcriptHash: String,
    val mode: InterpretationMode,
    val provenance: String? = null,
    val startedAtEpochMs: Long,
)

/** Resultado tipado de un intento contra un proveedor. Sin cuerpos, encabezados ni claves. */
data class ProviderAttemptRecord(
    val id: String,
    val runId: String,
    val packetId: String,
    val provider: InferenceProvider,
    val modelId: String,
    val attempt: Int,
    val cacheHit: Boolean,
    val outcome: String,
    val durationMs: Long,
    val startedAtEpochMs: Long,
)

/** Evidencia de un claim ya resuelta a un span de transcripción local real. */
data class PersistedEvidence(
    val transcriptSpanId: String,
    val ordinal: Int,
    val contextual: Boolean,
)

/**
 * Claim persistente completo con su grafo de evidencia y supersesiones. Es la forma que el
 * journal guarda y devuelve; sobrevive al reabrir la sesión.
 */
data class PersistedClaim(
    val id: String,
    val sessionId: String,
    val runId: String,
    val packetId: String,
    val providerClaimKey: String,
    val category: ClaimCategory,
    val value: String,
    val normalizedValue: String,
    val status: ClaimStatus,
    val origin: ClaimOrigin,
    val declaredConfidence: Double,
    val effectiveConfidence: Double,
    val claimOrdinal: Int,
    val blockId: String,
    val startMs: Long,
    val endMs: Long,
    val excerpt: String,
    val active: Boolean = true,
    val evidences: List<PersistedEvidence> = emptyList(),
    val supersedesClaimIds: List<String> = emptyList(),
)

/**
 * Registro transaccional del grafo semántico (Task I4). Consume los contratos de I1 y expone
 * el ciclo de vida de una corrida, sus paquetes e intentos, y el guardado/carga de claims con
 * evidencia y supersesiones. Ningún método acepta cuerpos HTTP, encabezados ni credenciales:
 * solo resultados tipados.
 */
interface InterpretationJournal {
    suspend fun beginRun(run: InterpretationRunRecord)
    suspend fun startPacket(runId: String, packetId: String, ordinal: Int, requestHash: String, requestBytes: Int)
    suspend fun recordAttempt(attempt: ProviderAttemptRecord)
    suspend fun completePacket(runId: String, packetId: String, state: InterpretationPacketState, provider: InferenceProvider?)
    suspend fun failRun(runId: String, failure: InterpretationFailure)
    suspend fun completeRun(runId: String, state: InterpretationRunState)
    suspend fun saveClaims(claims: List<PersistedClaim>)
    suspend fun loadClaims(runId: String): List<PersistedClaim>
}

class RoomInterpretationJournal(
    private val database: DiarioDatabase,
    private val clock: Clock,
) : InterpretationJournal {
    private val dao = database.sessions()

    override suspend fun beginRun(run: InterpretationRunRecord) {
        dao.saveInterpretationRun(
            InterpretationRunEntity(
                id = run.id,
                sessionId = run.sessionId,
                state = InterpretationRunState.RUNNING.name,
                appVersion = run.appVersion,
                whisperVersion = run.whisperVersion,
                promptVersion = run.promptVersion,
                schemaVersion = run.schemaVersion,
                validatorVersion = run.validatorVersion,
                transcriptHash = run.transcriptHash,
                mode = run.mode.name,
                provenance = run.provenance,
                failure = null,
                startedAtEpochMs = run.startedAtEpochMs,
                completedAtEpochMs = null,
            ),
        )
    }

    override suspend fun startPacket(
        runId: String,
        packetId: String,
        ordinal: Int,
        requestHash: String,
        requestBytes: Int,
    ) {
        dao.saveInterpretationPacket(
            InterpretationPacketEntity(
                runId = runId,
                packetId = packetId,
                ordinal = ordinal,
                state = InterpretationPacketState.RUNNING.name,
                requestHash = requestHash,
                requestBytes = requestBytes,
                provider = null,
                startedAtEpochMs = clock.nowEpochMs(),
                completedAtEpochMs = null,
            ),
        )
    }

    override suspend fun recordAttempt(attempt: ProviderAttemptRecord) {
        dao.saveProviderAttempt(
            ProviderAttemptEntity(
                id = attempt.id,
                runId = attempt.runId,
                packetId = attempt.packetId,
                provider = attempt.provider.name,
                modelId = attempt.modelId,
                attempt = attempt.attempt,
                cacheHit = attempt.cacheHit,
                outcome = attempt.outcome,
                durationMs = attempt.durationMs,
                startedAtEpochMs = attempt.startedAtEpochMs,
            ),
        )
    }

    override suspend fun completePacket(
        runId: String,
        packetId: String,
        state: InterpretationPacketState,
        provider: InferenceProvider?,
    ) {
        dao.updateInterpretationPacket(runId, packetId, state.name, provider?.name, clock.nowEpochMs())
    }

    override suspend fun failRun(runId: String, failure: InterpretationFailure) {
        dao.updateInterpretationRun(runId, InterpretationRunState.FAILED.name, failure.name, clock.nowEpochMs())
    }

    override suspend fun completeRun(runId: String, state: InterpretationRunState) {
        dao.updateInterpretationRun(runId, state.name, null, clock.nowEpochMs())
    }

    override suspend fun saveClaims(claims: List<PersistedClaim>) = database.withTransaction {
        claims.forEach { claim ->
            dao.insertClaims(
                listOf(
                    EvidenceClaimEntity(
                        id = claim.id,
                        sessionId = claim.sessionId,
                        category = claim.category.name,
                        value = claim.value,
                        normalizedValue = claim.normalizedValue,
                        status = claim.status.name,
                        confidence = claim.effectiveConfidence,
                        origin = claim.origin.name,
                        blockId = claim.blockId,
                        startMs = claim.startMs,
                        endMs = claim.endMs,
                        excerpt = claim.excerpt,
                        active = claim.active,
                        runId = claim.runId,
                        packetId = claim.packetId,
                        providerClaimKey = claim.providerClaimKey,
                        declaredConfidence = claim.declaredConfidence,
                        effectiveConfidence = claim.effectiveConfidence,
                        claimOrdinal = claim.claimOrdinal,
                    ),
                ),
            )
            dao.deleteClaimEvidence(claim.id)
            if (claim.evidences.isNotEmpty()) {
                dao.insertClaimEvidence(
                    claim.evidences.map { evidence ->
                        ClaimEvidenceEntity(claim.id, evidence.transcriptSpanId, evidence.ordinal, evidence.contextual)
                    },
                )
            }
            dao.deleteClaimSupersessions(claim.id)
            if (claim.supersedesClaimIds.isNotEmpty()) {
                dao.insertClaimSupersessions(
                    claim.supersedesClaimIds.map { old -> ClaimSupersessionEntity(claim.id, old) },
                )
            }
        }
    }

    override suspend fun loadClaims(runId: String): List<PersistedClaim> = database.withTransaction {
        val rows = dao.claimsForRun(runId)
        val claims = ArrayList<PersistedClaim>(rows.size)
        for (row in rows) {
            val evidences = dao.claimEvidence(row.id).map { evidence ->
                PersistedEvidence(evidence.transcriptSpanId, evidence.ordinal, evidence.contextual)
            }
            val supersedes = dao.claimSupersessions(row.id)
            claims += PersistedClaim(
                id = row.id,
                sessionId = row.sessionId,
                runId = row.runId ?: runId,
                packetId = row.packetId.orEmpty(),
                providerClaimKey = row.providerClaimKey.ifEmpty { row.id },
                category = ClaimCategory.valueOf(row.category),
                value = row.value,
                normalizedValue = row.normalizedValue,
                status = ClaimStatus.valueOf(row.status),
                origin = ClaimOrigin.valueOf(row.origin),
                declaredConfidence = row.declaredConfidence,
                effectiveConfidence = row.effectiveConfidence,
                claimOrdinal = row.claimOrdinal,
                blockId = row.blockId,
                startMs = row.startMs,
                endMs = row.endMs,
                excerpt = row.excerpt,
                active = row.active,
                evidences = evidences,
                supersedesClaimIds = supersedes,
            )
        }
        claims
    }
}
