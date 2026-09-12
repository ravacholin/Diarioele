package com.capo.diarioclase.recording.audio
import com.capo.diarioclase.data.db.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
class PersistingSegmentStoreTest{
 @Test fun `ready metadata is persisted only after file closes`()=runTest{
  val events=mutableListOf<String>();val files=OrderedFileStore(events);val metadata=MemoryMetadata(events);val store=PersistingSegmentStore(files,metadata)
  val open=store.open(BlockId("b"),0);store.append(open,shortArrayOf(1),1);store.close(open)
  assertEquals(listOf(SegmentState.OPEN,SegmentState.READY),metadata.states);assertEquals(listOf("db-open","file-close","db-ready"),events)
 }
}
private class OrderedFileStore(private val events:MutableList<String>):SegmentStore{override suspend fun open(blockId:BlockId,ordinal:Int)=OpenSegment(SegmentId("s"),blockId,"open");override suspend fun append(segment:OpenSegment,pcm:ShortArray,count:Int){};override suspend fun close(segment:OpenSegment):ReadySegment{events+="file-close";return ReadySegment(segment.id,segment.blockId,"ready",1_000,"hash")};override suspend fun repairOpenSegments()=emptyList<ReadySegment>();override suspend fun delete(segmentId:SegmentId)=DeleteResult.Deleted}
private class MemoryMetadata(private val events:MutableList<String>):SegmentMetadataStore{val states=mutableListOf<SegmentState>();override suspend fun saveOpen(segment:OpenSegment,ordinal:Int){states+=SegmentState.OPEN;events+="db-open"};override suspend fun saveReady(segment:ReadySegment){states+=SegmentState.READY;events+="db-ready"};override suspend fun markDeleted(segmentId:SegmentId){states+=SegmentState.DELETED}}
