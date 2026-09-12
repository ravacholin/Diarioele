package com.capo.diarioclase.data.repository
import androidx.room.withTransaction
import com.capo.diarioclase.core.clock.Clock
import com.capo.diarioclase.data.db.*
import kotlinx.coroutines.flow.*
import java.time.LocalDate
import java.util.UUID
fun interface IdProvider { fun next():String }
interface SessionRepository {
 fun observeActiveSession():Flow<SessionAggregate?>
 fun observeLatestFinalizedRecording():Flow<RecordingReport?>
 fun observeLatestCleanupPendingRecording():Flow<RecordingReport?> = flowOf(null)
 suspend fun createSession(level:CerLevel?):SessionId
 suspend fun startBlock(sessionId:SessionId):BlockId
 suspend fun closeBlock(blockId:BlockId,reason:BlockCloseReason)
 suspend fun finalizeSession(sessionId:SessionId)
 suspend fun updateSessionState(sessionId:SessionId,state:SessionState)
}
class RoomSessionRepository(private val database:DiarioDatabase,private val clock:Clock,private val dateProvider:()->String={LocalDate.now().toString()},private val idProvider:IdProvider=IdProvider{UUID.randomUUID().toString()}):SessionRepository {
 private val dao=database.sessions()
 override fun observeActiveSession()=dao.observeActiveSession().map{r->r?.let{ val bs=it.blocks.sortedBy{b->b.ordinal};SessionAggregate(SessionId(it.session.id),it.session.level?.let(CerLevel::valueOf),SessionState.valueOf(it.session.state),bs.map{b->BlockId(b.id)},bs.sumOf{b->((b.endedAtEpochMs?:clock.nowEpochMs())-b.startedAtEpochMs).coerceAtLeast(0)})}}
 @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
 override fun observeLatestFinalizedRecording():Flow<RecordingReport?> = observeRecording(dao.observeLatestFinalizedSession())
 @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
 override fun observeLatestCleanupPendingRecording():Flow<RecordingReport?> = observeRecording(dao.observeLatestCleanupPendingSession())
 @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
 private fun observeRecording(source:Flow<SessionWithBlocks?>):Flow<RecordingReport?> = source.flatMapLatest { record ->
  if(record==null) flowOf(null)
  else dao.observeSegmentsForSession(record.session.id).map { segments ->
   val byBlock=segments.groupBy { it.blockId }
   RecordingReport(
    SessionId(record.session.id),
    record.session.pedagogicalDate,
    record.blocks.sortedBy { it.ordinal }.map { block ->
     BlockRecordingSummary(
      BlockId(block.id),
      block.ordinal+1,
      ((block.endedAtEpochMs?:block.startedAtEpochMs)-block.startedAtEpochMs).coerceAtLeast(0),
      block.closeReason?.let(BlockCloseReason::valueOf),
      byBlock[block.id].orEmpty().sortedBy { it.ordinal }.map { segment ->
       SegmentSummary(SegmentId(segment.id),segment.path,segment.durationMs,segment.byteCount,SegmentState.valueOf(segment.state),java.io.File(segment.path).isFile,segment.lastTranscriptionFailure)
      },
     )
    },
    SessionState.valueOf(record.session.state),
   )
  }
 }
 override suspend fun createSession(level:CerLevel?)=database.withTransaction{check(dao.activeSession()==null);val id=SessionId(idProvider.next());val now=clock.nowEpochMs();dao.insertSession(SessionEntity(id.value,dateProvider(),level?.name,SessionState.PAUSED.name,now,now));id}
 override suspend fun startBlock(sessionId:SessionId)=database.withTransaction{val s=req(sessionId);check(SessionState.valueOf(s.state)==SessionState.PAUSED);check(dao.openBlock(sessionId.value)==null);val b=BlockEntity(idProvider.next(),sessionId.value,dao.blockCount(sessionId.value),clock.nowEpochMs(),null,null);dao.insertBlock(b);dao.updateSession(s.copy(state=SessionState.RECORDING.name,updatedAtEpochMs=clock.nowEpochMs()));BlockId(b.id)}
 override suspend fun closeBlock(blockId:BlockId,reason:BlockCloseReason)=database.withTransaction{val b=dao.block(blockId.value)?:error("Bloque inexistente");check(b.endedAtEpochMs==null);val s=req(SessionId(b.sessionId));check(SessionState.valueOf(s.state)==SessionState.RECORDING);val now=clock.nowEpochMs();dao.updateBlock(b.copy(endedAtEpochMs=now,closeReason=reason.name));dao.updateSession(s.copy(state=(if(reason==BlockCloseReason.FINALIZED)SessionState.FINALIZED else SessionState.PAUSED).name,updatedAtEpochMs=now))}
 override suspend fun finalizeSession(sessionId:SessionId)=database.withTransaction{val s=req(sessionId);val state=SessionState.valueOf(s.state);check(state==SessionState.RECORDING||state==SessionState.PAUSED);val now=clock.nowEpochMs();dao.openBlock(sessionId.value)?.let{dao.updateBlock(it.copy(endedAtEpochMs=now,closeReason=BlockCloseReason.FINALIZED.name))};dao.updateSession(s.copy(state=SessionState.FINALIZED.name,updatedAtEpochMs=now))}
 override suspend fun updateSessionState(sessionId:SessionId,state:SessionState)=database.withTransaction{val s=req(sessionId);val current=SessionState.valueOf(s.state);check(current.canTransitionTo(state));dao.updateSession(s.copy(state=state.name,updatedAtEpochMs=clock.nowEpochMs()))}
 private suspend fun req(id:SessionId)=dao.session(id.value)?:error("Sesión inexistente")
}
