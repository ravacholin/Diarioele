package com.capo.diarioclase.recording.audio

import com.capo.diarioclase.data.db.SegmentId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OggRecoveryTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `repair promotes finalized OGG and preserves invalid open source`() = runTest {
        val valid = File(folder.root, "block__0__valid.open.ogg").apply {
            writeBytes(byteArrayOf(0x4f, 0x67, 0x67, 0x53, 1))
        }
        val invalid = File(folder.root, "block__1__invalid.open.ogg").apply {
            writeBytes(byteArrayOf(7, 7, 7, 7))
        }
        val invalidBeforeRepair = invalid.readBytes()
        val existingReady = File(folder.root, "block__2__existing.ready.ogg").apply {
            writeBytes(byteArrayOf(5, 5, 5))
        }
        val readyBeforeRepair = existingReady.readBytes()
        val store = OpusSegmentStore(
            root = folder.root,
            encoderFactory = StreamingAudioEncoderFactory { _, _ ->
                error("La recuperación no debe crear un encoder")
            },
            inspector = EncodedAudioInspector { file ->
                if (file == valid) EncodedAudioInfo(2_000, 16_000, 1, "audio/opus")
                else throw IllegalStateException("OGG incompleto")
            },
        )

        val repaired = store.repairOpenSegments()

        assertEquals(1, repaired.size)
        assertEquals(SegmentId("valid"), repaired.single().id)
        assertTrue(repaired.single().path.endsWith("valid.ready.ogg"))
        assertFalse(valid.exists())
        assertTrue(invalid.exists())
        assertTrue(invalidBeforeRepair.contentEquals(invalid.readBytes()))
        assertTrue(readyBeforeRepair.contentEquals(existingReady.readBytes()))
    }
}
