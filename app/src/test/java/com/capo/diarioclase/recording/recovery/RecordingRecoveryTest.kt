package com.capo.diarioclase.recording.recovery
import com.capo.diarioclase.data.db.*
import com.capo.diarioclase.data.repository.SessionRepository
import com.capo.diarioclase.recording.audio.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RecordingRecoveryTest {
 @Test fun `open segments are repaired before recovery prompt`()=runTest{
  val events=mutableListOf<String>();val session=SessionAggregate(SessionId("s"),null,SessionState.PAUSED,listOf(BlockId("b")),0)
  val recovery=RecordingRecovery(RecoverySessions(session,events),RecoverySegments(events),MemoryMarkers())
  val decision=recovery.onAppStart()
  assertEquals(listOf("repair","session"),events);assertEquals(RecoveryDecision.ResumeOrFinalize(SessionId("s")),decision)
 }
 @Test fun `homework marker stores block relative timestamp`()=runTest{
  val markers=MemoryMarkers();val recovery=RecordingRecovery(RecoverySessions(null,mutableListOf()),RecoverySegments(mutableListOf()),markers)
  recovery.markHomework(SessionId("s"),BlockId("b"),100_000,130_000)
  assertEquals(30_000,markers.items.single().offsetMs)
 }
 @Test fun `recording block is closed as interrupted after process recovery`()=runTest{
  val sessions=RecoverySessions(SessionAggregate(SessionId("s"),null,SessionState.RECORDING,listOf(BlockId("b")),0),mutableListOf())
  RecordingRecovery(sessions,RecoverySegments(mutableListOf()),MemoryMarkers()).onAppStart()
  assertEquals(BlockId("b"),sessions.closedBlock);assertEquals(BlockCloseReason.INTERRUPTED,sessions.closeReason)
 }
}
private class RecoverySessions(private val session:SessionAggregate?,private val events:MutableList<String>):SessionRepository{var updatedState:SessionState?=null;var closedBlock:BlockId?=null;var closeReason:BlockCloseReason?=null
 override fun observeActiveSession():Flow<SessionAggregate?> = flow{events+="session";emit(session)}
 override fun observeLatestFinalizedRecording():Flow<RecordingReport?> = flowOf(null)
 override suspend fun createSession(level:CerLevel?)=error("");override suspend fun startBlock(sessionId:SessionId)=error("");override suspend fun closeBlock(blockId:BlockId,reason:BlockCloseReason){closedBlock=blockId;closeReason=reason};override suspend fun finalizeSession(sessionId:SessionId){};override suspend fun updateSessionState(sessionId:SessionId,state:SessionState){updatedState=state}
}
private class RecoverySegments(private val events:MutableList<String>):SegmentStore{
 override suspend fun repairOpenSegments():List<ReadySegment>{events+="repair";return emptyList()};override suspend fun open(blockId:BlockId,ordinal:Int)=error("");override suspend fun append(segment:OpenSegment,pcm:ShortArray,count:Int){};override suspend fun close(segment:OpenSegment)=error("");override suspend fun delete(segmentId:SegmentId)=DeleteResult.Deleted
}
private class MemoryMarkers:MarkerStore{val items=mutableListOf<HomeworkMarker>();override suspend fun save(marker:HomeworkMarker){items+=marker}}
