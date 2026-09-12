package com.capo.diarioclase.recording.recovery
import com.capo.diarioclase.data.db.*
import com.capo.diarioclase.data.repository.SessionRepository
import com.capo.diarioclase.recording.audio.SegmentStore
import kotlinx.coroutines.flow.first
import java.util.UUID
data class HomeworkMarker(val id:String,val sessionId:SessionId,val blockId:BlockId,val absoluteEpochMs:Long,val offsetMs:Long)
fun interface MarkerStore { suspend fun save(marker:HomeworkMarker) }
sealed interface RecoveryDecision { data object None:RecoveryDecision;data class ResumeOrFinalize(val sessionId:SessionId):RecoveryDecision }
class RecordingRecovery(private val sessions:SessionRepository,private val segments:SegmentStore,private val markers:MarkerStore){
 suspend fun onAppStart():RecoveryDecision{segments.repairOpenSegments();val active=sessions.observeActiveSession().first();if(active?.state==SessionState.RECORDING){val openBlock=active.blocks.lastOrNull();if(openBlock!=null)sessions.closeBlock(openBlock,BlockCloseReason.INTERRUPTED)else sessions.updateSessionState(active.id,SessionState.PAUSED)};return active?.let{RecoveryDecision.ResumeOrFinalize(it.id)}?:RecoveryDecision.None}
 suspend fun markHomework(sessionId:SessionId,blockId:BlockId,blockStartEpochMs:Long,nowEpochMs:Long){markers.save(HomeworkMarker(UUID.randomUUID().toString(),sessionId,blockId,nowEpochMs,(nowEpochMs-blockStartEpochMs).coerceAtLeast(0)))}
}
