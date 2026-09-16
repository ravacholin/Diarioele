package com.capo.diarioclase.recording.audio

import com.capo.diarioclase.data.db.SegmentId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TemporaryAudioFilesTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `finds legacy WAV and new OGG ready files by exact segment id`() = runTest {
        File(folder.root, "block__0__wav-id.ready.wav").writeBytes(byteArrayOf(1))
        File(folder.root, "block__1__ogg-id.ready.ogg").writeBytes(byteArrayOf(2))
        val files = TemporaryAudioFiles(folder.root)

        assertTrue(files.exists(SegmentId("wav-id")))
        assertTrue(files.exists(SegmentId("ogg-id")))
        assertFalse(files.exists(SegmentId("id")))
    }

    @Test
    fun `delete removes only requested ready file and preserves open and unrelated audio`() = runTest {
        val target = File(folder.root, "block__0__target.ready.ogg").apply {
            writeBytes(byteArrayOf(1))
        }
        val open = File(folder.root, "block__0__target.open.ogg").apply {
            writeBytes(byteArrayOf(2))
        }
        val legacy = File(folder.root, "block__1__legacy.ready.wav").apply {
            writeBytes(byteArrayOf(3))
        }
        val unrelated = File(folder.root, "notes.txt").apply {
            writeText("preservar")
        }
        val files = TemporaryAudioFiles(folder.root)

        assertTrue(files.delete(SegmentId("target")) is DeleteResult.Deleted)

        assertFalse(target.exists())
        assertTrue(open.exists())
        assertTrue(legacy.exists())
        assertTrue(unrelated.exists())
    }
}
