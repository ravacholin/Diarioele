package com.capo.diarioclase.ui.capture
import com.capo.diarioclase.data.db.*
import com.capo.diarioclase.data.repository.SessionRepository
import com.capo.diarioclase.diary.cleanup.CleanupOutcome
import com.capo.diarioclase.diary.DiaryEntry
import com.capo.diarioclase.processing.evidence.InterpretationMode
import com.capo.diarioclase.processing.transcription.TranscriptionFailure
import com.capo.diarioclase.processing.work.TranscriptionProgress
import com.capo.diarioclase.processing.work.TranscriptionRunState
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CaptureViewModelTest {
 @Test fun `draft editor locks while mode reprocessing is busy`(){assertTrue(draftEditorEnabled(false));assertFalse(draftEditorEnabled(true))}
 @Test fun `changing mode saves all unsaved fields before reprocessing`()=runTest{
  val report=RecordingReport(SessionId("s"),"2026-09-11",emptyList(),SessionState.AWAITING_REVIEW)
  val original=DiaryDraftEntity("draft","s","CONSERVATIVE","old","old","1","2","old",1)
  val drafts=MutableStateFlow<DiaryDraftEntity?>(original)
  val reprocessStarted=CompletableDeferred<Unit>();val finishReprocess=CompletableDeferred<Unit>();var saveCalls=0;var resumeCalls=0
  val delegated=UiActions()
  val actions=object:CaptureActions by delegated{
   override suspend fun saveDraft(draft:DiaryDraftEntity){saveCalls++;drafts.value=draft}
   override suspend fun resumeProcessing(id:SessionId,mode:InterpretationMode){
    resumeCalls++
    val saved=drafts.value!!
    assertEquals(listOf("tema escrito","actividad oral","42","7","tarea escrita"),listOf(saved.topics,saved.activities,saved.pages,saved.exercises,saved.homework))
    assertTrue(saved.userEdited)
    reprocessStarted.complete(Unit);finishReprocess.await()
    drafts.value=saved.copy(mode=mode.name,updatedAtEpochMs=2)
   }
  }
  val vm=CaptureViewModel(UiSessions(null,report),actions,backgroundScope,drafts);runCurrent()
  vm.onMode(InterpretationMode.EXHAUSTIVE,"tema escrito","actividad oral","42","7","tarea escrita");reprocessStarted.await();runCurrent()
  assertTrue(vm.state.value.busy)
  vm.onMode(InterpretationMode.BALANCED,"reemplazo","reemplazo","0","0","reemplazo")
  vm.onApprove("reemplazo","reemplazo","0","0","reemplazo");runCurrent()
  assertEquals(1,saveCalls);assertEquals(1,resumeCalls);assertEquals(0,delegated.approvalCalls)
  finishReprocess.complete(Unit);runCurrent()
  assertEquals("tema escrito",vm.state.value.draft!!.topics)
  assertEquals("EXHAUSTIVE",vm.state.value.draft!!.mode)
 }
 @Test fun `restart surfaces pending cleanup without retrying it`()=runTest{
  val pending=RecordingReport(SessionId("s"),"2026-09-12",emptyList(),SessionState.CLEANUP_PENDING)
  val entry=DiaryEntry("diary",SessionId("s"),"2026-09-12",null,"Narración","Lectura","12","3","Tarea",1,1,false)
  val actions=UiActions();val vm=CaptureViewModel(UiSessions(null),actions,backgroundScope,pendingRecordings=flowOf(pending),diaries=flowOf(listOf(entry)));runCurrent()
  assertEquals(CaptureStatus.CLEANUP_PENDING,vm.state.value.status);assertEquals("diary",vm.state.value.diaryId);assertEquals("Narración",vm.state.value.draft?.topics);assertEquals(null,actions.retriedSessionId)
 }
 @Test fun `new repository session can replace archived screen for another day`()=runTest{
  val report=RecordingReport(SessionId("s"),"2026-09-11",emptyList(),SessionState.AWAITING_REVIEW)
  val draft=DiaryDraftEntity("draft","s","CONSERVATIVE","tema","actividad","1","2","tarea",1)
  val repository=UiSessions(null,report)
  val vm=CaptureViewModel(repository,UiActions(),backgroundScope,flowOf(draft));runCurrent()
  vm.onApprove("tema","actividad","1","2","tarea");runCurrent();assertEquals(CaptureStatus.ARCHIVED,vm.state.value.status)
  repository.emit(SessionAggregate(SessionId("next-day"),null,SessionState.RECORDING,listOf(BlockId("next-block")),0));runCurrent()
  assertEquals(CaptureStatus.RECORDING,vm.state.value.status);assertEquals(SessionId("next-day"),vm.state.value.sessionId)
 }
 @Test fun `pause appears only after repository confirms it`()=runTest{
  val repo=UiSessions(SessionAggregate(SessionId("s"),null,SessionState.RECORDING,listOf(BlockId("b")),4_000));val actions=UiActions();val vm=CaptureViewModel(repo,actions,backgroundScope);runCurrent()
  vm.onPause();runCurrent();assertEquals(CaptureStatus.RECORDING,vm.state.value.status);assertEquals(1,actions.pauses)
  repo.emit(SessionAggregate(SessionId("s"),null,SessionState.PAUSED,listOf(BlockId("b")),4_000));runCurrent();assertEquals(CaptureStatus.PAUSED,vm.state.value.status)
 }
 @Test fun `finalized recording becomes a visible verification report`()=runTest{
  val report=RecordingReport(SessionId("s"),"2026-09-11",listOf(BlockRecordingSummary(BlockId("b"),1,2_000,BlockCloseReason.FINALIZED,listOf(SegmentSummary(SegmentId("seg"),"/private/seg.ready.wav",2_000,64_044,SegmentState.READY)))))
  val repo=UiSessions(null,report);val vm=CaptureViewModel(repo,UiActions(),backgroundScope);runCurrent();assertEquals(CaptureStatus.REVIEW,vm.state.value.status);assertEquals(report,vm.state.value.lastRecording)
 }
 @Test fun `processing starts through the scheduler`()=runTest{
  val report=RecordingReport(SessionId("s"),"2026-09-11",listOf(BlockRecordingSummary(BlockId("b"),1,2_000,BlockCloseReason.FINALIZED,listOf(SegmentSummary(SegmentId("seg"),"/private/seg.ready.wav",2_000,64_044,SegmentState.READY)))))
  val actions=UiActions();val vm=CaptureViewModel(UiSessions(null,report),actions,backgroundScope);runCurrent()
  vm.onProcess(InterpretationMode.BALANCED);runCurrent()
  assertEquals(SessionId("s"),actions.startedSessionId);assertEquals(InterpretationMode.BALANCED,actions.startedMode)
 }
 @Test fun `persistent progress is shown as percent and processed time`()=runTest{
  val report=RecordingReport(SessionId("s"),"2026-09-11",emptyList(),SessionState.TRANSCRIBING)
  val progress=MutableStateFlow<TranscriptionProgress?>(TranscriptionProgress(SessionId("s"),30_000,60_000,0,0,0,TranscriptionRunState.PROCESSING,null))
  val vm=CaptureViewModel(UiSessions(null,report),UiActions(),backgroundScope,observeProgress={progress});runCurrent()
  assertEquals(CaptureStatus.PROCESSING,vm.state.value.status)
  assertEquals(50,vm.state.value.progressPercent);assertEquals(30_000,vm.state.value.processedMs);assertEquals(60_000,vm.state.value.processingTotalMs)
  assertFalse(vm.state.value.transcriptionPaused);assertNull(vm.state.value.progressLabel)
 }
 @Test fun `preparing state shows model preparation label`()=runTest{
  val report=RecordingReport(SessionId("s"),"2026-09-11",emptyList(),SessionState.TRANSCRIBING)
  val progress=MutableStateFlow<TranscriptionProgress?>(TranscriptionProgress(SessionId("s"),0,0,0,0,0,TranscriptionRunState.PREPARING,null))
  val vm=CaptureViewModel(UiSessions(null,report),UiActions(),backgroundScope,observeProgress={progress});runCurrent()
  assertEquals(CaptureStatus.PROCESSING,vm.state.value.status);assertEquals("PREPARANDO MODELO",vm.state.value.progressLabel)
 }
 @Test fun `pausing keeps the recording available`()=runTest{
  val report=RecordingReport(SessionId("s"),"2026-09-11",listOf(BlockRecordingSummary(BlockId("b"),1,60_000,BlockCloseReason.FINALIZED,listOf(SegmentSummary(SegmentId("seg"),"/private/seg.ready.wav",60_000,1_920_044,SegmentState.TRANSCRIBING)))),SessionState.TRANSCRIBING)
  val progress=MutableStateFlow<TranscriptionProgress?>(TranscriptionProgress(SessionId("s"),20_000,60_000,0,0,0,TranscriptionRunState.PAUSED,null))
  val vm=CaptureViewModel(UiSessions(null,report),UiActions(),backgroundScope,observeProgress={progress});runCurrent()
  assertEquals(CaptureStatus.PROCESSING,vm.state.value.status);assertTrue(vm.state.value.transcriptionPaused);assertEquals(report,vm.state.value.lastRecording)
 }
 @Test fun `resume processing reuses the same session`()=runTest{
  val report=RecordingReport(SessionId("s"),"2026-09-11",emptyList(),SessionState.TRANSCRIBING)
  val progress=MutableStateFlow<TranscriptionProgress?>(TranscriptionProgress(SessionId("s"),20_000,60_000,0,0,0,TranscriptionRunState.PAUSED,null))
  val actions=UiActions();val vm=CaptureViewModel(UiSessions(null,report),actions,backgroundScope,observeProgress={progress});runCurrent()
  vm.onResumeProcessing(InterpretationMode.CONSERVATIVE);runCurrent()
  assertEquals(SessionId("s"),actions.resumedSessionId);assertEquals(InterpretationMode.CONSERVATIVE,actions.resumedMode)
 }
 @Test fun `completed transcription with draft becomes an editable card`()=runTest{
  val report=RecordingReport(SessionId("s"),"2026-09-11",emptyList(),SessionState.AWAITING_REVIEW)
  val draft=DiaryDraftEntity("draft","s","CONSERVATIVE","tema","actividad","1","2","tarea",1)
  val progress=MutableStateFlow<TranscriptionProgress?>(TranscriptionProgress(SessionId("s"),60_000,60_000,0,0,0,TranscriptionRunState.COMPLETED,null))
  val vm=CaptureViewModel(UiSessions(null,report),UiActions(),backgroundScope,flowOf(draft),observeProgress={progress});runCurrent()
  assertEquals(CaptureStatus.DRAFT,vm.state.value.status);assertEquals("tema",vm.state.value.draft?.topics)
 }
 @Test fun `homework marker releases busy state without repository transition`()=runTest{
  val aggregate=SessionAggregate(SessionId("s"),null,SessionState.RECORDING,listOf(BlockId("b")),4_000);val vm=CaptureViewModel(UiSessions(aggregate),UiActions(),backgroundScope);runCurrent()
  vm.onMarkHomework();runCurrent()
  assertFalse(vm.state.value.busy);assertEquals(1,vm.state.value.homeworkMarkers)
 }
 @Test fun `approval forwards latest edited fields`()=runTest{
  val actions=UiActions();val vm=reviewViewModel(actions,backgroundScope);runCurrent()
  vm.onApprove("tema editado","actividad editada","12","3","tarea editada");runCurrent()
  assertEquals("tema editado",actions.approvedDraft!!.topics);assertEquals("actividad editada",actions.approvedDraft!!.activities);assertEquals("12",actions.approvedDraft!!.pages);assertEquals("3",actions.approvedDraft!!.exercises);assertEquals("tarea editada",actions.approvedDraft!!.homework)
 }
 @Test fun `approval enters pending cleanup and retry archives the saved diary`()=runTest{
  val actions=UiActions().apply{cleanupResult=CleanupOutcome.Pending("diary",1)};val vm=reviewViewModel(actions,backgroundScope);runCurrent()
  vm.onApprove("tema","actividad","12","3","tarea");runCurrent();assertEquals(CaptureStatus.CLEANUP_PENDING,vm.state.value.status);assertEquals("diary",vm.state.value.diaryId)
  actions.retryResult=CleanupOutcome.Archived("diary");vm.onRetryCleanup();runCurrent();assertEquals(SessionId("s"),actions.retriedSessionId);assertEquals(CaptureStatus.ARCHIVED,vm.state.value.status)
 }
 @Test fun `retry save failure remains pending and retryable`()=runTest{
  val actions=UiActions().apply{cleanupResult=CleanupOutcome.Pending("diary",1);retryResult=CleanupOutcome.SaveFailed("Lectura temporal no disponible")};val vm=reviewViewModel(actions,backgroundScope);runCurrent()
  vm.onApprove("tema","actividad","12","3","tarea");runCurrent();vm.onRetryCleanup();runCurrent()
  assertEquals(CaptureStatus.CLEANUP_PENDING,vm.state.value.status);assertEquals("diary",vm.state.value.diaryId);assertFalse(vm.state.value.busy);assertTrue(vm.state.value.message!!.contains("segura"))
 }
 @Test fun `duplicate approval tap is ignored while cleanup is busy`()=runTest{
  val actions=UiActions().apply{holdApproval=true};val vm=reviewViewModel(actions,backgroundScope);runCurrent()
  vm.onApprove("tema","actividad","12","3","tarea");vm.onApprove("otro tema","actividad","12","3","tarea");runCurrent()
  assertEquals(1,actions.approvalCalls);assertEquals(CaptureStatus.APPROVING,vm.state.value.status)
 }
 @Test fun `transcription timeout is visible and confirms audio remains saved`()=runTest{
  val report=RecordingReport(SessionId("s"),"2026-09-11",listOf(BlockRecordingSummary(BlockId("b"),1,60_000,BlockCloseReason.FINALIZED,listOf(SegmentSummary(SegmentId("seg"),"/private/seg.ready.wav",60_000,1_920_044,SegmentState.READY)))),SessionState.TRANSCRIBING)
  val progress=MutableStateFlow<TranscriptionProgress?>(TranscriptionProgress(SessionId("s"),20_000,60_000,0,0,0,TranscriptionRunState.FAILED,TranscriptionFailure.TIMEOUT))
  val vm=CaptureViewModel(UiSessions(null,report),UiActions(),backgroundScope,observeProgress={progress});runCurrent()
  assertEquals(CaptureStatus.REVIEW,vm.state.value.status)
  assertEquals("La transcripción local no respondió a tiempo. El audio sigue guardado",vm.state.value.message)
  assertEquals(TranscriptionFailure.TIMEOUT,vm.state.value.processingFailure);assertEquals(report,vm.state.value.lastRecording)
 }
 @Test fun `room refresh preserves the specific transcription failure`()=runTest{
  val failed=SegmentSummary(SegmentId("seg"),"/private/seg.ready.wav",10_000,320_044,SegmentState.FAILED,true,TranscriptionFailure.AUDIO_SOURCE_UNSUPPORTED.name)
  val report=RecordingReport(SessionId("s"),"2026-09-11",listOf(BlockRecordingSummary(BlockId("b"),1,10_000,BlockCloseReason.FINALIZED,listOf(failed))),SessionState.TRANSCRIBING)
  val vm=CaptureViewModel(UiSessions(null,report),UiActions(),backgroundScope);runCurrent()
  assertEquals("Este teléfono no admite desgrabar el audio guardado con el motor local. El audio sigue seguro",vm.state.value.message)
 }
 private fun reviewViewModel(actions:CaptureActions,scope:kotlinx.coroutines.CoroutineScope):CaptureViewModel{
  val report=RecordingReport(SessionId("s"),"2026-09-11",emptyList(),SessionState.AWAITING_REVIEW);val draft=DiaryDraftEntity("draft","s","CONSERVATIVE","tema","actividad","1","2","tarea",1)
  return CaptureViewModel(UiSessions(null,report),actions,scope,flowOf(draft))
 }
}
private class UiSessions(initial:SessionAggregate?,report:RecordingReport?=null):SessionRepository{private val flow=MutableStateFlow(initial);private val reportFlow=MutableStateFlow(report);fun emit(x:SessionAggregate?){flow.value=x};override fun observeActiveSession():Flow<SessionAggregate?> = flow;override fun observeLatestFinalizedRecording():Flow<RecordingReport?> = reportFlow;override suspend fun createSession(level:CerLevel?)=SessionId("s");override suspend fun startBlock(sessionId:SessionId)=BlockId("b");override suspend fun closeBlock(blockId:BlockId,reason:BlockCloseReason){};override suspend fun finalizeSession(sessionId:SessionId){};override suspend fun updateSessionState(sessionId:SessionId,state:SessionState){} }
private class UiActions:CaptureActions{var pauses=0;var approvalCalls=0;var approvedDraft:DiaryDraftEntity?=null;var cleanupResult:CleanupOutcome=CleanupOutcome.Archived("diary");var retryResult:CleanupOutcome=CleanupOutcome.Archived("diary");var retriedSessionId:SessionId?=null;var startedSessionId:SessionId?=null;var startedMode:InterpretationMode?=null;var resumedSessionId:SessionId?=null;var resumedMode:InterpretationMode?=null;var pausedProcessingId:SessionId?=null;var holdApproval=false;override suspend fun startNewDay(){};override suspend fun resume(id:SessionId){};override suspend fun pause(){pauses++};override suspend fun markHomework(sessionId:SessionId,blockId:BlockId){};override suspend fun finalizeDay(id:SessionId,state:SessionState){};override suspend fun startProcessing(id:SessionId,mode:InterpretationMode){startedSessionId=id;startedMode=mode};override suspend fun pauseProcessing(id:SessionId){pausedProcessingId=id};override suspend fun resumeProcessing(id:SessionId,mode:InterpretationMode){resumedSessionId=id;resumedMode=mode};override suspend fun approveAndClean(draft:DiaryDraftEntity):CleanupOutcome{approvalCalls++;approvedDraft=draft;if(holdApproval)awaitCancellation();return cleanupResult};override suspend fun retryCleanup(sessionId:SessionId):CleanupOutcome{retriedSessionId=sessionId;return retryResult}}
