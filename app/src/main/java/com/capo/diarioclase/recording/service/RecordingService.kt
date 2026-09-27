package com.capo.diarioclase.recording.service
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.capo.diarioclase.core.clock.SystemClock
import com.capo.diarioclase.DiarioClaseApp
import com.capo.diarioclase.data.db.*
import com.capo.diarioclase.data.repository.RoomSegmentMetadataStore
import com.capo.diarioclase.recording.audio.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
class RecordingService:Service(){
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default+CoroutineExceptionHandler{_,error->Log.e(TAG,"Fallo inesperado en la grabación",error)});private val commands=Channel<Pair<Intent,Int>>(Channel.UNLIMITED);private lateinit var coordinator:RecordingCoordinator
 override fun onCreate(){super.onCreate();RecordingNotification.createChannel(this);startForeground(RecordingNotification.ID,RecordingNotification.build(this));val app=application as DiarioClaseApp;val segments=PersistingSegmentStore(FileSegmentStore(File(filesDir,"temporary_audio")),RoomSegmentMetadataStore(app.database.sessions()));coordinator=RecordingCoordinator(app.repository,segments,{AndroidPcmSource(this)},SystemClock,scope){stopRecordingService()};scope.launch{for((intent,startId) in commands)execute(intent,startId)}}
 // Los comandos se ejecutan de a uno y en orden: un PAUSAR que llega mientras START todavía
 // abre el bloque no puede adelantarse y dejar el micrófono grabando sin servicio. Al detenerse
 // se usa el startId del comando para no descartar un START que llegó después.
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{if(intent!=null)commands.trySend(intent to startId);return START_NOT_STICKY}
 private suspend fun execute(intent:Intent,startId:Int){
  // Un fallo al iniciar (micrófono ocupado, error de Room) no debe tirar abajo la app: el
  // bloque ya quedó cerrado como interrumpido y el audio confirmado sigue a salvo.
  try{when(intent.action){ACTION_START->intent.getStringExtra(EXTRA_SESSION_ID)?.let{id->coordinator.start(SessionId(id))};ACTION_PAUSE->{coordinator.pause();stopRecordingService(startId)};ACTION_FINALIZE->{coordinator.finalizeDay();stopRecordingService(startId)}}}
  catch(cancelled:CancellationException){throw cancelled}
  catch(error:Throwable){Log.e(TAG,"No se pudo ejecutar ${intent.action}",error);stopRecordingService(startId)}
 }
 private fun stopRecordingService(startId:Int?=null){if(startId==null){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}else if(stopSelfResult(startId))stopForeground(STOP_FOREGROUND_REMOVE)}
 override fun onDestroy(){scope.cancel();super.onDestroy()};override fun onBind(intent:Intent?):IBinder?=null
 companion object {const val ACTION_START="com.capo.diarioclase.START";const val ACTION_PAUSE="com.capo.diarioclase.PAUSE";const val ACTION_FINALIZE="com.capo.diarioclase.FINALIZE";const val EXTRA_SESSION_ID="session_id";private const val TAG="RecordingService"}
}
