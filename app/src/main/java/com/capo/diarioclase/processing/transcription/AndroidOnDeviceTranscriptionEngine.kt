package com.capo.diarioclase.processing.transcription

import android.content.Context
import android.content.Intent
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.ModelDownloadListener
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import com.capo.diarioclase.recording.audio.ReadySegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class AndroidOnDeviceTranscriptionEngine(private val context: Context) : TranscriptionEngine {
    override suspend fun transcribe(segment: ReadySegment): TranscriptResult = coroutineScope {
        if (Build.VERSION.SDK_INT < 33 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
            return@coroutineScope TranscriptResult.Failure(TranscriptionFailure.ON_DEVICE_UNAVAILABLE, false, "El reconocimiento local no está disponible")
        val file = File(segment.path)
        if (!file.isFile || file.length() <= WAV_HEADER_BYTES)
            return@coroutineScope TranscriptResult.Failure(TranscriptionFailure.INVALID_AUDIO, false, "El segmento WAV no es válido")
        val pipe = ParcelFileDescriptor.createPipe()
        val copyCompleted = AtomicBoolean(false)
        val writer = launch(Dispatchers.IO) {
            copyCompleted.set(writePcmToPipe(file, pipe[1]))
        }
        try {
            val recognition = runRecognitionWithDeadline(segment.durationMs) {
                withContext(Dispatchers.Main.immediate) { recognize(segment, pipe[0]) }
            }
            if (recognition == null) {
                TranscriptResult.Failure(
                    TranscriptionFailure.TIMEOUT,
                    true,
                    "El reconocimiento local no respondió dentro del tiempo esperado",
                )
            } else {
                validateSuppliedAudioResult(
                    recognition,
                    writer,
                    copyCompleted::get,
                ) { isAudioPipeDrained(pipe[0]) }
            }
        } finally {
            runCatching { pipe[0].close() }
            runCatching { pipe[1].close() }
            writer.cancelAndJoin()
        }
    }

    @SuppressLint("NewApi")
    suspend fun requestSpanishModelDownload() = withContext(Dispatchers.Main.immediate) {
        if (Build.VERSION.SDK_INT < 33 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return@withContext
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        if (Build.VERSION.SDK_INT >= 34) recognizer.triggerModelDownload(baseIntent(), context.mainExecutor, object : ModelDownloadListener {
            override fun onProgress(completedPercent: Int) = Unit
            override fun onSuccess() = recognizer.destroy()
            override fun onScheduled() = recognizer.destroy()
            override fun onError(error: Int) = recognizer.destroy()
        }) else {
            recognizer.triggerModelDownload(baseIntent())
            android.os.Handler(context.mainLooper).postDelayed({ recognizer.destroy() }, 5_000)
        }
    }

    @androidx.annotation.RequiresApi(33)
    private suspend fun recognize(segment: ReadySegment, audioSource: ParcelFileDescriptor): TranscriptResult = suspendCancellableCoroutine { continuation ->
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        val texts = linkedSetOf<String>()
        val lifecycleLock = Any()
        var done = false
        fun completeOnMain(result: TranscriptResult?) {
            val shouldComplete = synchronized(lifecycleLock) {
                if (done) false else {
                    done = true
                    true
                }
            }
            if (!shouldComplete) return
            context.mainExecutor.execute {
                recognizer.cancel()
                recognizer.destroy()
                if (result != null && continuation.isActive) continuation.resume(result)
            }
        }
        fun finish(result: TranscriptResult) {
            completeOnMain(result)
        }
        fun resultFromTexts(): TranscriptResult {
            if (texts.isEmpty()) return TranscriptResult.Failure(TranscriptionFailure.NO_SPEECH, true, "No se detectó voz en este bloque")
            val duration = segment.durationMs.coerceAtLeast(1)
            val orderedTexts = texts.toList()
            val each = duration / orderedTexts.size
            return TranscriptResult.Success(orderedTexts.mapIndexed { index, text ->
                TranscriptSpan(UUID.randomUUID().toString(), segment.id.value, segment.blockId, index * each, if (index == orderedTexts.lastIndex) duration else (index + 1) * each, text, .80)
            })
        }
        fun collect(bundle: Bundle) {
            bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let(texts::add)
        }
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onResults(results: Bundle) { collect(results); finish(resultFromTexts()) }
            override fun onSegmentResults(segmentResults: Bundle) { collect(segmentResults) }
            override fun onEndOfSegmentedSession() { finish(resultFromTexts()) }
            override fun onError(error: Int) {
                val failure = when (error) {
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> TranscriptionFailure.LANGUAGE_UNAVAILABLE
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> TranscriptionFailure.RECOGNIZER_BUSY
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> TranscriptionFailure.NO_SPEECH
                    else -> TranscriptionFailure.UNKNOWN
                }
                finish(TranscriptResult.Failure(failure, failure != TranscriptionFailure.LANGUAGE_UNAVAILABLE, "Código local $error"))
            }
        })
        continuation.invokeOnCancellation {
            completeOnMain(null)
        }
        val intent = baseIntent().apply {
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audioSource)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }
        recognizer.checkRecognitionSupport(intent, context.mainExecutor, object : RecognitionSupportCallback {
            override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                synchronized(lifecycleLock) {
                    if (done || !continuation.isActive) return
                    val spanishReady = recognitionSupport.installedOnDeviceLanguages.any { it.startsWith("es", ignoreCase = true) }
                    if (!spanishReady) {
                        finish(TranscriptResult.Failure(TranscriptionFailure.LANGUAGE_UNAVAILABLE, false, "El paquete local de español no está instalado"))
                        return
                    }
                    try {
                        recognizer.startListening(intent)
                    } catch (_: Exception) {
                        finish(TranscriptResult.Failure(TranscriptionFailure.UNKNOWN, true, "El reconocedor local no pudo iniciar"))
                    }
                }
            }
            override fun onError(errorCode: Int) { finish(TranscriptResult.Failure(TranscriptionFailure.LANGUAGE_UNAVAILABLE, false, "El paquete de español local no está listo")) }
        })
    }

    private fun baseIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-AR")
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
    }
}

