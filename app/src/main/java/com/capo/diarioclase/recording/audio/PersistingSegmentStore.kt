package com.capo.diarioclase.recording.audio
import com.capo.diarioclase.data.db.*
interface SegmentMetadataStore { suspend fun saveOpen(segment:OpenSegment,ordinal:Int);suspend fun saveReady(segment:ReadySegment);suspend fun markDeleted(segmentId:SegmentId) }
class PersistingSegmentStore(private val files:SegmentStore,private val metadata:SegmentMetadataStore):SegmentStore{
 override suspend fun open(blockId:BlockId,ordinal:Int)=files.open(blockId,ordinal).also{metadata.saveOpen(it,ordinal)}
 override suspend fun append(segment:OpenSegment,pcm:ShortArray,count:Int)=files.append(segment,pcm,count)
 override suspend fun close(segment:OpenSegment)=files.close(segment).also{metadata.saveReady(it)}
 override suspend fun abort(segment:OpenSegment)=files.abort(segment)
 override suspend fun repairOpenSegments()=files.repairOpenSegments().also{ready->ready.forEach{metadata.saveReady(it)}}
 override suspend fun delete(segmentId:SegmentId):DeleteResult=files.delete(segmentId).also{if(it is DeleteResult.Deleted)metadata.markDeleted(segmentId)}
 override suspend fun exists(segmentId:SegmentId)=files.exists(segmentId)
}
