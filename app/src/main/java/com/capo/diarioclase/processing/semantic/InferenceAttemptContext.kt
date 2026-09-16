package com.capo.diarioclase.processing.semantic

/**
 * Contexto del intento de inferencia que recibe un [InferenceProviderClient] (Task Q0).
 *
 * Distingue el primer intento del único reintento correctivo permitido por el quality loop
 * (Q2). En un reintento transporta los [safeIssueCodes] detectados por el gate de calidad,
 * que son códigos estables ([SemanticIssue]) aptos para incluirse en el prompt sin filtrar
 * el cuerpo original del proveedor ni la transcripción.
 *
 * Invariantes:
 * - [attempt] vale 1 en el intento inicial y 2 en el reintento correctivo. No hay un tercer
 *   intento: el router permite una única reparación.
 * - [safeIssueCodes] está vacío en el intento inicial y contiene solo códigos de issue en el
 *   reintento. Nunca contiene texto de transcripción, cuerpos HTTP ni credenciales.
 */
data class InferenceAttemptContext(
    val attempt: Int,
    val safeIssueCodes: Set<SemanticIssue>,
) {
    companion object {
        /** Intento inicial, sin issues previos. */
        fun initial() = InferenceAttemptContext(attempt = 1, safeIssueCodes = emptySet())

        /** Único reintento correctivo, con los códigos de issue detectados por el gate. */
        fun repair(issues: Set<SemanticIssue>) =
            InferenceAttemptContext(attempt = 2, safeIssueCodes = issues)
    }
}
