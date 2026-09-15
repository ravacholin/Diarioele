package com.capo.diarioclase.processing.semantic

import kotlinx.coroutines.Dispatchers
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
        connectTimeoutMs: Int = 30_000,
        readTimeoutMs: Int = 90_000,
    ): HttpTransportResult
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
) : InferenceHttpTransport {

    override suspend fun request(
        url: String,
        headers: Map<String, String>,
        body: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): HttpTransportResult = withContext(Dispatchers.IO) {
        val parsed = URL(url)
        if (!parsed.protocol.equals("https", ignoreCase = true)) {
            throw TransportPolicyException("Solo se permite HTTPS.")
        }
        if (parsed.host !in ALLOWED_HOSTS) {
            throw TransportPolicyException("Host no permitido.")
        }

        val connection = (parsed.openConnection() as HttpsURLConnection).apply {
            requestMethod = "POST"
            instanceFollowRedirects = false
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }

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
            HttpTransportResult(status, responseBody, responseHeaders)
        } finally {
            connection.disconnect()
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
