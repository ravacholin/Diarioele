package com.capo.diarioclase
import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.BatteryManager
import androidx.core.content.ContextCompat
import com.capo.diarioclase.data.db.*
import com.capo.diarioclase.recording.*
import com.capo.diarioclase.recording.service.RecordingService
import com.capo.diarioclase.ui.capture.CaptureActions
import com.capo.diarioclase.processing.evidence.InterpretationMode
import com.capo.diarioclase.processing.work.LocalDraftReprojector
import com.capo.diarioclase.diary.cleanup.CleanupOutcome
class AndroidCaptureActions(private val context:Context,private val app:DiarioClaseApp):CaptureActions{
 override suspend fun startNewDay(){val battery=context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);when(val r=PreflightChecker().check(context.filesDir.usableSpace,battery,ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)){PreflightResult.Ready->{val id=app.repository.createSession(null);start(RecordingService.ACTION_START,id)};is PreflightResult.Blocked->error(when(r.reason){PreflightReason.INSUFFICIENT_SPACE->"Falta espacio libre";PreflightReason.MICROPHONE_PERMISSION->"Falta permiso de micrófono";PreflightReason.CRITICAL_BATTERY->"Batería demasiado baja"})}}
 override suspend fun resume(id:SessionId)=start(RecordingService.ACTION_START,id)
 override suspend fun pause(){ContextCompat.startForegroundService(context,Intent(context,RecordingService::class.java).setAction(RecordingService.ACTION_PAUSE))}
 override suspend fun markHomework(sessionId:SessionId,blockId:BlockId){val b=app.database.sessions().block(blockId.value)?:return;app.recovery.markHomework(sessionId,blockId,b.startedAtEpochMs,System.currentTimeMillis())}
 override suspend fun finalizeDay(id:SessionId,state:SessionState){if(state==SessionState.RECORDING)ContextCompat.startForegroundService(context,Intent(context,RecordingService::class.java).setAction(RecordingService.ACTION_FINALIZE)) else app.repository.finalizeSession(id)}
 override suspend fun startProcessing(id:SessionId,mode:InterpretationMode){preferLocal(false);app.transcriptionScheduler.start(id,mode)}
 override suspend fun pauseProcessing(id:SessionId)=app.transcriptionScheduler.pause(id)
 override suspend fun resumeProcessing(id:SessionId,mode:InterpretationMode){preferLocal(false);app.transcriptionScheduler.resume(id,mode)}
 override suspend fun continueLocal(id:SessionId,mode:InterpretationMode){preferLocal(true);app.transcriptionScheduler.resume(id,mode)}
 private suspend fun preferLocal(enabled:Boolean){app.database.sessions().saveSetting(AppSettingEntity("prefer_local_interpretation",enabled.toString()))}
 private val reprojector by lazy{LocalDraftReprojector(app.processingStore)}
 override suspend fun reprojectMode(id:SessionId,mode:InterpretationMode){reprojector.reproject(id,mode)}
 override suspend fun regenerateEditorialReport(id:SessionId,mode:InterpretationMode){app.editorialReportService.generate(id,mode,app.processingStore.persistedClaims(id))}
 override suspend fun saveDraft(draft:DiaryDraftEntity){app.processingStore.saveEditedDraft(draft)}
 override suspend fun reviewClaim(sessionId:SessionId,claimId:String,action:ReviewAction,correctedValue:String?){app.processingStore.reviewClaim(claimId,action,correctedValue)}
 override suspend fun retryInterpretation(id:SessionId,mode:InterpretationMode){preferLocal(false);app.transcriptionScheduler.resume(id,mode)}
 override suspend fun saveExample(id:SessionId){app.localExampleService.savePreview(app.localExampleService.preview(id.value))}
 override suspend fun deleteAllExamples(){app.localExampleService.deleteAll()}
 override suspend fun exportCorpus(uri:String){app.localExampleService.exportJsonl(contentResolverIo(),uri)}
 override suspend fun importCorpus(uri:String){app.localExampleService.importJsonl(contentResolverIo(),uri)}
 private fun contentResolverIo()=object:com.capo.diarioclase.processing.evaluation.ExampleDocumentIo{
  override fun read(uri:String):String=context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use{it.readBytes().toString(Charsets.UTF_8)}.orEmpty()
  override fun write(uri:String,content:String){context.contentResolver.openOutputStream(android.net.Uri.parse(uri),"wt")?.use{it.write(content.toByteArray(Charsets.UTF_8))}}
 }
 override suspend fun approveAndClean(draft:DiaryDraftEntity):CleanupOutcome=app.cleanupCoordinator.approveAndClean(SessionId(draft.sessionId),draft)
 override suspend fun retryCleanup(sessionId:SessionId):CleanupOutcome=app.cleanupCoordinator.retryCleanup(sessionId)
 private fun start(action:String,id:SessionId){ContextCompat.startForegroundService(context,Intent(context,RecordingService::class.java).setAction(action).putExtra(RecordingService.EXTRA_SESSION_ID,id.value))}
}
