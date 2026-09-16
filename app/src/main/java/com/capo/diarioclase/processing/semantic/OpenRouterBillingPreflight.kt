package com.capo.diarioclase.processing.semantic

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Preflight de facturación de OpenRouter (Q2).
 *
 * Antes de habilitar OpenRouter se consulta `GET /api/v1/key` con la clave, a través de un GET
 * acotado y allowlisted del transporte. La clave se considera segura solo si es de free tier y
 * tiene un límite acotado; si reporta gasto ilimitado (límite nulo) o capacidad de gasto
 * (no free tier), se devuelve [Result.BILLING_WARNING] y el proveedor queda deshabilitado.
 *
 * Un preflight seguro se cachea [cacheTtlMs] (10 minutos por defecto) para no repetir la
 * consulta en cada uso; la acción “Probar conexión” siempre fuerza un refresco. Nunca envía
 * transcripción ni persiste la clave.
 */
class OpenRouterBillingPreflight(
    private val transport: InferenceHttpTransport,
    private val endpoint: String = "https://openrouter.ai/api/v1/key",
    private val cacheTtlMs: Long = 10 * 60_000L,
    private val nowEpochMs: () -> Long = { System.currentTimeMillis() },
) {

    enum class Result { SAFE, BILLING_WARNING, INVALID_KEY, NO_NETWORK, UNAVAILABLE }

    private data class Cached(val result: Result, val atEpochMs: Long)

    private var cached: Cached? = null

    suspend fun inspect(credential: EphemeralCredential, forceRefresh: Boolean): Result {
        if (!forceRefresh) {
            cached?.let { hit ->
                if (hit.result == Result.SAFE && nowEpochMs() - hit.atEpochMs < cacheTtlMs) {
                    return hit.result
                }
            }
        }
        val result = runInspection(credential)
        // Solo se cachea un preflight seguro; una advertencia o un error se re-chequean siempre.
        cached = if (result == Result.SAFE) Cached(result, nowEpochMs()) else null
        return result
    }

    private suspend fun runInspection(credential: EphemeralCredential): Result {
        val headers = mapOf("Authorization" to "Bearer ${credential.value}")
        val response = try {
            transport.get(endpoint, headers)
        } catch (e: SocketTimeoutException) {
            return Result.UNAVAILABLE
        } catch (e: UnknownHostException) {
            return Result.NO_NETWORK
        } catch (e: ConnectException) {
            return Result.NO_NETWORK
        } catch (e: TransportPolicyException) {
            return Result.UNAVAILABLE
        } catch (e: IOException) {
            return Result.UNAVAILABLE
        }
        if (response.status == 401 || response.status == 403) return Result.INVALID_KEY
        if (response.status !in 200..299) return Result.UNAVAILABLE
        return classify(response.body)
    }

    private fun classify(body: String): Result {
        val data = runCatching {
            INFERENCE_JSON.parseToJsonElement(body).jsonObject["data"]?.jsonObject
        }.getOrNull() ?: return Result.UNAVAILABLE

        val limitElement = data["limit"]
        val unlimited = limitElement == null || limitElement is JsonNull
        val isFreeTier = data["is_free_tier"]?.jsonPrimitive?.booleanOrNull ?: false

        // Seguro solo si es free tier y con un límite acotado. Cualquier señal de gasto
        // (ilimitado o cuenta con créditos) mantiene el proveedor deshabilitado.
        return if (unlimited || !isFreeTier) Result.BILLING_WARNING else Result.SAFE
    }
}
