package com.capo.diarioclase.processing.semantic

import com.capo.diarioclase.processing.evidence.ClaimCategory
import com.capo.diarioclase.processing.evidence.ClaimStatus
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Contrato común de la Fase 5, congelado por Task 1.
 *
 * Reúne los modelos, enums, errores y políticas que comparten los adaptadores de
 * proveedor (Task 4), el validador (Task 5), la caché (Task 6) y el router (Task 7).
 * Nada de este archivo consulta la red ni conoce credenciales reales: solo describe
 * la forma de las entradas y salidas y las invariantes que el resto de las tareas no
 * puede redefinir.
 *
 * Reglas congeladas:
 * - Proveedores remotos: únicamente [InferenceProvider]. No se agregan variantes.
 * - Orden de spans: bloque, ordinal de segmento de audio y ordinal de span, en ese
 *   orden. `startMs` nunca reordena por sí solo (ver [SpanOrder]).
 * - Evidencia múltiple: cada claim referencia una lista de spans ([ProviderSemanticClaim.evidenceSpanIds]).
 * - Supersesiones: cada claim puede reemplazar otras claves de claim ([ProviderSemanticClaim.supersedesClaimKeys]).
 * - Política estado→campo: ver [StatusFieldPolicy].
 */

/** Proveedores remotos gratuitos admitidos. Catálogo cerrado. */
enum class InferenceProvider { GEMINI, GROQ, OPENROUTER }

/**
 * Perfil de un proveedor en el orden de la cadena. El `modelId` proviene del catálogo
 * gratuito fijo (Task 2); no es un campo libre editable por el usuario.
 */
data class ProviderProfile(
    val provider: InferenceProvider,
    val modelId: String,
    val enabled: Boolean,
    val consentVersion: String?,
)

/**
 * Span de transcripción con identidad pública artificial. Es lo único que se envía a un
 * proveedor: no incluye ids de sesión, rutas ni segmentos internos.
 *
 * - [publicId] tiene la forma `B<bloque>-S<span>` (ver [PublicSpanId]).
 * - [blockOrdinal], [audioSegmentOrdinal] y [spanOrdinal] fijan el orden estable.
 * - [contextOnly] marca spans repetidos como contexto: no pueden sostener por sí solos
 *   un claim (esa regla la aplica el validador de Task 5).
 */
data class PublicTranscriptSpan(
    val publicId: String,
    val blockOrdinal: Int,
    val audioSegmentOrdinal: Int,
    val spanOrdinal: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val contextOnly: Boolean,
)

/**
 * Paquete textual entregado a un proveedor por vez. `packetId` es determinista y lo
 * calcula el constructor de paquetes (Task 3) a partir de las versiones y del texto
 * exacto de los spans.
 */
data class InterpretationRequest(
    val packetId: String,
    val promptVersion: String,
    val schemaVersion: String,
    val spans: List<PublicTranscriptSpan>,
)

/**
 * Local-only request envelope. Provider clients receive [request], while validation and
 * persistence retain [sourceSpanIds] to resolve public aliases to transcript span ids.
 */
data class InterpretationPacket(
    val request: InterpretationRequest,
    val sourceSpanIds: Map<String, String>,
) {
    init {
        require(request.spans.map { it.publicId }.toSet() == sourceSpanIds.keys) {
            "Every public span id must map to exactly one local transcript span id."
        }
    }
}

/** Resultado de una llamada a un proveedor. Éxito con JSON crudo, o falla tipificada. */
sealed interface ProviderOutcome {
    data class Success(
        val provider: InferenceProvider,
        val modelId: String,
        val rawJson: String,
    ) : ProviderOutcome

    data class Failure(
        val provider: InferenceProvider,
        val code: ProviderFailure,
        val retryable: Boolean,
        val httpStatus: Int? = null,
        val retryAfterMs: Long? = null,
    ) : ProviderOutcome
}

/**
 * Motivos de falla uniformes entre proveedores. El router (Task 7) decide reintentos y
 * circuit breaker a partir de estos códigos; ningún código transporta cuerpos HTTP,
 * encabezados ni credenciales.
 */
