package com.capo.diarioclase.processing.transcription

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Native pipe contract: run on an Android device, not a mocked Linux pipe. */
class AudioPipeConsumptionTest {
    @Test
    fun testBufferedShortAudioIsNotProofOfConsumption() {
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            // Fits in even a single pipe page, so writing can finish without any reader.
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(ByteArray(320) { 1 }) }
            assertFalse(isAudioPipeDrained(pipe[0]))
            // Polling must not consume bytes just to make its own verification pass.
            assertFalse(isAudioPipeDrained(pipe[0]))
            val reader = ParcelFileDescriptor.dup(pipe[0].fileDescriptor)
            ParcelFileDescriptor.AutoCloseInputStream(reader).use { input ->
                assertEquals(320, input.readBytes().size)
            }
            assertTrue(isAudioPipeDrained(pipe[0]))
        } finally {
            pipe.forEach { runCatching { it.close() } }
        }
    }

    @Test
    fun testOpenWriterOrInvalidDescriptorFailsClosed() {
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            assertFalse(isAudioPipeDrained(pipe[0]))
            pipe[0].close()
            assertFalse(isAudioPipeDrained(pipe[0]))
        } finally {
            pipe.forEach { runCatching { it.close() } }
        }
    }

    @Test
    fun testFullPipeWriterCanBeCancelledWithReaderStillOpen() = runBlocking {
        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val source = File.createTempFile("pipe-cancel-", ".wav", cacheDir)
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            // No reader consumes these bytes. Cancellation must not wait for a blocking write.
            source.outputStream().use { it.write(ByteArray(4 * 1024 * 1024)) }
            val writer = launch(Dispatchers.IO) { writePcmToPipe(source, pipe[1]) }
            delay(50)
            withTimeout(1_000) { writer.cancelAndJoin() }
            assertTrue(writer.isCancelled)
        } finally {
            pipe.forEach { runCatching { it.close() } }
            source.delete()
        }
    }
}
