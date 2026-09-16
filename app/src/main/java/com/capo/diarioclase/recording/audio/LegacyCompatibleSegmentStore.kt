package com.capo.diarioclase.recording.audio

import com.capo.diarioclase.data.db.BlockId
import com.capo.diarioclase.data.db.SegmentId

/**
 * Writes new recordings through [primary] while retaining recovery and cleanup
 * compatibility with segments created by the former WAV implementation.
 */
class LegacyCompatibleSegmentStore(
    private val primary: SegmentStore,
    private val legacy: SegmentStore,
    private val cleanup: CleanupFileStore,
) : SegmentStore {
    override suspend fun open(blockId: BlockId, ordinal: Int): OpenSegment =
        primary.open(blockId, ordinal)

    override suspend fun append(segment: OpenSegment, pcm: ShortArray, count: Int) =
        primary.append(segment, pcm, count)

    override suspend fun close(segment: OpenSegment): ReadySegment =
        primary.close(segment)

    override suspend fun repairOpenSegments(): List<ReadySegment> =
        legacy.repairOpenSegments() + primary.repairOpenSegments()

    override suspend fun delete(segmentId: SegmentId): DeleteResult =
        cleanup.delete(segmentId)

    override suspend fun exists(segmentId: SegmentId): Boolean =
        cleanup.exists(segmentId)
}
