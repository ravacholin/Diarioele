package com.capo.diarioclase.processing.work

import com.capo.diarioclase.data.db.*
import com.capo.diarioclase.processing.evidence.*
import com.capo.diarioclase.processing.transcription.*
import com.capo.diarioclase.recording.audio.ReadySegment
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TranscriptionCoordinatorTest {
 @Test fun `checkpoints every segment and creates five field draft`()=runTest{
  val store=FakeStore();val engine=TranscriptionEngine{segment->TranscriptResult.Success(listOf(TranscriptSpan("t-${segment.id.value}",segment.id.value,segment.blockId,0,1000,if(segment.id.value=="a")"Vamos a la página cuarenta y dos" else "Hacemos el ejercicio tres",.9)))}
  val result=TranscriptionCoordinator(store,engine,LiteralClaimExtractor{store.nextId()}).process(SessionId("day"),InterpretationMode.CONSERVATIVE)
  assertTrue(result is ProcessingOutcome.Complete);assertEquals(listOf("a","b"),store.saved);assertEquals("42",store.draft?.pages);assertEquals("3 (p. 42)",store.draft?.exercises);assertEquals(SessionState.AWAITING_REVIEW,store.state)
 }
 @Test fun `failure stops later work and keeps previous checkpoint`()=runTest{
  val store=FakeStore();var call=0;val engine=TranscriptionEngine{segment->if(call++==0)TranscriptResult.Success(listOf(TranscriptSpan("t",segment.id.value,segment.blockId,0,1000,"Página diez",.9)))else TranscriptResult.Failure(TranscriptionFailure.NO_SPEECH,true)}
  val result=TranscriptionCoordinator(store,engine).process(SessionId("day"),InterpretationMode.CONSERVATIVE)
  assertTrue(result is ProcessingOutcome.Failed);assertEquals(listOf("a"),store.saved);assertEquals(listOf("b"),store.failed)
 }
 @Test fun `user edit survives mode change` ()=runTest{
  val store=FakeStore().apply{existingDraft=DiaryDraftEntity("draft","day",InterpretationMode.CONSERVATIVE.name,"Tema manual","Actividad manual","12","3","Tarea manual",1_000,true)}
  val engine=TranscriptionEngine{segment->TranscriptResult.Success(listOf(TranscriptSpan("t-${segment.id.value}",segment.id.value,segment.blockId,0,1000,"Página diez",.9)))}

  val result=TranscriptionCoordinator(store,engine).process(SessionId("day"),InterpretationMode.EXHAUSTIVE)

  val draft=(result as ProcessingOutcome.Complete).draft
  assertEquals(InterpretationMode.EXHAUSTIVE,draft.mode);assertEquals("Tema manual",draft.topics);assertEquals("Actividad manual",draft.activities);assertEquals("12",draft.pages);assertEquals("3",draft.exercises);assertEquals("Tarea manual",draft.homework)
 }
}
private class FakeStore:ProcessingStore{
 var state=SessionState.FINALIZED;var counter=0;val saved=mutableListOf<String>();val failed=mutableListOf<String>();var spans=mutableListOf<TranscriptSpan>();var draft:DiaryDraft?=null;var existingDraft:DiaryDraftEntity?=null
 fun nextId()="claim-${counter++}"
 override suspend fun sessionState(id:SessionId)=state
 override suspend fun segments(id:SessionId)=listOf("a","b").mapIndexed{i,name->ProcessableSegment(ReadySegment(SegmentId(name),BlockId("block"),"/$name.wav",1000,"hash"),i,if(name in saved)SegmentState.TRANSCRIBED else SegmentState.READY)}
 override suspend fun markTranscribing(id:SegmentId)=Unit
 override suspend fun saveTranscript(id:SegmentId,spans:List<TranscriptSpan>){saved+=id.value;this.spans+=spans}
 override suspend fun markFailed(id:SegmentId,failure:TranscriptionFailure){failed+=id.value}
 override suspend fun transcript(id:SessionId)=spans
 override suspend fun draft(id:SessionId)=existingDraft
 override suspend fun saveEvidence(id:SessionId,claims:List<EvidenceClaim>,draft:DiaryDraft){this.draft=draft}
 override suspend fun updateSession(id:SessionId,state:SessionState){this.state=state}
}
