package com.capo.diarioclase.recording.audio

import com.capo.diarioclase.data.db.BlockId
import com.capo.diarioclase.data.db.SegmentId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyCompatibleSegmentStoreTest {
    @Test
    fun `new segments are always opened by OGG primary store`() = runTest {
        val primary = StubSegmentStore(openPath = "new.open.ogg")
        val legacy = StubSegmentStore(openPath = "legacy.open.wav")
        val store = LegacyCompatibleSegmentStore(primary, legacy, StubCleanupFiles())

        val open = store.open(BlockId("block"), 0)

        assertTrue(open.path.endsWith(".open.ogg"))
    }

    @Test
    fun `recovery returns finalized legacy WAV and new OGG segments`() = runTest {
        val wav = ready("wav", "legacy.ready.wav")
        val ogg = ready("ogg", "new.ready.ogg")
        val store = LegacyCompatibleSegmentStore(
            primary = StubSegmentStore(repaired = listOf(ogg)),
            legacy = StubSegmentStore(repaired = listOf(wav)),
            cleanup = StubCleanupFiles(),
        )

        assertEquals(listOf(wav, ogg), store.repairOpenSegments())
    }

    private fun ready(id: String, path: String) = ReadySegment(
        id = SegmentId(id),
        blockId = BlockId("block"),
        path = path,
        durationMs = 1_000,
        sha256 = "hash-$id",
    )
}

private class StubSegmentStore(
    private val openPath: String = "unused.open.ogg",
    private val repaired: List<ReadySegment> = emptyList(),
) : SegmentStore {
    override suspend fun open(blockId: BlockId, ordinal: Int) =
        OpenSegment(SegmentId("new"), blockId, openPath)

    override suspend fun append(segment: OpenSegment, pcm: ShortArray, count: Int) = Unit
    override suspend fun close(segment: OpenSegment) = readyFrom(segment)
    override suspend fun repairOpenSegments() = repaired
    override suspend fun delete(segmentId: SegmentId) = DeleteResult.Deleted

    private fun readyFrom(segment: OpenSegment) = ReadySegment(
        segment.id,
        segment.blockId,
        segment.path.replace(".open.", ".ready."),
        1_000,
        "hash",
    )
}

private class StubCleanupFiles : CleanupFileStore {
    override suspend fun delete(segmentId: SegmentId) = DeleteResult.Deleted
    override suspend fun exists(segmentId: SegmentId) = true
}