/**
 * Copies PCM without ever blocking indefinitely on a full recognizer pipe.
 * Transcription already requires API 33; API 30 is where public fcntlInt became available.
 */
internal suspend fun writePcmToPipe(source: File, output: ParcelFileDescriptor): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        runCatching { output.close() }
        return false
    }
    return try {
        val descriptor = output.fileDescriptor
        val flags = Os.fcntlInt(descriptor, OsConstants.F_GETFL, 0)
        Os.fcntlInt(descriptor, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
        FileInputStream(source).use { input ->
            input.channel.position(WAV_HEADER_BYTES)
            val buffer = ByteArray(PIPE_COPY_BUFFER_BYTES)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                var offset = 0
                while (offset < read) {
                    currentCoroutineContext().ensureActive()
                    try {
                        val written = Os.write(descriptor, buffer, offset, read - offset)
                        if (written <= 0) return false
                        offset += written
                    } catch (error: ErrnoException) {
                        if (error.errno != OsConstants.EAGAIN) throw error
                        delay(PIPE_WRITE_RETRY_MS)
                    }
                }
            }
        }
        true
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    } finally {
        runCatching { output.close() }
    }
}

/** Non-consuming proof: EOF/HUP is present and no unread bytes remain in the pipe. */
internal fun isAudioPipeDrained(readEnd: ParcelFileDescriptor): Boolean = runCatching {
    val polled = StructPollfd().apply {
        fd = readEnd.fileDescriptor
        events = OsConstants.POLLIN.toShort()
    }
    if (Os.poll(arrayOf(polled), 0) != 1) return@runCatching false
    val events = polled.revents.toInt()
    val invalid = OsConstants.POLLERR or OsConstants.POLLNVAL
    events and OsConstants.POLLHUP != 0 && events and OsConstants.POLLIN == 0 && events and invalid == 0
}.getOrDefault(false)

private const val WAV_HEADER_BYTES = 44L
private const val PIPE_COPY_BUFFER_BYTES = 32 * 1024
private const val PIPE_WRITE_RETRY_MS = 5L
