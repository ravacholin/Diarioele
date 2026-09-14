package com.capo.diarioclase.processing.transcription

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test

class WhisperNativeSmokeTest {
    @Test
    fun nativeEngineLoadsPinnedModelAndAcceptsSpanishPcm() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val model = WhisperModelInstaller(context).ensureInstalled()
        WhisperNativeBridge().use { native ->
            assertTrue(native.version().isNotBlank())
            native.load(model.absolutePath)
            withTimeout(300_000) {
                native.transcribe(
                    samples = FloatArray(16_000),
                    options = WhisperOptions(threads = 2),
                )
            }
        }
    }
}
