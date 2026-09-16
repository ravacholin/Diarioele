package com.capo.diarioclase.ui.capture
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.capo.diarioclase.data.db.*
import com.capo.diarioclase.data.repository.SessionRepository
import com.capo.diarioclase.processing.evidence.*
import com.capo.diarioclase.processing.transcription.TranscriptionFailure
import com.capo.diarioclase.processing.work.TranscriptionProgress
import com.capo.diarioclase.processing.work.TranscriptionRunState
import com.capo.diarioclase.diary.cleanup.CleanupOutcome
import com.capo.diarioclase.diary.DiaryEntry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean
interface CaptureActions { suspend fun startNewDay();suspend fun resume(id:SessionId);suspend fun pause();suspend fun markHomework(sessionId:SessionId,blockId:BlockId);suspend fun finalizeDay(id:SessionId,state:SessionState);suspend fun startProcessing(id:SessionId,mode:InterpretationMode){};suspend fun pauseProcessing(id:SessionId){};suspend fun resumeProcessing(id:SessionId,mode:InterpretationMode){};suspend fun reprojectMode(id:SessionId,mode:InterpretationMode){};suspend fun continueLocal(id:SessionId,mode:InterpretationMode){};suspend fun saveDraft(draft:DiaryDraftEntity){};suspend fun approveAndClean(draft:DiaryDraftEntity):CleanupOutcome=error("Aprobación no configurada");suspend fun retryCleanup(sessionId:SessionId):CleanupOutcome=error("Limpieza no configurada");suspend fun reviewClaim(sessionId:SessionId,claimId:String,action:ReviewAction,correctedValue:String?){};suspend fun retryInterpretation(id:SessionId,mode:InterpretationMode){} }
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureViewModel(repository:SessionRepository,private val actions:CaptureActions,scope:CoroutineScope?=null,drafts:Flow<DiaryDraftEntity?> = flowOf(null),claims:Flow<List<EvidenceClaim>> = flowOf(emptyList()),pendingRecordings:Flow<RecordingReport?> = repository.observeLatestCleanupPendingRecording(),diaries:Flow<List<DiaryEntry>> = flowOf(emptyList()),semanticRuns:Flow<SemanticRunUi?> = flowOf(null),interpretations:Flow<InterpretationProgressUi?> = flowOf(null),observeProgress:(SessionId)->Flow<TranscriptionProgress?> = { flowOf(null) }):ViewModel(){
 private val workScope=scope?:viewModelScope;private val _state=MutableStateFlow(CaptureUiState());val state:StateFlow<CaptureUiState> = _state.asStateFlow();private val actionInProgress=AtomicBoolean(false)
 private val progressFlow:Flow<TranscriptionProgress?> = repository.observeLatestFinalizedRecording().map{it?.sessionId}.distinctUntilChanged().flatMapLatest{id->if(id==null)flowOf(null) else observeProgress(id)}
 init {workScope.launch{combine(repository.observeActiveSession().map{it as Any?},repository.observeLatestFinalizedRecording().map{it as Any?},drafts.map{it as Any?},claims.map{it as Any?},pendingRecordings.map{it as Any?},diaries.map{it as Any?},progressFlow.map{it as Any?},semanticRuns.map{it as Any?},interpretations.map{it as Any?}){values->values}.collect{values->
  val session=values[0] as SessionAggregate?;val report=values[1] as RecordingReport?;val draft=values[2] as DiaryDraftEntity?;@Suppress("UNCHECKED_CAST") val evidence=values[3] as List<EvidenceClaim>;val pending=values[4] as RecordingReport?;@Suppress("UNCHECKED_CAST") val entries=values[5] as List<DiaryEntry>;val progress=values[6] as TranscriptionProgress?;val semantic=values[7] as SemanticRunUi?;val interpretation=values[8] as InterpretationProgressUi?
  if(_state.value.status in setOf(CaptureStatus.APPROVING,CaptureStatus.ARCHIVED)&&!(_state.value.status==CaptureStatus.ARCHIVED&&session!=null)) return@collect
  if(_state.value.status==CaptureStatus.CLEANUP_PENDING&&pending==null) return@collect
  _state.value=session?.let{CaptureUiState(if(it.state==SessionState.RECORDING)CaptureStatus.RECORDING else CaptureStatus.PAUSED,it.id,it.blocks.lastOrNull(),it.blocks.size,it.durationMs,_state.value.homeworkMarkers,false,"Los bloques cerrados están guardados",report,draft,evidence) }?:pending?.let{pendingReport->val entry=entries.firstOrNull{it.sessionId==pendingReport.sessionId};CaptureUiState(CaptureStatus.CLEANUP_PENDING,pendingReport.sessionId,message="La ficha permanente está segura. La limpieza temporal requiere reintento",lastRecording=pendingReport,draft=entry?.toRecoveredDraft(),diaryId=entry?.id)}?:report?.let{reportState(it,draft,evidence,progress,semantic,interpretation)}?:CaptureUiState()
 }};workScope.launch{while(isActive){delay(1_000);if(_state.value.status==CaptureStatus.RECORDING)_state.update{it.copy(totalDurationMs=it.totalDurationMs+1_000)}}}}
 private fun reportState(report:RecordingReport,draft:DiaryDraftEntity?,evidence:List<EvidenceClaim>,progress:TranscriptionProgress?,semantic:SemanticRunUi?=null,interpretation:InterpretationProgressUi?=null):CaptureUiState{
  val runState=progress?.state
  val segmentFailed=report.segments.any{s->s.state==SegmentState.FAILED}
  val failing=segmentFailed||runState==TranscriptionRunState.FAILED
  val paused=runState==TranscriptionRunState.PAUSED
  val running=runState==TranscriptionRunState.PREPARING||runState==TranscriptionRunState.PROCESSING||paused
  val extracting=report.state==SessionState.EXTRACTING&&!segmentFailed&&runState!=TranscriptionRunState.FAILED&&!paused
  val status=when{
   report.state==SessionState.AWAITING_REVIEW&&draft!=null->CaptureStatus.DRAFT
   running&&!failing->CaptureStatus.PROCESSING
   report.state in setOf(SessionState.TRANSCRIBING,SessionState.EXTRACTING)&&!failing->CaptureStatus.PROCESSING
   else->CaptureStatus.REVIEW
  }
  val storedFailure=report.segments.firstOrNull{s->s.state==SegmentState.FAILED}?.lastTranscriptionFailure?.let{code->runCatching{TranscriptionFailure.valueOf(code)}.getOrNull()}
  val failure=progress?.failure?.takeIf{runState==TranscriptionRunState.FAILED}?:storedFailure
  val total=progress?.totalMs?:0L;val processed=progress?.processedMs?:0L
  val percent=if(total>0L)((processed*100L)/total).toInt().coerceIn(0,100) else 0
  val message=when{
   failing->failure?.let(::failureMessage)?:"Falló la transcripción. El audio sigue guardado y podés reintentar"
   status==CaptureStatus.DRAFT->"Ficha generada. Revisá antes de aprobar"
   paused->"Procesamiento en pausa. El audio sigue guardado"
   runState==TranscriptionRunState.PREPARING->"Preparando el modelo local de español"
   extracting->"Generando la ficha… (interpretando la transcripción)"
   status==CaptureStatus.PROCESSING->"Transcribiendo en español · $percent%"
   else->if(report.allAudioReady)"Audio verificado y disponible" else "Revisá el estado del audio"
  }
  return CaptureUiState(status=status,sessionId=report.sessionId,busy=actionInProgress.get(),message=message,lastRecording=report,draft=draft,claims=evidence,processedMs=processed,processingTotalMs=total,progressPercent=percent,progressLabel=if(runState==TranscriptionRunState.PREPARING)"PREPARANDO MODELO" else if(extracting)"GENERANDO FICHA" else null,transcriptionPaused=paused,processingFailure=failure.takeIf{failing},semanticRun=semantic,interpretation=interpretation)
 }
 fun onStart(){execute{actions.startNewDay()}};fun onResume(){_state.value.sessionId?.let{id->execute{actions.resume(id)}}};fun onPause(){execute{actions.pause()}}
 fun onMarkHomework(){val s=_state.value.sessionId;val b=_state.value.currentBlockId;if(s!=null&&b!=null)execute(clearOnSuccess=true){actions.markHomework(s,b);_state.update{it.copy(homeworkMarkers=it.homeworkMarkers+1,message="Tarea marcada")}}}
 fun onFinalize(){val s=_state.value;val id=s.sessionId?:return;execute{actions.finalizeDay(id,if(s.status==CaptureStatus.RECORDING)SessionState.RECORDING else SessionState.PAUSED)}}
 fun onProcess(mode:InterpretationMode=InterpretationMode.CONSERVATIVE){val id=_state.value.lastRecording?.sessionId?:return;execute(clearOnSuccess=true){actions.startProcessing(id,mode)}}
 fun onPauseProcessing(){val id=_state.value.lastRecording?.sessionId?:return;execute(clearOnSuccess=true){actions.pauseProcessing(id)}}
 fun onResumeProcessing(mode:InterpretationMode=InterpretationMode.CONSERVATIVE){val id=_state.value.lastRecording?.sessionId?:return;execute(clearOnSuccess=true){actions.resumeProcessing(id,mode)}}
 // Task I7b: fuerza la interpretación local de la próxima corrida (deja de esperar a lo remoto).
 fun onContinueLocal(mode:InterpretationMode=InterpretationMode.CONSERVATIVE){val id=_state.value.lastRecording?.sessionId?:return;execute(clearOnSuccess=true){actions.continueLocal(id,mode)}}
 // Reintenta la interpretación completa (Fase 6, Q5) reusando la transcripción ya confirmada.
 fun onRetryInterpretation(mode:InterpretationMode=InterpretationMode.CONSERVATIVE){val id=_state.value.lastRecording?.sessionId?:return;execute(clearOnSuccess=true){actions.retryInterpretation(id,mode)}}
 // Cambiar de modo reproyecta localmente desde los claims ya persistidos (Task I7): nunca
 // reprograma trabajo ni llama a la red. Las ediciones del docente se conservan en el store.
 fun onMode(mode:InterpretationMode){val draft=_state.value.draft?:return;execute(clearOnSuccess=true){actions.reprojectMode(SessionId(draft.sessionId),mode)}}
 // Revisión estructurada del docente (Fase 6, Q4): aceptar/rechazar/corregir un claim "por
 // confirmar". Nunca reprograma trabajo ni llama a la red; solo persiste la decisión.
 private fun reviewSessionId():SessionId?{val id=_state.value.draft?.sessionId?:_state.value.sessionId?.value;return id?.let{SessionId(it)}}
 fun acceptClaim(claimId:String)=review(claimId,ReviewAction.ACCEPT,null)
 fun rejectClaim(claimId:String)=review(claimId,ReviewAction.REJECT,null)
 fun correctClaim(claimId:String,value:String){if(value.isBlank())return;review(claimId,ReviewAction.CORRECT,value)}
 // No pasa por `execute`: varias revisiones seguidas no deben bloquearse entre sí ni marcar
 // ocupada la pantalla. Persiste la decisión; nunca reprograma trabajo ni llama a la red.
 private fun review(claimId:String,action:ReviewAction,value:String?){val id=reviewSessionId()?:return;workScope.launch{try{actions.reviewClaim(id,claimId,action,value)}catch(error:CancellationException){throw error}catch(error:Exception){_state.update{it.copy(message=error.message)}}}}
 fun onSaveDraft(topics:String,activities:String,pages:String,exercises:String,homework:String){val draft=_state.value.draft?:return;execute(clearOnSuccess=true){actions.saveDraft(draft.copy(topics=topics,activities=activities,pages=pages,exercises=exercises,homework=homework));_state.update{it.copy(message="Cambios guardados")}}}
 fun onApprove(topics:String,activities:String,pages:String,exercises:String,homework:String){val current=_state.value;val draft=current.draft?:return;if(current.busy||current.status!=CaptureStatus.DRAFT)return;val edited=draft.copy(topics=topics,activities=activities,pages=pages,exercises=exercises,homework=homework,userEdited=true);_state.value=current.copy(status=CaptureStatus.APPROVING,busy=true,message="Guardando la ficha permanente",draft=edited);workScope.launch{try{showApprovalOutcome(actions.approveAndClean(edited))}catch(error:CancellationException){throw error}catch(error:Exception){_state.update{it.copy(status=CaptureStatus.DRAFT,busy=false,message=error.message?:"No se pudo aprobar la ficha")}}}}
 fun onRetryCleanup(){val current=_state.value;val sessionId=current.sessionId?:return;if(current.busy||current.status!=CaptureStatus.CLEANUP_PENDING)return;_state.value=current.copy(status=CaptureStatus.APPROVING,busy=true,message="Reintentando la limpieza");workScope.launch{try{showRetryOutcome(actions.retryCleanup(sessionId))}catch(error:CancellationException){throw error}catch(error:Exception){_state.update{it.copy(status=CaptureStatus.CLEANUP_PENDING,busy=false,message="La ficha permanente sigue segura. ${error.message?:"No se pudo reintentar la limpieza"}")}}}}
 private fun showApprovalOutcome(outcome:CleanupOutcome)=showCleanupOutcome(outcome,CaptureStatus.DRAFT)
 private fun showRetryOutcome(outcome:CleanupOutcome)=showCleanupOutcome(outcome,CaptureStatus.CLEANUP_PENDING)
 private fun showCleanupOutcome(outcome:CleanupOutcome,saveFailureStatus:CaptureStatus){_state.update{current->when(outcome){is CleanupOutcome.Archived->current.copy(status=CaptureStatus.ARCHIVED,busy=false,diaryId=outcome.diaryId,message="Diario archivado y temporales eliminados");is CleanupOutcome.Pending->current.copy(status=CaptureStatus.CLEANUP_PENDING,busy=false,diaryId=outcome.diaryId,message="La ficha está segura. La limpieza temporal requiere reintento");is CleanupOutcome.SaveFailed->current.copy(status=saveFailureStatus,busy=false,message=if(saveFailureStatus==CaptureStatus.CLEANUP_PENDING)"La ficha permanente sigue segura. ${outcome.reason}" else outcome.reason)}}}
 private fun failureMessage(failure:TranscriptionFailure)=when(failure){TranscriptionFailure.ON_DEVICE_UNAVAILABLE,TranscriptionFailure.NATIVE_UNAVAILABLE->"Este teléfono no ofrece transcripción local";TranscriptionFailure.LANGUAGE_UNAVAILABLE,TranscriptionFailure.MODEL_MISSING->"Falta el modelo local de español";TranscriptionFailure.MODEL_INVALID->"El modelo local de español no es válido";TranscriptionFailure.AUDIO_SOURCE_UNSUPPORTED->"Este teléfono no admite desgrabar el audio guardado con el motor local. El audio sigue seguro";TranscriptionFailure.NO_SPEECH->"No se detectó voz. El audio sigue guardado";TranscriptionFailure.RECOGNIZER_BUSY->"El transcriptor está ocupado. Reintentá";TranscriptionFailure.INVALID_AUDIO->"Un segmento de audio no es válido";TranscriptionFailure.OUT_OF_MEMORY->"No hay memoria suficiente para transcribir este tramo. El audio sigue guardado";TranscriptionFailure.TIMEOUT->"La transcripción local no respondió a tiempo. El audio sigue guardado";else->"La transcripción falló. El audio sigue guardado"}
 private fun execute(clearOnSuccess:Boolean=false,action:suspend()->Unit){if(_state.value.busy||!actionInProgress.compareAndSet(false,true))return;_state.update{it.copy(busy=true,message=null)};workScope.launch{try{action();if(clearOnSuccess)_state.update{it.copy(busy=false)}}catch(error:CancellationException){throw error}catch(error:Exception){_state.update{it.copy(busy=false,message=error.message)}}finally{actionInProgress.set(false);_state.update{it.copy(busy=false)}}}}
 private fun DiaryEntry.toRecoveredDraft()=DiaryDraftEntity("permanent-$id",sessionId.value,InterpretationMode.CONSERVATIVE.name,topics,activities,pages,completedExercises,homework,updatedAtEpochMs,userEdited=true)
}
