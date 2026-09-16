package com.capo.diarioclase.processing.semantic

/**
 * Transporte HTTP falso para las pruebas de los adaptadores (Task 4). No abre red: registra
 * cada request y devuelve un resultado fijo, o lanza una excepción para simular fallas de red.
 */
class FakeInferenceHttpTransport(
    private val result: HttpTransportResult? = null,
    private val error: Throwable? = null,
    private val getResult: HttpTransportResult? = null,
    private val getError: Throwable? = null,
) : InferenceHttpTransport {

    data class RecordedRequest(
        val url: String,
        val headers: Map<String, String>,
        val body: String,
    )

    val requests = mutableListOf<RecordedRequest>()
    val lastRequest: RecordedRequest? get() = requests.lastOrNull()

    /** Requests GET registrados (preflights de solo lectura, Q2). */
    val getRequests = mutableListOf<RecordedRequest>()
    val lastGet: RecordedRequest? get() = getRequests.lastOrNull()

    override suspend fun request(
        url: String,
        headers: Map<String, String>,
        body: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): HttpTransportResult {
        requests += RecordedRequest(url, headers, body)
        error?.let { throw it }
        return result ?: error("FakeInferenceHttpTransport sin resultado configurado")
    }

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): HttpTransportResult {
        getRequests += RecordedRequest(url, headers, "")
        getError?.let { throw it }
        return getResult ?: error("FakeInferenceHttpTransport sin resultado GET configurado")
    }

    companion object {
        fun ok(status: Int, body: String, headers: Map<String, String> = emptyMap()) =
            FakeInferenceHttpTransport(result = HttpTransportResult(status, body, headers))

        fun throwing(error: Throwable) = FakeInferenceHttpTransport(error = error)

        fun okGet(status: Int, body: String, headers: Map<String, String> = emptyMap()) =
            FakeInferenceHttpTransport(getResult = HttpTransportResult(status, body, headers))

        fun throwingGet(error: Throwable) = FakeInferenceHttpTransport(getError = error)
    }
}
