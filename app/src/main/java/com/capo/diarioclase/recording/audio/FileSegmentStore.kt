package com.capo.diarioclase.recording.audio
import com.capo.diarioclase.data.db.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

class FileSegmentStore(private val root:File,private val listFiles:()->Array<File>?={root.listFiles()}):SegmentStore,CleanupFileStore {
 init { root.mkdirs() }
 override suspend fun open(blockId:BlockId,ordinal:Int)=withContext(Dispatchers.IO){
  val id=SegmentId(UUID.randomUUID().toString());val safe=blockId.value.replace(Regex("[^A-Za-z0-9-]"),"_")
  val file=File(root,"${safe}__${ordinal}__${id.value}.open.wav")
  RandomAccessFile(file,"rw").use{it.write(WavHeader.forMuLaw(0).encode());it.fd.sync()}
  OpenSegment(id,blockId,file.absolutePath)
 }
 override suspend fun append(segment:OpenSegment,pcm:ShortArray,count:Int)=withContext(Dispatchers.IO){
  require(count in 0..pcm.size)
  val encoded=ByteArray(count){MuLawCodec.encode(pcm[it])}
  RandomAccessFile(segment.path,"rw").use{f->f.seek(f.length());f.write(encoded);f.fd.sync()}
 }
 override suspend fun close(segment:OpenSegment)=withContext(Dispatchers.IO){closeFile(File(segment.path),segment.id,segment.blockId)}
 override suspend fun repairOpenSegments()=withContext(Dispatchers.IO){
  root.listFiles{f->f.name.endsWith(".open.wav")}?.sortedBy{it.name}?.mapNotNull{f->
   val parts=f.name.removeSuffix(".open.wav").split("__");if(parts.size<3){f.delete();null}else closeFile(f,SegmentId(parts.last()),BlockId(parts.dropLast(2).joinToString("__")))
  }?:emptyList()
 }
 override suspend fun delete(segmentId:SegmentId)=withContext(Dispatchers.IO){
  val file=listedFiles().firstOrNull{it.name.endsWith("__${segmentId.value}.ready.wav")}
  if(file==null)DeleteResult.Failed("Archivo inexistente") else if(file.delete())DeleteResult.Deleted else DeleteResult.Failed("No se pudo borrar")
 }
 override suspend fun exists(segmentId:SegmentId)=withContext(Dispatchers.IO){
  listedFiles().any{it.name.endsWith("__${segmentId.value}.ready.wav")}
 }
 private fun listedFiles()=listFiles()?:throw IOException("No se pudo listar el audio temporal")
 private fun closeFile(file:File,id:SegmentId,blockId:BlockId):ReadySegment {
  // Un segmento abierto antes de la migración a µ-law conserva su cabecera PCM16 de 44 bytes:
  // reescribirlo como µ-law pisaría audio y lo volvería ilegible.
  val legacy=RandomAccessFile(file,"rw").use{f->
   val legacy=isLegacyPcm16(f);val headerBytes=if(legacy)LEGACY_PCM16_HEADER_BYTES else WAV_HEADER_BYTES
   val data=(f.length()-headerBytes).coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
   f.seek(0);f.write((if(legacy)WavHeader.forLegacyPcm16(data) else WavHeader.forMuLaw(data)).encode());f.fd.sync();legacy
  }
  val ready=File(file.parentFile,file.name.removeSuffix(".open.wav")+".ready.wav")
  try{Files.move(file.toPath(),ready.toPath(),StandardCopyOption.ATOMIC_MOVE)}catch(_:Exception){Files.move(file.toPath(),ready.toPath(),StandardCopyOption.REPLACE_EXISTING)}
  val headerBytes=if(legacy)LEGACY_PCM16_HEADER_BYTES else WAV_HEADER_BYTES;val bytesPerSample=if(legacy)LEGACY_PCM16_BYTES_PER_SAMPLE else STORED_BYTES_PER_SAMPLE
  val bytes=(ready.length()-headerBytes).coerceAtLeast(0);val duration=bytes*1_000/(SAMPLE_RATE*CHANNELS*bytesPerSample)
  return ReadySegment(id,blockId,ready.absolutePath,duration,sha256(ready))
 }
 private fun isLegacyPcm16(f:RandomAccessFile):Boolean{if(f.length()<LEGACY_PCM16_HEADER_BYTES)return false;f.seek(20);val low=f.read();val high=f.read();return (low or (high shl 8))==WAVE_FORMAT_PCM}
 private fun sha256(file:File):String {val d=MessageDigest.getInstance("SHA-256");file.inputStream().use{input->val b=ByteArray(8192);while(true){val n=input.read(b);if(n<0)break;d.update(b,0,n)}};return d.digest().joinToString(""){"%02x".format(it)}}
}