enum class ProviderFailure {
    NOT_CONFIGURED,
    CONSENT_REQUIRED,
    NO_NETWORK,
    AUTHENTICATION,
    BILLING_RISK,
    QUOTA,
    SERVER_UNAVAILABLE,
    TIMEOUT,
    EMPTY_RESPONSE,
    INVALID_RESPONSE,
    INTERNAL,
}

/**
 * Credencial efímera. El router la lee del store cifrado (Task 2) inmediatamente antes
 * de una llamada y la borra de memoria después. Nunca se persiste ni se registra.
 */
@JvmInline
value class EphemeralCredential(val value: String)

/**
 * Claim en el formato del contrato JSON, previo a convertirse en modelos persistentes.
 * Es la forma que el validador de Task 5 recibe y transforma en `RawClaim` con
 * `EvidenceRef` construidos desde spans locales.
 *
 * - [claimKey] es estable dentro de una respuesta y sirve como blanco de supersesiones.
 * - [category] y [status] se validan contra [ClaimCategory] y [ClaimStatus] en Task 5.
 * - [evidenceSpanIds] referencia [PublicTranscriptSpan.publicId]; nunca texto libre.
 * - [supersedesClaimKeys] lista [claimKey] anteriores que este claim reemplaza.
 */
data class ProviderSemanticClaim(
    val claimKey: String,
    val category: String,
    val value: String,
    val normalizedValue: String,
    val status: String,
    val confidence: Double,
    val evidenceSpanIds: List<String>,
    val supersedesClaimKeys: List<String>,
)

/** Identidad pública artificial de un span: `B<bloque>-S<span>`. */
object PublicSpanId {
    private val PATTERN = Regex("""^B(\d+)-S(\d+)$""")

    fun of(blockOrdinal: Int, spanOrdinal: Int): String = "B$blockOrdinal-S$spanOrdinal"

    fun isValid(publicId: String): Boolean = PATTERN.matches(publicId)
}

/**
 * Orden estable de spans, congelado por el contrato. Ordena por bloque, luego por
 * ordinal de segmento de audio y luego por ordinal de span. `startMs` es un desempate
 * final y nunca reordena segmentos por sí solo.
 */
object SpanOrder : Comparator<PublicTranscriptSpan> {
    override fun compare(a: PublicTranscriptSpan, b: PublicTranscriptSpan): Int {
        var c = a.blockOrdinal.compareTo(b.blockOrdinal)
        if (c != 0) return c
        c = a.audioSegmentOrdinal.compareTo(b.audioSegmentOrdinal)
        if (c != 0) return c
        c = a.spanOrdinal.compareTo(b.spanOrdinal)
        if (c != 0) return c
        return a.startMs.compareTo(b.startMs)
    }
}

/** Campos permanentes de la ficha diaria. */
enum class DiaryField { TOPICS, ACTIVITIES, PAGES, EXERCISES, HOMEWORK }

/** Destino de un claim en la ficha, resuelto por la política estado→campo. */
sealed interface FieldTarget {
    /** El claim alimenta un campo concreto de la clase o la tarea. */
    data class Field(val field: DiaryField) : FieldTarget

    /** El claim siempre queda “por confirmar” antes de aprobar. */
    data object Confirmation : FieldTarget

    /** El claim se conserva como historial inactivo (propuesto, cancelado o corregido). */
    data object InactiveHistory : FieldTarget
}

/**
 * Política estado→campo, congelada por Task 1 y aplicada por el projector (Task 5).
 *
 * El estado decide antes que la categoría:
 * - `UNCERTAIN` siempre requiere confirmación, cualquiera sea la categoría.
 * - `PROPOSED`, `CANCELLED` y `CORRECTED` quedan como historial inactivo.
 * - `ASSIGNED` alimenta el campo Tarea, aunque la categoría sea EXERCISE, PAGE, etc.
 * - `PERFORMED` alimenta el campo de clase que corresponde a su categoría.
 */
object StatusFieldPolicy {
    fun target(category: ClaimCategory, status: ClaimStatus): FieldTarget = when (status) {
        ClaimStatus.UNCERTAIN -> FieldTarget.Confirmation
        ClaimStatus.PROPOSED, ClaimStatus.CANCELLED, ClaimStatus.CORRECTED -> FieldTarget.InactiveHistory
        ClaimStatus.ASSIGNED -> FieldTarget.Field(DiaryField.HOMEWORK)
        ClaimStatus.PERFORMED -> FieldTarget.Field(categoryField(category))
    }

