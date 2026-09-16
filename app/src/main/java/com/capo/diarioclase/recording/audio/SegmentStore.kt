package com.capo.diarioclase.recording.audio
import com.capo.diarioclase.data.db.*
data class OpenSegment(val id:SegmentId,val blockId:BlockId,val path:String)
data class ReadySegment(val id:SegmentId,val blockId:BlockId,val path:String,val durationMs:Long,val sha256:String,val state:SegmentState=SegmentState.READY)
sealed interface DeleteResult { data object Deleted:DeleteResult; data class Failed(val reason:String):DeleteResult }
/** File-only dependency for verified cleanup. Metadata-decorating stores must not implement this. */
interface CleanupFileStore {
 suspend fun delete(segmentId:SegmentId):DeleteResult
 suspend fun exists(segmentId:SegmentId):Boolean
}
interface SegmentStore {
 suspend fun open(blockId:BlockId,ordinal:Int):OpenSegment
 suspend fun append(segment:OpenSegment,pcm:ShortArray,count:Int)
 suspend fun close(segment:OpenSegment):ReadySegment
 /** Releases an in-flight writer after an unexpected failure without promoting its source. */
 suspend fun abort(segment:OpenSegment) = Unit
 suspend fun repairOpenSegments():List<ReadySegment>
 suspend fun delete(segmentId:SegmentId):DeleteResult
 suspend fun exists(segmentId:SegmentId):Boolean = true
}
