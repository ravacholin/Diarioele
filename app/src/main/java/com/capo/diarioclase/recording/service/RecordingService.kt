package com.capo.diarioclase.recording.service
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.capo.diarioclase.core.clock.SystemClock
import com.capo.diarioclase.DiarioClaseApp
import com.capo.diarioclase.data.db.*
import com.capo.diarioclase.recording.audio.*
import kotlinx.coroutines.*
import java.io.IOException
class RecordingService:Service(){
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);private lateinit var coordinator:RecordingCoordinator;private lateinit var app:DiarioClaseApp
 override fun onCreate(){super.onCreate();RecordingNotification.createChannel(this);startForeground(RecordingNotification.ID,RecordingNotification.build(this));app=application as DiarioClaseApp;coordinator=RecordingCoordinator(sessions=app.repository,segments=app.recordingSegments,sourceFactory={app.recordingComponents.requireOpusSupport();AndroidPcmSource(this)},clock=SystemClock,scope=scope,maxSegmentSamples=16_000*60,onUnexpectedStop=::stopAfterFailure)}
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{when(intent?.action){ACTION_START->intent.getStringExtra(EXTRA_SESSION_ID)?.let{id->scope.launch{runCatching{coordinator.start(SessionId(id))}.onFailure(::stopAfterFailure)}};ACTION_PAUSE->scope.launch{coordinator.pause();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()};ACTION_FINALIZE->scope.launch{coordinator.finalizeDay();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}};return START_NOT_STICKY}
 private fun stopAfterFailure(error:Throwable){val failure=when(error){is UnsupportedAudioCapabilityException->RecordingFailure.OPUS_UNAVAILABLE;is IOException->RecordingFailure.STORAGE;else->RecordingFailure.CAPTURE};app.reportRecordingFailure(failure);stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
 override fun onDestroy(){scope.cancel();super.onDestroy()};override fun onBind(intent:Intent?):IBinder?=null
 companion object {const val ACTION_START="com.capo.diarioclase.START";const val ACTION_PAUSE="com.capo.diarioclase.PAUSE";const val ACTION_FINALIZE="com.capo.diarioclase.FINALIZE";const val EXTRA_SESSION_ID="session_id"}
}