    private fun categoryField(category: ClaimCategory): DiaryField = when (category) {
        ClaimCategory.TOPIC -> DiaryField.TOPICS
        ClaimCategory.ACTIVITY -> DiaryField.ACTIVITIES
        ClaimCategory.PAGE -> DiaryField.PAGES
        ClaimCategory.EXERCISE -> DiaryField.EXERCISES
        ClaimCategory.HOMEWORK -> DiaryField.HOMEWORK
    }
}

/** Error estructural al leer el envoltorio JSON del contrato. No es validación semántica. */
class ContractParseException(message: String) : Exception(message)

/**
 * Códec canónico del envoltorio `{"claims":[...]}` compartido por todos los proveedores.
 *
 * Task 1 solo cubre la estructura: presencia y tipo de cada campo, y round-trip estable.
 * La validación semántica adversarial (enums desconocidos, confianza fuera de rango,
 * ids inexistentes, evidencia solo contextual, límites de cantidad) pertenece a Task 5.
 */
object ProviderClaimsCodec {

    fun encode(claims: List<ProviderSemanticClaim>): String {
        val array = JSONArray()
        claims.forEach { claim ->
            val obj = JSONObject()
            obj.put("claim_key", claim.claimKey)
            obj.put("category", claim.category)
            obj.put("value", claim.value)
            obj.put("normalized_value", claim.normalizedValue)
            obj.put("status", claim.status)
            obj.put("confidence", claim.confidence)
            obj.put("evidence_span_ids", JSONArray(claim.evidenceSpanIds))
            obj.put("supersedes_claim_keys", JSONArray(claim.supersedesClaimKeys))
            array.put(obj)
        }
        return JSONObject().put("claims", array).toString()
    }

    fun decode(rawJson: String): List<ProviderSemanticClaim> {
        val root = try {
            JSONObject(rawJson)
        } catch (e: JSONException) {
            throw ContractParseException("JSON raíz inválido: ${e.message}")
        }
        if (!root.has("claims")) throw ContractParseException("Falta la propiedad 'claims'.")
        val array = root.optJSONArray("claims")
            ?: throw ContractParseException("'claims' debe ser un arreglo.")
        val claims = ArrayList<ProviderSemanticClaim>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i)
                ?: throw ContractParseException("El elemento $i de 'claims' no es un objeto.")
            claims += try {
                obj.toClaim(i)
            } catch (e: JSONException) {
                throw ContractParseException("Claim $i inválido: ${e.message}")
            }
        }
        return claims
    }

    private fun JSONObject.toClaim(index: Int): ProviderSemanticClaim = ProviderSemanticClaim(
        claimKey = requireString("claim_key", index),
        category = requireString("category", index),
        value = requireString("value", index),
        normalizedValue = requireString("normalized_value", index),
        status = requireString("status", index),
        confidence = requireDouble("confidence", index),
        evidenceSpanIds = requireStringList("evidence_span_ids", index),
        supersedesClaimKeys = requireStringList("supersedes_claim_keys", index),
    )

    private fun JSONObject.requireString(key: String, index: Int): String {
        if (!has(key) || isNull(key)) throw ContractParseException("Claim $index sin '$key'.")
        if (get(key) !is String) throw ContractParseException("Claim $index: '$key' debe ser texto.")
        return getString(key)
    }

    private fun JSONObject.requireDouble(key: String, index: Int): Double {
        if (!has(key) || isNull(key)) throw ContractParseException("Claim $index sin '$key'.")
        return try {
            getDouble(key)
        } catch (e: JSONException) {
            throw ContractParseException("Claim $index: '$key' debe ser numérico.")
        }
    }

    private fun JSONObject.requireStringList(key: String, index: Int): List<String> {
        if (!has(key) || isNull(key)) throw ContractParseException("Claim $index sin '$key'.")
        val array = optJSONArray(key)
            ?: throw ContractParseException("Claim $index: '$key' debe ser un arreglo.")
        val list = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            if (array.isNull(i) || array.get(i) !is String) {
                throw ContractParseException("Claim $index: '$key'[$i] debe ser texto.")
            }
            list += array.getString(i)
        }
        return list
    }
}
