package com.capo.diarioclase.processing.semantic

/**
 * Contrato del quality loop (Fase 6), congelado por Task Q0.
 *
 * Reúne los modelos que comparten el gate de calidad (Q1), los prompts de reparación (Q2),
 * la fusión híbrida local + remota (Q3) y la observabilidad de la interpretación (Q5).
 *
 * Invariante central: ningún modelo de este archivo transporta cuerpos HTTP de un proveedor,
 * texto de transcripción ni credenciales. Solo describen decisiones, códigos de issue seguros
 * y confianza efectiva. Un [SemanticIssue] es un código estable, apto para incluirse en un
 * prompt de reparación (Q2) o en el estado de UI (Q5) sin filtrar contenido privado.
 */

/**
 * Motivo por el cual la respuesta de un proveedor no puede aceptarse tal cual.
 *
 * Cada valor es un código seguro: nombra la clase del problema, nunca el dato concreto que lo
 * disparó. El gate de calidad (Q1) los produce y el factory de prompts (Q2) los reenvía al
 * proveedor como instrucción de corrección, sin adjuntar el cuerpo original.
 */
enum class SemanticIssue {
    /** El proveedor devolvió vacío pese a existir señal local (marcador o candidato literal). */
    EMPTY_DESPITE_LOCAL_SIGNAL,

    /** Un número citado (página, ejercicio) no coincide con la evidencia disponible. */
    NUMERIC_EVIDENCE_MISMATCH,

    /** El valor normalizado no se corresponde con la normalización determinista local. */
    NORMALIZATION_MISMATCH,

    /** El estado declarado es incompatible con la evidencia (p. ej. PERFORMED sin acción). */
    INCOMPATIBLE_STATUS,

    /** Dos claims se contradicen y la contradicción no quedó resuelta por supersesión. */
    UNRESOLVED_CONTRADICTION,

    /** El orden de los claims no respeta la primera evidencia no contextual. */
    INVALID_CHRONOLOGY,

    /** Un claim referencia evidencia inexistente o solo contextual. */
    UNKNOWN_EVIDENCE,
}

/**
 * Decisión del gate de calidad sobre una respuesta remota.
 *
 * - [ACCEPT]: la respuesta se funde con los candidatos locales sin reintento.
 * - [REPAIR]: se permite un único reintento correctivo con los códigos de issue.
 * - [FALLBACK]: la respuesta se descarta y la interpretación continúa solo con lo local.
 */
enum class QualityDecision { ACCEPT, REPAIR, FALLBACK }

/**
 * Veredicto del gate de calidad para una respuesta de proveedor.
 *
 * Solo contiene datos seguros: la [decision], los [issues] detectados como códigos y la
 * [effectiveConfidence] agregada. Nunca incluye el texto que originó los issues.
 */
data class QualityReport(
    val decision: QualityDecision,
    val issues: List<SemanticIssue>,
    val effectiveConfidence: Double,
)

/**
 * Procedencia de un claim ya fusionado.
 *
 * - [LOCAL]: solo lo produjo la extracción determinista local.
 * - [REMOTE]: solo lo produjo un proveedor remoto aceptado.
 * - [BOTH]: local y remoto coincidieron sobre la misma evidencia.
 * - [USER]: lo fijó una corrección manual del docente.
 */
enum class ClaimProvenance { LOCAL, REMOTE, BOTH, USER }

/**
 * Señal de un marcador manual colocado durante la clase.
 *
 * Los ids son locales (marcador y bloque) y nunca se envían a un proveedor: la fusión (Q3) los
 * usa para sostener candidatos de tarea sin exponerlos en el [InterpretationRequest].
 */
data class ManualMarkerSignal(
    val markerId: String,
    val type: String,
    val blockId: String,
    val offsetMs: Long,
)

/**
 * Señales locales que acompañan a un paquete pero no viajan al proveedor.
 *
 * Se calculan en el dispositivo (Q7 las carga desde el store) y alimentan la fusión híbrida.
 */
data class LocalInterpretationSignals(
    val markers: List<ManualMarkerSignal> = emptyList(),
)
