package com.capo.diarioclase.processing.semantic

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Transporte HTTP mínimo para la inferencia remota (Task 4).
 *
 * Los adaptadores de proveedor dependen de esta interfaz para poder inyectar un
 * transporte falso en las pruebas (nunca red real en CI). La implementación real
 * ([DefaultInferenceHttpTransport]) solo acepta HTTPS y hosts del catálogo, desactiva
 * redirects y acota el tamaño de la respuesta.
 */
interface InferenceHttpTransport {
    suspend fun request(
        url: String,
        headers: Map<String, String>,
        body: String,
        // Un modelo Flash gratuito responde en pocos segundos. Timeouts cortos evitan que
        // una llamada colgada congele la generación de la ficha (antes: 30 s / 90 s).
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 30_000,
    ): HttpTransportResult

    /**
     * GET acotado y sin cuerpo para preflights de solo lectura (Q2): la inspección de la clave
     * de OpenRouter (`GET /api/v1/key`) para detectar capacidad de gasto antes de habilitar el
     * proveedor. Mantiene la misma allowlist de hosts y no sigue redirects. Nunca se usa para
     * enviar transcripción.
     */
    suspend fun get(
        url: String,
        headers: Map<String, String>,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 10_000,
    ): HttpTransportResult =
        throw UnsupportedOperationException("GET no soportado por este transporte.")
}

data class HttpTransportResult(
    val status: Int,
    val body: String,
    val headers: Map<String, String>,
)

/** Se lanza cuando el host o el esquema no están permitidos, o la respuesta excede el límite. */
class TransportPolicyException(message: String) : Exception(message)

class DefaultInferenceHttpTransport(
    private val maxResponseBytes: Int = 512 * 1024,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val openConnection: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
) : InferenceHttpTransport {

    override suspend fun request(
        url: String,
        headers: Map<String, String>,
        body: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): HttpTransportResult {
        val parsed = URL(url)
        if (!parsed.protocol.equals("https", ignoreCase = true)) {
            throw TransportPolicyException("Solo se permite HTTPS.")
        }
        if (parsed.host !in ALLOWED_HOSTS) {
            throw TransportPolicyException("Host no permitido.")
        }

        return withContext(ioDispatcher) {
            val connection = openConnection(parsed).apply {
                requestMethod = "POST"
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }

            // La E/S bloqueante no responde por sí sola a la cancelación de la corrutina; al
            // cancelarse el paquete o la sesión (Task I5), desconectar la conexión desbloquea
            // el read pendiente para que la ficha no quede colgada esperando al proveedor.
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { runCatching { connection.disconnect() } }
                try {
                    connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    val status = connection.responseCode
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                        readBounded(reader)
                    }.orEmpty()
                    val responseHeaders = buildMap {
                        connection.headerFields.forEach { (name, values) ->
                            if (name != null) put(name, values.firstOrNull().orEmpty())
                        }
                    }
                    if (continuation.isActive) {
                        continuation.resume(HttpTransportResult(status, responseBody, responseHeaders))
                    }
                } catch (t: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(t)
                } finally {
                    runCatching { connection.disconnect() }
                }
            }
        }
    }

    override suspend fun get(
        url: String,
        headers: Map<String, String>,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): HttpTransportResult {
        val parsed = URL(url)
        if (!parsed.protocol.equals("https", ignoreCase = true)) {
            throw TransportPolicyException("Solo se permite HTTPS.")
        }
        if (parsed.host !in ALLOWED_HOSTS) {
            throw TransportPolicyException("Host no permitido.")
        }

        return withContext(ioDispatcher) {
            val connection = openConnection(parsed).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Accept", "application/json")
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }

            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { runCatching { connection.disconnect() } }
                try {
                    val status = connection.responseCode
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    val responseBody = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                        readBounded(reader)
                    }.orEmpty()
                    val responseHeaders = buildMap {
                        connection.headerFields.forEach { (name, values) ->
                            if (name != null) put(name, values.firstOrNull().orEmpty())
                        }
                    }
                    if (continuation.isActive) {
                        continuation.resume(HttpTransportResult(status, responseBody, responseHeaders))
                    }
                } catch (t: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(t)
                } finally {
                    runCatching { connection.disconnect() }
                }
            }
        }
    }

    private fun readBounded(reader: BufferedReader): String {
        val builder = StringBuilder()
        val buffer = CharArray(8 * 1024)
        var total = 0
        while (true) {
            val read = reader.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxResponseBytes) {
                throw TransportPolicyException("Respuesta demasiado grande.")
            }
            builder.append(buffer, 0, read)
        }
        return builder.toString()
    }

    companion object {
        val ALLOWED_HOSTS = setOf(
            "generativelanguage.googleapis.com",
            "api.groq.com",
            "openrouter.ai",
        )
    }
}
