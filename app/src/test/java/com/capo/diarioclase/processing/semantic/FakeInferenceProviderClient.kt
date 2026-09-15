package com.capo.diarioclase.processing.semantic

/**
 * Cliente de proveedor falso y configurable para las pruebas de Fase 5.
 *
 * No usa red ni credenciales reales. Recibe un [InferenceProvider] y una cola de
 * [ProviderOutcome], que devuelve en orden; registra cada [InterpretationRequest]
 * recibido y la cantidad de intentos. Deliberadamente **no** guarda el valor de la
 * credencial: solo cuenta cuántas veces se le pasó una, para poder afirmar que el
 * router la entrega por llamada sin filtrarla.
 *
 * Cuando la cola se agota, repite el último outcome (o, si nunca hubo ninguno, devuelve
 * [ProviderFailure.INTERNAL]). Así una prueba puede fijar “siempre 429” con un solo
 * elemento.
 */
class FakeInferenceProviderClient(
    val provider: InferenceProvider,
    outcomes: List<ProviderOutcome>,
    private val modelId: String = "fake-model",
) : InferenceProviderClient {

    constructor(
        provider: InferenceProvider,
        outcome: ProviderOutcome,
        modelId: String = "fake-model",
    ) : this(provider, listOf(outcome), modelId)

    private val queue = ArrayDeque(outcomes)
    private var lastServed: ProviderOutcome? = null

    /** Requests recibidos, en orden. */
    val requests = mutableListOf<InterpretationRequest>()

    /** Cantidad de veces que se recibió una credencial (nunca su valor). */
    var credentialsSeen = 0
        private set

    val attempts: Int get() = requests.size

    override suspend fun infer(
        request: InterpretationRequest,
        credential: EphemeralCredential,
    ): ProviderOutcome {
        requests += request
        credentialsSeen++
        val outcome = queue.removeFirstOrNull()
            ?: lastServed
            ?: ProviderOutcome.Failure(provider, ProviderFailure.INTERNAL, retryable = false)
        lastServed = outcome
        return outcome
    }

    companion object {
        fun success(
            provider: InferenceProvider,
            rawJson: String,
            modelId: String = "fake-model",
        ): ProviderOutcome = ProviderOutcome.Success(provider, modelId, rawJson)

        /** JSON estructuralmente inválido: se entrega como “éxito” para que lo rechace el validador. */
        fun invalidJson(
            provider: InferenceProvider,
            rawJson: String = "{ not json",
            modelId: String = "fake-model",
        ): ProviderOutcome = ProviderOutcome.Success(provider, modelId, rawJson)

        fun authenticationFailure(provider: InferenceProvider, httpStatus: Int = 401): ProviderOutcome =
            ProviderOutcome.Failure(provider, ProviderFailure.AUTHENTICATION, retryable = false, httpStatus = httpStatus)

        fun quotaFailure(provider: InferenceProvider, retryAfterMs: Long? = null): ProviderOutcome =
            ProviderOutcome.Failure(provider, ProviderFailure.QUOTA, retryable = false, httpStatus = 429, retryAfterMs = retryAfterMs)

        fun serverUnavailable(provider: InferenceProvider, httpStatus: Int = 503): ProviderOutcome =
            ProviderOutcome.Failure(provider, ProviderFailure.SERVER_UNAVAILABLE, retryable = true, httpStatus = httpStatus)

        fun timeout(provider: InferenceProvider): ProviderOutcome =
            ProviderOutcome.Failure(provider, ProviderFailure.TIMEOUT, retryable = true)

        fun noNetwork(provider: InferenceProvider): ProviderOutcome =
            ProviderOutcome.Failure(provider, ProviderFailure.NO_NETWORK, retryable = false)
    }
}
