package com.capo.diarioclase.recording.audio

import com.capo.diarioclase.data.db.BlockId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OpusSegmentStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `close promotes only a validated encoded segment`() = runTest {
        val encodedInfo = EncodedAudioInfo(
            durationMs = 1_000,
            sampleRate = 16_000,
            channelCount = 1,
            mimeType = "audio/opus",
        )
        val store = OpusSegmentStore(
            root = folder.root,
            encoderFactory = StreamingAudioEncoderFactory { output, _ ->
                FileWritingEncoder(output, encodedInfo)
            },
            inspector = EncodedAudioInspector { encodedInfo },
        )

        val open = store.open(BlockId("block"), 0)
        store.append(open, shortArrayOf(1, 2, 3, 4), 4)
        val ready = store.close(open)

        assertTrue(open.path.endsWith(".open.ogg"))
        assertFalse(File(open.path).exists())
        assertTrue(ready.path.endsWith(".ready.ogg"))
        assertTrue(File(ready.path).exists())
        assertTrue(File(ready.path).readBytes().contentEquals(byteArrayOf(1, 2, 3, 4)))
        assertEquals(1_000, ready.durationMs)
        assertEquals(64, ready.sha256.length)
    }

    @Test
    fun `failed validation keeps the open source byte for byte`() = runTest {
        val encodedInfo = EncodedAudioInfo(1_000, 16_000, 1, "audio/opus")
        val store = OpusSegmentStore(
            root = folder.root,
            encoderFactory = StreamingAudioEncoderFactory { output, _ ->
                FileWritingEncoder(output, encodedInfo)
            },
            inspector = EncodedAudioInspector {
                throw IllegalStateException("OGG inválido")
            },
        )
        val open = store.open(BlockId("block"), 0)
        store.append(open, shortArrayOf(9, 8, 7), 3)
        val beforeClose = File(open.path).readBytes()

        try {
            store.close(open)
            fail("La validación debía impedir la promoción")
        } catch (expected: IllegalStateException) {
            assertEquals("OGG inválido", expected.message)
        }

        assertTrue(File(open.path).exists())
        assertTrue(beforeClose.contentEquals(File(open.path).readBytes()))
        assertFalse(folder.root.listFiles().orEmpty().any { it.name.endsWith(".ready.ogg") })
    }
}

private class FileWritingEncoder(
    private val output: File,
    private val info: EncodedAudioInfo,
) : StreamingAudioEncoder {
    override fun append(pcm: ShortArray, count: Int) {
        output.outputStream().use { stream ->
            repeat(count) { index -> stream.write(pcm[index].toInt()) }
        }
    }

    override fun finish(): EncodedAudioInfo = info

    override fun close() = Unit
}
