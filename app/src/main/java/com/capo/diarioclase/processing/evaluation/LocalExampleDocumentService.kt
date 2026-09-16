package com.capo.diarioclase.processing.evaluation

/**
 * Almacenamiento del corpus en archivo privado sin backup (Fase 6, Q6). La implementación de
 * producción usa `noBackupFilesDir`; en pruebas se usa una versión en memoria.
 */
interface LocalExampleStorage {
    fun readAll(): String
    fun writeAll(content: String)
    fun deleteAll()
}

/** E/S de documentos vía Storage Access Framework (Fase 6, Q6). Solo tras acción del usuario. */
interface ExampleDocumentIo {
    fun read(uri: String): String
    fun write(uri: String, content: String)
}

/**
 * Operaciones explícitas del corpus local (Fase 6, Q6). Ningún flujo de aprobación las llama
 * automáticamente: siempre las inicia el docente. `preview` arma el ejemplo anonimizado sin
 * guardarlo; `savePreview` lo agrega al corpus; `deleteAll` borra todo; export/import usan SAF.
 */
class LocalExampleDocumentService(
    private val repository: LocalExampleRepository,
    private val storage: LocalExampleStorage,
    private val codec: JsonlExampleCodec = JsonlExampleCodec(),
) {

    suspend fun preview(sessionId: String): LocalEvaluationExample = repository.buildExample(sessionId)

    fun savePreview(preview: LocalEvaluationExample) {
        val existing = storage.readAll()
        val line = codec.encode(preview)
        storage.writeAll(if (existing.isBlank()) line else existing.trimEnd() + "\n" + line)
    }

    fun count(): Int = codec.decodeAll(storage.readAll()).size

    fun deleteAll() = storage.deleteAll()

    fun exportJsonl(io: ExampleDocumentIo, uri: String) {
        io.write(uri, storage.readAll())
    }

    fun importJsonl(io: ExampleDocumentIo, uri: String) {
        val imported = codec.decodeAll(io.read(uri)) // valida el formato antes de guardar
        val merged = (codec.decodeAll(storage.readAll()) + imported)
        storage.writeAll(codec.encodeAll(merged))
    }
}
