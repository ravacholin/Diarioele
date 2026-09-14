package com.capo.diarioclase.processing.transcription

data class WhisperOptions(
    val language: String = "es",
    val translate: Boolean = false,
    val detectLanguage: Boolean = false,
    val prompt: String = RioplatensePrompt.TEXT,
    val threads: Int,
)

data class NativeSpan(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val confidence: Double,
)

data class NativeWindowResult(val spans: List<NativeSpan>)

object RioplatensePrompt {
    const val TEXT =
        "Clase de español rioplatense. El profesor usa vos y ustedes. " +
            "Registrar con precisión temas, actividades realizadas, páginas, " +
            "ejercicios hechos y tarea para la próxima clase."
}

interface WhisperNativeRuntime : AutoCloseable {
    fun load(modelPath: String)
    fun transcribe(samples: FloatArray, options: WhisperOptions): NativeWindowResult
    fun cancel()
}

class WhisperNativeBridge : WhisperNativeRuntime {
    private var handle: Long = 0L

    @Synchronized
    override fun load(modelPath: String) {
        close()
        ensureLibraryLoaded()
        handle = nativeInit(modelPath)
        check(handle != 0L) { "No se pudo cargar el modelo Whisper" }
    }

    @Synchronized
    override fun transcribe(
        samples: FloatArray,
        options: WhisperOptions,
    ): NativeWindowResult {
        check(handle != 0L) { "El motor Whisper no está cargado" }
        require(options.language == "es") { "Solo se admite español" }
        require(!options.translate && !options.detectLanguage) {
            "La traducción y la detección automática están desactivadas"
        }
        return NativeWindowResult(
            nativeTranscribe(
                handle = handle,
                samples = samples,
                prompt = options.prompt,
                threads = options.threads,
            ).toList(),
        )
    }

    override fun cancel() {
        val current = handle
        if (current != 0L) nativeCancel(current)
    }

    @Synchronized
    override fun close() {
        val current = handle
        handle = 0L
        if (current != 0L) nativeFree(current)
    }

    fun version(): String {
        ensureLibraryLoaded()
        return nativeVersion()
    }

    private external fun nativeInit(modelPath: String): Long
    private external fun nativeTranscribe(
        handle: Long,
        samples: FloatArray,
        prompt: String,
        threads: Int,
    ): Array<NativeSpan>
    private external fun nativeCancel(handle: Long)
    private external fun nativeFree(handle: Long)
    private external fun nativeVersion(): String

    companion object {
        @Volatile private var libraryLoaded = false

        @Synchronized
        private fun ensureLibraryLoaded() {
            if (!libraryLoaded) {
                System.loadLibrary("diarioclase_whisper")
                libraryLoaded = true
            }
        }
    }
}
