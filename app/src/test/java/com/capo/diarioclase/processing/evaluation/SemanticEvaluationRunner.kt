package com.capo.diarioclase.processing.evaluation

import com.capo.diarioclase.processing.evidence.ClaimCategory
import com.capo.diarioclase.processing.evidence.ClaimStatus
import com.capo.diarioclase.processing.evidence.DiaryDraft
import com.capo.diarioclase.processing.evidence.DiaryFieldMaterializer
import com.capo.diarioclase.processing.evidence.InterpretationMode
import com.capo.diarioclase.processing.evidence.InterpretationProjector
import com.capo.diarioclase.processing.evidence.PagesAndExercisesComposer
import com.capo.diarioclase.processing.evidence.RawClaim
import com.capo.diarioclase.processing.semantic.DiaryField
import com.capo.diarioclase.processing.semantic.EphemeralCredential
import com.capo.diarioclase.processing.semantic.FallbackClaimExtractor
import com.capo.diarioclase.processing.semantic.FreeInferenceRouter
import com.capo.diarioclase.processing.semantic.InferenceProvider
import com.capo.diarioclase.processing.semantic.InferenceProviderClient
import com.capo.diarioclase.processing.semantic.InterpretationRequest
import com.capo.diarioclase.processing.semantic.ProviderClaimsCodec
import com.capo.diarioclase.processing.semantic.ProviderModel
import com.capo.diarioclase.processing.semantic.ProviderOutcome
import com.capo.diarioclase.processing.semantic.ProviderRetryPolicy
import com.capo.diarioclase.processing.semantic.ProviderSemanticClaim
import com.capo.diarioclase.processing.semantic.PublicTranscriptSpan
import com.capo.diarioclase.processing.semantic.RoutedPacketOutcome
import com.capo.diarioclase.processing.semantic.SemanticClaimReducer
import com.capo.diarioclase.processing.semantic.SemanticResponseValidator

/** Un paquete de evaluación: los spans públicos y la respuesta “gold” del proveedor. */
data class EvalPacket(
    val packetId: String,
    val spans: List<PublicTranscriptSpan>,
    val gold: List<ProviderSemanticClaim>,
)

/** Reporte determinista de una evaluación. Vacío en todos sus campos significa aprobado. */
data class EvaluationReport(
    val validationFailures: List<String>,
    val missingRequired: List<ClaimExpectation>,
    val prohibitedPresent: List<ClaimExpectation>,
    val wrongFields: List<String>,
    val invalidEvidence: List<String>,
) {
    val ok: Boolean
        get() = validationFailures.isEmpty() && missingRequired.isEmpty() &&
            prohibitedPresent.isEmpty() && wrongFields.isEmpty() && invalidEvidence.isEmpty()

    fun describe(): String = buildString {
        if (validationFailures.isNotEmpty()) appendLine("Validación/ruteo: $validationFailures")
        if (missingRequired.isNotEmpty()) appendLine("Faltan claims requeridos: $missingRequired")
        if (prohibitedPresent.isNotEmpty()) appendLine("Claims prohibidos presentes: $prohibitedPresent")
        if (wrongFields.isNotEmpty()) appendLine("Campos incorrectos: $wrongFields")
        if (invalidEvidence.isNotEmpty()) appendLine("Evidencia sin identidad de span: $invalidEvidence")
    }.trim()
}

/**
 * Gate de evaluación de integridad offline (Task I6). Corre el pipeline real —router con la
 * identidad global de Task I5, validador, reducer y materializer— sobre transcripciones
 * sintéticas y contrasta el resultado con expectativas tipadas. No llama a proveedores
 * reales: cada paquete entrega su respuesta “gold” a través de un cliente en memoria.
 */
class SemanticEvaluationRunner(
    private val mode: InterpretationMode = InterpretationMode.CONSERVATIVE,
    private val validator: SemanticResponseValidator = SemanticResponseValidator(),
    private val reducer: SemanticClaimReducer = SemanticClaimReducer(),
    private val materializer: DiaryFieldMaterializer = DiaryFieldMaterializer(
        InterpretationProjector(),
        PagesAndExercisesComposer(),
    ),
) {

    suspend fun evaluate(packets: List<EvalPacket>, expectation: SemanticExpectation): EvaluationReport {
        val disabled = mutableSetOf<InferenceProvider>()
        val strikes = mutableMapOf<InferenceProvider, Int>()
        val raw = mutableListOf<RawClaim>()
        val validationFailures = mutableListOf<String>()

        packets.forEach { packet ->
            val json = ProviderClaimsCodec.encode(packet.gold)
            val client = InferenceProviderClient { _, _ ->
                ProviderOutcome.Success(InferenceProvider.GEMINI, "eval-model", json)
            }
            val router = FreeInferenceRouter(
                clients = mapOf(InferenceProvider.GEMINI to client),
                validator = validator,
                fallback = FallbackClaimExtractor(),
                retryPolicy = ProviderRetryPolicy(retryDelayMs = 0),
                cache = null,
                credentialFor = { EphemeralCredential("k") },
                onDelay = {},
                nowEpochMs = { 1 },
            )
            val request = InterpretationRequest(packet.packetId, "free-ele-v1", "claims-v1", packet.spans)
            val sources = packet.spans.associate { it.publicId to "t-${it.publicId}" }
            when (
                val outcome = router.route(
                    "eval",
                    request,
                    listOf(ProviderModel(InferenceProvider.GEMINI, "eval-model")),
                    disabled,
                    "eval-run",
                    sources,
                    transientStrikes = strikes,
                )
            ) {
                is RoutedPacketOutcome.Remote -> raw += outcome.claims
                is RoutedPacketOutcome.Local ->
                    validationFailures += "el paquete ${packet.packetId} cayó a local: ${outcome.failures}"
            }
        }

        val claims = reducer.reduce(raw)
        val draft = materializer.materialize("eval", mode, claims)
        val active = claims.filter { it.active }
            .map { ClaimExpectation(it.category, it.normalizedValue, it.status) }
            .toSet()

        return EvaluationReport(
            validationFailures = validationFailures,
            missingRequired = (expectation.required - active).toList(),
            prohibitedPresent = expectation.prohibited.filter { it in active },
            wrongFields = expectation.expectedFields
                .filterNot { (field, value) -> fieldValue(draft, field) == value }
                .map { (field, value) -> "$field esperaba \"$value\" pero fue \"${fieldValue(draft, field)}\"" },
            invalidEvidence = claims
                .filter { it.evidences.isNotEmpty() && it.transcriptSpanIds.isEmpty() }
                .map { "claim ${it.id} (${it.category}/${it.normalizedValue}) sin transcriptSpanIds" },
        )
    }

    suspend fun assertMatches(packets: List<EvalPacket>, expectation: SemanticExpectation) {
        val report = evaluate(packets, expectation)
        if (!report.ok) throw AssertionError("Evaluación de integridad falló:\n${report.describe()}")
    }

    private fun fieldValue(draft: DiaryDraft, field: DiaryField): String = when (field) {
        DiaryField.TOPICS -> draft.topics
        DiaryField.ACTIVITIES -> draft.activities
        DiaryField.PAGES -> draft.pages
        DiaryField.EXERCISES -> draft.exercises
        DiaryField.HOMEWORK -> draft.homework
    }
}
