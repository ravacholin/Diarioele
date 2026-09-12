package com.capo.diarioclase.data.repository
import com.capo.diarioclase.data.db.*
import com.capo.diarioclase.recording.audio.*
import java.io.File
class RoomSegmentMetadataStore(private val dao:SessionDao):SegmentMetadataStore{
 override suspend fun saveOpen(segment:OpenSegment,ordinal:Int){dao.saveSegment(AudioSegmentEntity(segment.id.value,segment.blockId.value,ordinal,segment.path,File(segment.path).length(),0,null,SegmentState.OPEN.name))}
 override suspend fun saveReady(segment:ReadySegment){val old=dao.segment(segment.id.value);dao.saveSegment(AudioSegmentEntity(segment.id.value,segment.blockId.value,old?.ordinal?:0,segment.path,File(segment.path).length(),segment.durationMs,segment.sha256,SegmentState.READY.name,old?.transcriptionAttempts?:0))}
 override suspend fun markDeleted(segmentId:SegmentId){dao.segment(segmentId.value)?.let{dao.saveSegment(it.copy(state=SegmentState.DELETED.name))}}
}
