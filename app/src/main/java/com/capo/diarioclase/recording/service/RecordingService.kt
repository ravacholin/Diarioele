package com.capo.diarioclase.recording.service
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.capo.diarioclase.core.clock.SystemClock
import com.capo.diarioclase.DiarioClaseApp
import com.capo.diarioclase.data.db.*
import com.capo.diarioclase.data.repository.RoomSegmentMetadataStore
import com.capo.diarioclase.recording.audio.*
import kotlinx.coroutines.*
import java.io.File
class RecordingService:Service(){
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);private lateinit var coordinator:RecordingCoordinator
 override fun onCreate(){super.onCreate();RecordingNotification.createChannel(this);startForeground(RecordingNotification.ID,RecordingNotification.build(this));val app=application as DiarioClaseApp;val segments=PersistingSegmentStore(FileSegmentStore(File(filesDir,"temporary_audio")),RoomSegmentMetadataStore(app.database.sessions()));coordinator=RecordingCoordinator(app.repository,segments,{AndroidPcmSource(this)},SystemClock,scope){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}}
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{when(intent?.action){ACTION_START->intent.getStringExtra(EXTRA_SESSION_ID)?.let{id->scope.launch{coordinator.start(SessionId(id))}};ACTION_PAUSE->scope.launch{coordinator.pause();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()};ACTION_FINALIZE->scope.launch{coordinator.finalizeDay();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}};return START_NOT_STICKY}
 override fun onDestroy(){scope.cancel();super.onDestroy()};override fun onBind(intent:Intent?):IBinder?=null
 companion object {const val ACTION_START="com.capo.diarioclase.START";const val ACTION_PAUSE="com.capo.diarioclase.PAUSE";const val ACTION_FINALIZE="com.capo.diarioclase.FINALIZE";const val EXTRA_SESSION_ID="session_id"}
}
