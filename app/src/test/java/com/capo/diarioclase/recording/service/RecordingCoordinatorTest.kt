package com.capo.diarioclase.recording.service
import com.capo.diarioclase.core.clock.Clock
import com.capo.diarioclase.data.db.*
import com.capo.diarioclase.data.repository.SessionRepository
import com.capo.diarioclase.recording.audio.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RecordingCoordinatorTest {
 @Test fun `rotates at one minute without losing remainder`()=runTest{
  val store=MemorySegments();val coordinator=RecordingCoordinator(
   sessions=FakeSessions(),segments=store,sourceFactory={FiniteSource(16_000*61)},clock=Clock{0},scope=this,
   maxSegmentSamples=16_000*60,
  )
  coordinator.recordBlock(BlockId("b"),FiniteSource(16_000*61))
  assertEquals(listOf(60_000L,1_000L),store.closedDurations)
 }
 @Test fun `three minutes closes segment before opening next`()=runTest{
  val store=MemorySegments();val coordinator=RecordingCoordinator(FakeSessions(),store,{FiniteSource(16_000*181)},Clock{0},this)
  coordinator.recordBlock(BlockId("b"),FiniteSource(16_000*181))
  assertEquals(listOf(180_000L,1_000L),store.closedDurations)
 }
 @Test fun `pause closes current segment and block`()=runTest{
  val sessions=FakeSessions();val store=MemorySegments();val coordinator=RecordingCoordinator(sessions,store,{HoldingSource()},Clock{0},this)
  coordinator.start(SessionId("s"));runCurrent();coordinator.pause()
  assertFalse(store.open);assertEquals(BlockCloseReason.PAUSED,sessions.lastCloseReason)
 }
 @Test fun `microphone startup failure closes block as interrupted`()=runTest{
  val sessions=FakeSessions();val coordinator=RecordingCoordinator(sessions,MemorySegments(),{error("Micrófono no disponible")},Clock{0},this)
  val failure=runCatching{coordinator.start(SessionId("s"))}.exceptionOrNull()
  assertEquals("Micrófono no disponible",failure?.message);assertEquals(BlockCloseReason.INTERRUPTED,sessions.lastCloseReason)
 }
 @Test fun `microphone read failure closes block and stops service`()=runTest{
  val sessions=FakeSessions();val store=MemorySegments();var stopped=false;val coordinator=RecordingCoordinator(sessions,store,{FailingSource()},Clock{0},this){stopped=true}
  coordinator.start(SessionId("s"));runCurrent()
  assertEquals(BlockCloseReason.INTERRUPTED,sessions.lastCloseReason);assertTrue(store.aborted);assertTrue(stopped)
 }
}
private class FiniteSource(private var remaining:Int):PcmSource{
 override suspend fun read(target:ShortArray):Int{if(remaining==0)return -1;val n=minOf(remaining,target.size);remaining-=n;return n}
 override fun close(){}
}
private class HoldingSource:PcmSource{private var first=true;override suspend fun read(target:ShortArray):Int{if(first){first=false;return target.size};awaitCancellation()};override fun close(){}}
private class FailingSource:PcmSource{private var first=true;override suspend fun read(target:ShortArray):Int{if(first){first=false;return target.size};error("Lectura fallida")};override fun close(){}}
private class MemorySegments:SegmentStore{
 var open=false;var aborted=false;val closedDurations=mutableListOf<Long>();private val counts=mutableMapOf<String,Int>()
 override suspend fun open(blockId:BlockId,ordinal:Int):OpenSegment{open=true;val id=SegmentId("seg-$ordinal");counts[id.value]=0;return OpenSegment(id,blockId,File("$ordinal.open.wav").path)}
 override suspend fun append(segment:OpenSegment,pcm:ShortArray,count:Int){counts[segment.id.value]=counts.getValue(segment.id.value)+count}
 override suspend fun close(segment:OpenSegment):ReadySegment{open=false;val d=counts.getValue(segment.id.value)*1_000L/16_000;closedDurations+=d;return ReadySegment(segment.id,segment.blockId,"ready",d,"hash")}
 override suspend fun abort(segment:OpenSegment){aborted=true;open=false}
 override suspend fun repairOpenSegments()=emptyList<ReadySegment>();override suspend fun delete(segmentId:SegmentId)=DeleteResult.Deleted
}
private class FakeSessions:SessionRepository{
 private val state=MutableStateFlow<SessionAggregate?>(null);var lastCloseReason:BlockCloseReason?=null
 override fun observeActiveSession():Flow<SessionAggregate?> = state
 override fun observeLatestFinalizedRecording():Flow<RecordingReport?> = MutableStateFlow(null)
 override suspend fun createSession(level:CerLevel?)=SessionId("s")
 override suspend fun startBlock(sessionId:SessionId)=BlockId("b")
 override suspend fun closeBlock(blockId:BlockId,reason:BlockCloseReason){lastCloseReason=reason}
 override suspend fun finalizeSession(sessionId:SessionId){}
 override suspend fun updateSessionState(sessionId:SessionId,state:SessionState){}
}
