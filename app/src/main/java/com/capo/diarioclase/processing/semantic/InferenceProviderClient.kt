package com.capo.diarioclase.processing.semantic

/**
 * Contrato mínimo de un adaptador de proveedor de inferencia.
 *
 * Congelado por Task 1. Cada implementación de Task 4 (Gemini, y el cliente compatible
 * con OpenAI para Groq y OpenRouter) traduce el [InterpretationRequest] al formato HTTP
 * de su servicio y devuelve un [ProviderOutcome]. El router (Task 7) es el único que
 * obtiene la [EphemeralCredential] del store cifrado, la pasa por llamada y la descarta.
 *
 * Reglas del contrato:
 * - `infer` nunca lanza por una falla del proveedor: devuelve [ProviderOutcome.Failure].
 * - La credencial no se guarda ni se registra en logs, excepciones o resultados.
 * - No se envía audio, rutas ni ids internos: solo lo que contiene [InterpretationRequest].
 */
fun interface InferenceProviderClient {
    suspend fun infer(
        request: InterpretationRequest,
        credential: EphemeralCredential,
    ): ProviderOutcome
}
