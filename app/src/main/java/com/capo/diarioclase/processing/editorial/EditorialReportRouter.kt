package com.capo.diarioclase.processing.editorial

import com.capo.diarioclase.processing.semantic.EphemeralCredential
import com.capo.diarioclase.processing.semantic.InferenceProvider
import com.capo.diarioclase.processing.semantic.ProviderFailure
import com.capo.diarioclase.processing.semantic.ProviderModel
import com.capo.diarioclase.processing.semantic.ProviderOutcome
import com.capo.diarioclase.processing.semantic.ProviderRetryPolicy
import com.capo.diarioclase.processing.semantic.RetryDecision
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

sealed interface EditorialRoute {
    data class Ready(
        val report: EditorialReport,
        val rawJson: String,
        val provider: InferenceProvider,
        val modelId: String,
    ) : EditorialRoute

    data class Unavailable(val failures: List<ProviderFailure>) : EditorialRoute
}

class EditorialReportRouter(
    private val clients: Map<InferenceProvider, EditorialProviderClient>,
    private val validator: EditorialReportValidator,
    private val credentialFor: suspend (InferenceProvider) -> EphemeralCredential?,
    private val retryPolicy: ProviderRetryPolicy = ProviderRetryPolicy(),
    private val onDelay: suspend (Long) -> Unit = { delay(it) },
    private val timeoutMs: Long = 60_000,
) {
    suspend fun route(
        request: EditorialReportRequest,
        providers: List<ProviderModel>,
    ): EditorialRoute = withTimeoutOrNull(timeoutMs) {
        routeWithinDeadline(request, providers)
    } ?: EditorialRoute.Unavailable(listOf(ProviderFailure.TIMEOUT))

    private suspend fun routeWithinDeadline(
        request: EditorialReportRequest,
        providers: List<ProviderModel>,
    ): EditorialRoute {
        val failures = mutableListOf<ProviderFailure>()
        for (providerModel in providers) {
            val client = clients[providerModel.provider]
            if (client == null) {
                failures += ProviderFailure.NOT_CONFIGURED
                continue
            }
            val credential = credentialFor(providerModel.provider)
            if (credential == null) {
                failures += ProviderFailure.NOT_CONFIGURED
                continue
            }

            var requestsUsed = 0
            var repair: EditorialRepair? = null
            while (requestsUsed < retryPolicy.maxRequestsPerProvider) {
                val outcome = client.generate(request, credential, repair)
                requestsUsed++
                when (outcome) {
                    is ProviderOutcome.Success -> when (val validation = validator.validate(outcome.rawJson, request)) {
                        is EditorialValidationOutcome.Valid -> return EditorialRoute.Ready(
                            report = validation.report,
                            rawJson = outcome.rawJson,
                            provider = providerModel.provider,
                            modelId = outcome.modelId,
                        )
                        is EditorialValidationOutcome.Invalid -> {
                            failures += ProviderFailure.INVALID_RESPONSE
                            if (requestsUsed >= retryPolicy.maxRequestsPerProvider) break
                            repair = EditorialRepair(validation.issues, validation.missingClaimIds)
                        }
                    }

                    is ProviderOutcome.Failure -> {
                        failures += outcome.code
                        when (retryPolicy.decide(outcome.code, requestsUsed)) {
                            RetryDecision.STOP_ALL -> return EditorialRoute.Unavailable(failures.toList())
                            RetryDecision.STOP_PROVIDER -> break
                            RetryDecision.RETRY -> {
                                onDelay(outcome.retryAfterMs ?: retryPolicy.retryDelayMs)
                                repair = null
                            }
                            RetryDecision.CORRECT -> repair = EditorialRepair(
                                setOf(EditorialIssue.MALFORMED_CONTRACT),
                                emptySet(),
                            )
                        }
                    }
                }
            }
        }
        return EditorialRoute.Unavailable(failures.toList())
    }
}
