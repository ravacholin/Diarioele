package com.capo.diarioclase.processing.semantic

/**
 * Prompt y esquema únicos, compartidos por todos los proveedores (Task 4).
 *
 * El prompt pide una extracción exhaustiva de claims con evidencia obligatoria; **no**
 * menciona el modo de interpretación (`CONSERVATIVE`/`BALANCED`/`EXHAUSTIVE`), que la app
 * aplica localmente después. El esquema lógico se traduce al formato de cada proveedor en
 * su adaptador.
 */
data class InterpretationPrompt(
    val systemInstruction: String,
    val userText: String,
    val jsonSchema: String,
)

class InterpretationPromptFactory {

    fun create(request: InterpretationRequest): InterpretationPrompt = InterpretationPrompt(
        systemInstruction = SYSTEM_INSTRUCTION,
        userText = renderUserText(request),
        jsonSchema = JSON_SCHEMA,
    )

    private fun renderUserText(request: InterpretationRequest): String = buildString {
        append("Transcripción de una clase de español (rioplatense). ")
        append("Cada línea es un fragmento con su id artificial y su tiempo en milisegundos. ")
        append("Los fragmentos marcados (contexto) son solo contexto: no generes claims nuevos a partir de ellos.\n\n")
        request.spans.forEach { span ->
            append('[').append(span.publicId).append('|')
            append(span.startMs).append('-').append(span.endMs).append(']')
            if (span.contextOnly) append(" (contexto)")
            append(' ').append(span.text).append('\n')
        }
    }

    companion object {
        val SYSTEM_INSTRUCTION = """
            Sos un asistente que arma la ficha de una clase de español a partir de su
            transcripción. Devolvé únicamente un JSON con la propiedad "claims".

            Cada claim describe un hecho de la clase con estos campos obligatorios:
            - claim_key: identificador estable dentro de esta respuesta.
            - category: una de TOPIC, ACTIVITY, PAGE, EXERCISE, HOMEWORK.
            - value: el valor tal como se dijo.
            - normalized_value: el valor normalizado (por ejemplo, números en cifras).
            - status: uno de PERFORMED, ASSIGNED, PROPOSED, CANCELLED, CORRECTED, UNCERTAIN.
            - confidence: número entre 0 y 1.
            - evidence_span_ids: lista de ids de fragmentos que respaldan el claim.
            - supersedes_claim_keys: lista de claim_key anteriores que este claim reemplaza.

            Reglas:
            - No inventes páginas, ejercicios ni temas: extraé solamente lo que se expresó.
            - Cada claim debe citar en evidence_span_ids al menos un fragmento válido de los
              provistos. No uses fragmentos marcados (contexto) como única evidencia.
            - Distinguí una pregunta del alumnado de una actividad realizada: una pregunta
              no es una actividad ni una tarea.
            - Una cita o un ejemplo del libro no se convierte en tarea ni en actividad.
            - Un plan a futuro (lo que se hará la próxima clase) usa status PROPOSED, no
              PERFORMED.
            - Aplicá las autocorrecciones posteriores: si algo se corrige más adelante,
              reflejalo con status CORRECTED o con supersedes_claim_keys.
            - Usá UNCERTAIN cuando el referente sea ambiguo.
            - Extraé de forma exhaustiva; la aplicación decide después qué mostrar.
        """.trimIndent()

        val JSON_SCHEMA = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["claims"],
              "properties": {
                "claims": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "additionalProperties": false,
                    "required": [
                      "claim_key", "category", "value", "normalized_value",
                      "status", "confidence", "evidence_span_ids", "supersedes_claim_keys"
                    ],
                    "properties": {
                      "claim_key": { "type": "string" },
                      "category": { "type": "string", "enum": ["TOPIC", "ACTIVITY", "PAGE", "EXERCISE", "HOMEWORK"] },
                      "value": { "type": "string" },
                      "normalized_value": { "type": "string" },
                      "status": { "type": "string", "enum": ["PERFORMED", "ASSIGNED", "PROPOSED", "CANCELLED", "CORRECTED", "UNCERTAIN"] },
                      "confidence": { "type": "number", "minimum": 0, "maximum": 1 },
                      "evidence_span_ids": { "type": "array", "items": { "type": "string" } },
                      "supersedes_claim_keys": { "type": "array", "items": { "type": "string" } }
                    }
                  }
                }
              }
            }
        """.trimIndent()
    }
}
