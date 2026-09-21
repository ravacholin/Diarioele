package com.capo.diarioclase.processing.editorial

import com.capo.diarioclase.data.db.EditorialReportEntity
import com.capo.diarioclase.data.db.SessionId
import com.capo.diarioclase.processing.evidence.EvidenceClaim
import com.capo.diarioclase.processing.evidence.InterpretationMode
import com.capo.diarioclase.processing.semantic.ProviderFailure
import com.capo.diarioclase.processing.semantic.ProviderModel

sealed interface EditorialGenerationOutcome {
    data class Ready(
        val entity: EditorialReportEntity,
        val cacheHit: Boolean,
    ) : EditorialGenerationOutcome

    data class Unavailable(val failures: List<ProviderFailure>) : EditorialGenerationOutcome
    data object Obsolete : EditorialGenerationOutcome
}

interface EditorialReportGenerator {
    suspend fun generate(
        sessionId: SessionId,
        mode: InterpretationMode,
        claims: List<EvidenceClaim>,
    ): EditorialGenerationOutcome
}

class EditorialReportService(
    private val builder: EditorialReportPacketBuilder,
    private val router: EditorialReportRouter,
    private val store: EditorialReportStore,
    private val enabledProviders: suspend () -> List<ProviderModel>,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) : EditorialReportGenerator {
    override suspend fun generate(
        sessionId: SessionId,
        mode: InterpretationMode,
        claims: List<EvidenceClaim>,
    ): EditorialGenerationOutcome {
        val request = builder.build(sessionId.value, mode, claims)
        store.readyFor(sessionId.value, request.inputHash)?.let { cached ->
            return EditorialGenerationOutcome.Ready(cached, cacheHit = true)
        }

        store.begin(sessionId.value, request.inputHash)
        return when (val route = router.route(request, enabledProviders())) {
            is EditorialRoute.Ready -> {
                val candidate = EditorialReportEntity(
                    sessionId = sessionId.value,
                    inputHash = request.inputHash,
                    state = EditorialReportState.READY.name,
                    rawJson = route.rawJson,
                    summary = route.report.summary,
                    materialText = route.report.material.joinToString("\n") { it.text },
                    homeworkText = route.report.homework.joinToString("\n") { it.text },
                    provider = route.provider.name,
                    modelId = route.modelId,
                    promptVersion = request.promptVersion,
                    schemaVersion = request.schemaVersion,
                    validatorVersion = EditorialReportValidator.VERSION,
                    failure = null,
                    updatedAtEpochMs = nowEpochMs(),
                )
                if (!store.saveReadyIfCurrent(sessionId.value, request.inputHash, candidate)) {
                    EditorialGenerationOutcome.Obsolete
                } else {
                    val persisted = store.readyFor(sessionId.value, request.inputHash)
                        ?: return EditorialGenerationOutcome.Obsolete
                    EditorialGenerationOutcome.Ready(persisted, cacheHit = false)
                }
            }

            is EditorialRoute.Unavailable -> {
                store.markFailedIfCurrent(
                    sessionId.value,
                    request.inputHash,
                    route.failures.joinToString(",") { it.name }.ifBlank { ProviderFailure.INTERNAL.name },
                )
                EditorialGenerationOutcome.Unavailable(route.failures)
            }
        }
    }
}
