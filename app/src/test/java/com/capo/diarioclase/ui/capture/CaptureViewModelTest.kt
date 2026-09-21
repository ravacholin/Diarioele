package com.capo.diarioclase.ui.capture
import com.capo.diarioclase.data.db.*
import com.capo.diarioclase.data.repository.SessionRepository
import com.capo.diarioclase.diary.cleanup.CleanupOutcome
import com.capo.diarioclase.diary.DiaryEntry
import com.capo.diarioclase.processing.evidence.InterpretationMode
import com.capo.diarioclase.processing.transcription.TranscriptionFailure
import com.capo.diarioclase.processing.work.TranscriptionProgress
import com.capo.diarioclase.processing.work.TranscriptionRunState
import com.capo.diarioclase.processing.editorial.EditorialReportState
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CaptureViewModelTest {
 @Test fun `stale editorial report is exposed after review`()=runTest{
  val report=RecordingReport(SessionId("s"),"2026-09-21",emptyList(),SessionState.AWAITING_REVIEW)
  val draft=DiaryDraftEntity("draft","s","CONSERVATIVE","tema","actividad","1","2","tarea",1)
  val editorial=MutableStateFlow<EditorialReportEntity?>(readyEditorialEntity().copy(state="STALE"))
  val vm=CaptureViewModel(UiSessions(null,report),UiActions(),backgroundScope,flowOf(draft),editorialReports=editorial);runCurrent()
  assertEquals(EditorialReportState.STALE,vm.state.value.editorial?.state)
  assertNull(vm.state.value.editorial?.report)
 }
 @Test fun `regenerate editorial report never schedules transcription`()=runTest{
  val actions=UiActions();val vm=reviewViewModel(actions,backgroundScope);runCurrent()
  vm.onRegenerateEditorialReport(InterpretationMode.BALANCED);runCurrent()
  assertEquals(1,actions.editorialCalls);assertNull(actions.startedSessionId);assertNull(actions.resumedSessionId)
 }
 @Test fun `draft editor locks while mode reprocessing is busy`(){assertTrue(draftEditorEnabled(false));assertFalse(draftEditorEnabled(true))}
 @Test fun `changing mode reprojects locally without scheduling reprocessing`()=runTest{
  val report=RecordingReport(SessionId("s"),"2026-09-11",emptyList(),SessionState.AWAITING_REVIEW)
  val original=DiaryDraftEntity("draft","s","CONSERVATIVE","tema","actividad","1","2","tarea",1)
  val drafts=MutableStateFlow<DiaryDraftEntity?>(original)
  var resumeCalls=0;var startCalls=0;var reprojectedId:SessionId?=null;var reprojectedMode:InterpretationMode?=null
  val actions=object:CaptureActions by UiActions(){
   override suspend fun startProcessing(id:SessionId,mode:InterpretationMode){startCalls++}
   override suspend fun resumeProcessing(id:SessionId,mode:InterpretationMode){resumeCalls++}
   override suspend fun reprojectMode(id:SessionId,mode:InterpretationMode){reprojectedId=id;reprojectedMode=mode;drafts.value=drafts.value!!.copy(mode=mode.name,updatedAtEpochMs=2)}
  }
  val vm=CaptureViewModel(UiSessions(null,report),actions,backgroundScope,drafts);runCurrent()
  vm.onMode(InterpretationMode.EXHAUSTIVE);runCurrent()
  assertEquals(0,resumeCalls);assertEquals(0,startCalls) // cambiar de modo nunca reprograma trabajo ni llama a la red
  assertEquals(SessionId("s"),reprojectedId);assertEquals(InterpretationMode.EXHAUSTIVE,reprojectedMode)
  assertEquals("EXHAUSTIVE",vm.state.value.draft!!.mode)
 }
 @Test fun `semantic run state is exposed and continue local forces local`()=runTest{
  val report=RecordingReport(SessionId("s"),"2026-09-11",emptyList(),SessionState.EXTRACTING)
  val progress=MutableStateFlow<TranscriptionProgress?>(TranscriptionProgress(SessionId("s"),60_000,60_000,0,0,0,TranscriptionRunState.PROCESSING,null))
  val semantic=MutableStateFlow<SemanticRunUi?>(SemanticRunUi("RUNNING",null,canContinueLocal=true))
  var continuedId:SessionId?=null;var continuedMode:InterpretationMode?=null
  val actions=object:CaptureActions by UiActions(){override suspend fun continueLocal(id:SessionId,mode:InterpretationMode){continuedId=id;continuedMode=mode}}
  val vm=CaptureViewModel(UiSessions(null,report),actions,backgroundScope,semanticRuns=semantic,observeProgress={progress});runCurrent()
  assertEquals("RUNNING",vm.state.value.semanticRun?.state)
  assertTrue(vm.state.value.semanticRun?.canContinueLocal==true)
  vm.onContinueLocal(InterpretationMode.CONSERVATIVE);runCurrent()
  assertEquals(SessionId("s"),continuedId);assertEquals(InterpretationMode.CONSERVATIVE,continuedMode)
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
  val vm=CaptureViewModel(repository,UiActions(),backgroundScope,flowOf(draft),editorialReports=flowOf(readyEditorialEntity()));runCurrent()
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
 @Test fun `extracting phase shows a distinct ficha generation label`()=runTest{
  val report=RecordingReport(SessionId("s"),"2026-09-11",emptyList(),SessionState.EXTRACTING)
  val progress=MutableStateFlow<TranscriptionProgress?>(TranscriptionProgress(SessionId("s"),60_000,60_000,0,0,0,TranscriptionRunState.PROCESSING,null))
  val vm=CaptureViewModel(UiSessions(null,report),UiActions(),backgroundScope,observeProgress={progress});runCurrent()
  assertEquals(CaptureStatus.PROCESSING,vm.state.value.status)
  assertEquals("GENERANDO FICHA",vm.state.value.progressLabel)
  assertEquals("Generando la ficha… (interpretando la transcripción)",vm.state.value.message)
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
 @Test fun `interpretation progress is visible and sanitized`()=runTest{
  val report=RecordingReport(SessionId("s"),"2026-09-11",emptyList(),SessionState.EXTRACTING)
  val progress=MutableStateFlow<TranscriptionProgress?>(TranscriptionProgress(SessionId("s"),60_000,60_000,0,0,0,TranscriptionRunState.PROCESSING,null))
  val interp=MutableStateFlow<InterpretationProgressUi?>(null)
  val vm=CaptureViewModel(UiSessions(null,report),UiActions(),backgroundScope,semanticRuns=flowOf(null),interpretations=interp,observeProgress={progress});runCurrent()
  interp.value=InterpretationProgressUi(packet=2,totalPackets=4,provider=com.capo.diarioclase.processing.semantic.InferenceProvider.GROQ,attempt=1,cacheHit=false,elapsedMs=1_200,provenance="MIXTO",canRetry=true,canContinueLocal=true);runCurrent()
  assertEquals(2,vm.state.value.interpretation!!.packet)
  assertEquals(com.capo.diarioclase.processing.semantic.InferenceProvider.GROQ,vm.state.value.interpretation!!.provider)
  // El progreso de interpretación nunca filtra credenciales ni texto de transcripción.
  val interpText=vm.state.value.interpretation.toString()
  assertFalse(vm.state.value.toString().contains("gsk_"))
  assertFalse(interpText.contains("gsk_"))
  assertFalse(interpText.contains("Página"))
 }
 @Test fun `retry interpretation reuses the session without leaving processing`()=runTest{
  val report=RecordingReport(SessionId("s"),"2026-09-11",emptyList(),SessionState.EXTRACTING)
  val progress=MutableStateFlow<TranscriptionProgress?>(TranscriptionProgress(SessionId("s"),60_000,60_000,0,0,0,TranscriptionRunState.PROCESSING,null))
  var retriedId:SessionId?=null;var retriedMode:InterpretationMode?=null
  val actions=object:CaptureActions by UiActions(){override suspend fun retryInterpretation(id:SessionId,mode:InterpretationMode){retriedId=id;retriedMode=mode}}
  val vm=CaptureViewModel(UiSessions(null,report),actions,backgroundScope,observeProgress={progress});runCurrent()
  vm.onRetryInterpretation(InterpretationMode.BALANCED);runCurrent()
  assertEquals(SessionId("s"),retriedId);assertEquals(InterpretationMode.BALANCED,retriedMode)
 }
 @Test fun `claim review records the decision without scheduling work`()=runTest{
  val actions=UiActions();val vm=reviewViewModel(actions,backgroundScope);runCurrent()
  vm.acceptClaim("c1");vm.rejectClaim("c2");vm.correctClaim("c3","página 12");vm.correctClaim("c4","");runCurrent()
  assertEquals(listOf(Triple("c1",ReviewAction.ACCEPT,null),Triple("c2",ReviewAction.REJECT,null),Triple("c3",ReviewAction.CORRECT,"página 12")),actions.reviews)
  assertNull(actions.startedSessionId);assertNull(actions.resumedSessionId) // revisar nunca reprograma trabajo ni llama a la red
 }
 private fun reviewViewModel(actions:CaptureActions,scope:kotlinx.coroutines.CoroutineScope):CaptureViewModel{
  val report=RecordingReport(SessionId("s"),"2026-09-11",emptyList(),SessionState.AWAITING_REVIEW);val draft=DiaryDraftEntity("draft","s","CONSERVATIVE","tema","actividad","1","2","tarea",1)
  return CaptureViewModel(UiSessions(null,report),actions,scope,flowOf(draft),editorialReports=flowOf(readyEditorialEntity()))
 }
}
private fun readyEditorialEntity()=EditorialReportEntity(
 "s","hash","READY","{\"summary\":\"Resumen\",\"material\":[],\"homework\":[],\"summary_source_claim_ids\":[],\"discarded\":[]}",
 "Resumen","","","GEMINI","gemini-2.5-flash","p","s","v",null,1,
)
private class UiSessions(initial:SessionAggregate?,report:RecordingReport?=null):SessionRepository{private val flow=MutableStateFlow(initial);private val reportFlow=MutableStateFlow(report);fun emit(x:SessionAggregate?){flow.value=x};override fun observeActiveSession():Flow<SessionAggregate?> = flow;override fun observeLatestFinalizedRecording():Flow<RecordingReport?> = reportFlow;override suspend fun createSession(level:CerLevel?)=SessionId("s");override suspend fun startBlock(sessionId:SessionId)=BlockId("b");override suspend fun closeBlock(blockId:BlockId,reason:BlockCloseReason){};override suspend fun finalizeSession(sessionId:SessionId){};override suspend fun updateSessionState(sessionId:SessionId,state:SessionState){} }
private class UiActions:CaptureActions{var pauses=0;var approvalCalls=0;var editorialCalls=0;var approvedDraft:DiaryDraftEntity?=null;var cleanupResult:CleanupOutcome=CleanupOutcome.Archived("diary");var retryResult:CleanupOutcome=CleanupOutcome.Archived("diary");var retriedSessionId:SessionId?=null;var startedSessionId:SessionId?=null;var startedMode:InterpretationMode?=null;var resumedSessionId:SessionId?=null;var resumedMode:InterpretationMode?=null;var pausedProcessingId:SessionId?=null;var holdApproval=false;override suspend fun startNewDay(){};override suspend fun resume(id:SessionId){};override suspend fun pause(){pauses++};override suspend fun markHomework(sessionId:SessionId,blockId:BlockId){};override suspend fun finalizeDay(id:SessionId,state:SessionState){};override suspend fun startProcessing(id:SessionId,mode:InterpretationMode){startedSessionId=id;startedMode=mode};override suspend fun pauseProcessing(id:SessionId){pausedProcessingId=id};override suspend fun resumeProcessing(id:SessionId,mode:InterpretationMode){resumedSessionId=id;resumedMode=mode};override suspend fun regenerateEditorialReport(id:SessionId,mode:InterpretationMode){editorialCalls++};override suspend fun approveAndClean(draft:DiaryDraftEntity):CleanupOutcome{approvalCalls++;approvedDraft=draft;if(holdApproval)awaitCancellation();return cleanupResult};override suspend fun retryCleanup(sessionId:SessionId):CleanupOutcome{retriedSessionId=sessionId;return retryResult};val reviews=mutableListOf<Triple<String,ReviewAction,String?>>();override suspend fun reviewClaim(sessionId:SessionId,claimId:String,action:ReviewAction,correctedValue:String?){reviews+=Triple(claimId,action,correctedValue)}}
