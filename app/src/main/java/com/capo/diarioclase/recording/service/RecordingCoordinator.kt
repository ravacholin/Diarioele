package com.capo.diarioclase.recording.service
import com.capo.diarioclase.core.clock.Clock
import com.capo.diarioclase.data.db.*
import com.capo.diarioclase.data.repository.SessionRepository
import com.capo.diarioclase.recording.audio.*
import kotlinx.coroutines.*

sealed interface RecordingCommand { data class Start(val sessionId:SessionId):RecordingCommand;data object Pause:RecordingCommand;data object MarkHomework:RecordingCommand;data object FinalizeDay:RecordingCommand }

class RecordingCoordinator(private val sessions:SessionRepository,private val segments:SegmentStore,private val sourceFactory:()->PcmSource,private val clock:Clock,private val scope:CoroutineScope,private val maxSegmentSamples:Int=MAX_SEGMENT_SAMPLES,private val onUnexpectedStop:(Throwable)->Unit={}) {
 companion object { const val MAX_SEGMENT_SAMPLES=16_000*180 }
 init { require(maxSegmentSamples>0){"La duración máxima del segmento debe ser positiva"} }
 private var job:Job?=null;private var sessionId:SessionId?=null;private var blockId:BlockId?=null
 suspend fun start(session:SessionId){check(job==null);sessionId=session;val startedBlock=sessions.startBlock(session);blockId=startedBlock;val source=try{sourceFactory()}catch(error:Throwable){sessions.closeBlock(startedBlock,BlockCloseReason.INTERRUPTED);blockId=null;sessionId=null;throw error};job=scope.launch{try{source.use{recordBlock(startedBlock,it)}}catch(cancelled:CancellationException){throw cancelled}catch(error:Throwable){sessions.closeBlock(startedBlock,BlockCloseReason.INTERRUPTED);blockId=null;sessionId=null;onUnexpectedStop(error)}}}
 suspend fun pause(){val running=job?:return;running.cancelAndJoin();job=null;blockId?.let{sessions.closeBlock(it,BlockCloseReason.PAUSED)};blockId=null}
 suspend fun finalizeDay(){job?.cancelAndJoin();job=null;sessionId?.let{sessions.finalizeSession(it)};blockId=null;sessionId=null}
 suspend fun recordBlock(block:BlockId,source:PcmSource){
  val buffer=ShortArray(4096);var current:OpenSegment?=null;var currentSamples=0;var ordinal=0
  try {
   while(currentCoroutineContext().isActive){val read=source.read(buffer);if(read<0)break;if(read==0)continue;var offset=0
    while(offset<read){if(current==null)current=segments.open(block,ordinal);val take=minOf(read-offset,maxSegmentSamples-currentSamples);segments.append(current,buffer.copyOfRange(offset,offset+take),take);offset+=take;currentSamples+=take
     if(currentSamples==maxSegmentSamples){segments.close(current);current=null;currentSamples=0;ordinal++}
    }
   }
  } finally {withContext(NonCancellable){current?.let{segments.close(it)}}}
 }
}
