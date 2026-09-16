package com.capo.diarioclase.data.db
@JvmInline value class SessionId(val value: String)
@JvmInline value class BlockId(val value: String)
@JvmInline value class SegmentId(val value: String)
enum class CerLevel { A1,A2,B1,B2,C1,C2 }
enum class BlockCloseReason { PAUSED,FINALIZED,INTERRUPTED }
enum class SessionState { RECORDING,PAUSED,FINALIZED,TRANSCRIBING,EXTRACTING,AWAITING_REVIEW,APPROVED,CLEANUP_PENDING,ARCHIVED;
    fun canTransitionTo(next: SessionState) = next in when(this) {
        RECORDING -> setOf(PAUSED,FINALIZED); PAUSED -> setOf(RECORDING,FINALIZED); FINALIZED -> setOf(TRANSCRIBING)
        TRANSCRIBING -> setOf(EXTRACTING); EXTRACTING -> setOf(AWAITING_REVIEW); AWAITING_REVIEW -> setOf(APPROVED)
        APPROVED -> setOf(ARCHIVED,CLEANUP_PENDING); CLEANUP_PENDING -> setOf(ARCHIVED); ARCHIVED -> emptySet()
    }
}
enum class SegmentState { OPEN,READY,TRANSCRIBING,TRANSCRIBED,FAILED,DELETED }
enum class RecordingFailure { OPUS_UNAVAILABLE, STORAGE, CAPTURE }
const val RECORDING_FAILURE_SETTING = "recording_failure"
data class SessionAggregate(val id:SessionId,val level:CerLevel?,val state:SessionState,val blocks:List<BlockId>,val durationMs:Long)
data class SegmentSummary(val id:SegmentId,val path:String,val durationMs:Long,val byteCount:Long,val state:SegmentState,val exists:Boolean=true,val lastTranscriptionFailure:String?=null)
data class BlockRecordingSummary(val id:BlockId,val number:Int,val durationMs:Long,val closeReason:BlockCloseReason?,val segments:List<SegmentSummary>)
data class RecordingReport(val sessionId:SessionId,val pedagogicalDate:String,val blocks:List<BlockRecordingSummary>,val state:SessionState=SessionState.FINALIZED){
 val segments:List<SegmentSummary> get()=blocks.flatMap{it.segments}
 val recordedDurationMs:Long get()=segments.sumOf{it.durationMs}
 val allAudioReady:Boolean get()=segments.isNotEmpty()&&segments.all{it.state in setOf(SegmentState.READY,SegmentState.TRANSCRIBING,SegmentState.TRANSCRIBED,SegmentState.FAILED)&&it.exists}
}
