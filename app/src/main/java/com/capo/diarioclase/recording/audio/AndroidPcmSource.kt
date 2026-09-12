package com.capo.diarioclase.recording.audio
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
class AndroidPcmSource(context:Context):PcmSource {
 private val recorder:AudioRecord
 init {
  check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED){"Falta permiso de micrófono"}
  val minimum=AudioRecord.getMinBufferSize(SAMPLE_RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT)
  check(minimum>0){"El micrófono no admite PCM 16 kHz mono"}
  recorder=AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
   .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
   .setBufferSizeInBytes(minimum*2).build()
  check(recorder.state==AudioRecord.STATE_INITIALIZED){"No se pudo inicializar el micrófono"}
  recorder.startRecording()
 }
 override suspend fun read(target:ShortArray)=withContext(Dispatchers.IO){val read=recorder.read(target,0,target.size,AudioRecord.READ_BLOCKING);check(read>=0){"El micrófono interrumpió la captura ($read)"};read}
 override fun close(){runCatching{recorder.stop()};recorder.release()}
}
