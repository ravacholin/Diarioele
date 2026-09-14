package com.capo.diarioclase.ui.capture
import com.capo.diarioclase.data.db.*
import com.capo.diarioclase.processing.evidence.EvidenceClaim
import com.capo.diarioclase.processing.transcription.TranscriptionFailure
enum class CaptureStatus { IDLE,RECORDING,PAUSED,REVIEW,PROCESSING,DRAFT,APPROVING,CLEANUP_PENDING,ARCHIVED }
data class CaptureUiState(val status:CaptureStatus=CaptureStatus.IDLE,val sessionId:SessionId?=null,val currentBlockId:BlockId?=null,val currentBlock:Int=0,val totalDurationMs:Long=0,val homeworkMarkers:Int=0,val busy:Boolean=false,val message:String?=null,val lastRecording:RecordingReport?=null,val draft:DiaryDraftEntity?=null,val claims:List<EvidenceClaim> = emptyList(),val diaryId:String?=null,val processedMs:Long=0,val processingTotalMs:Long=0,val progressPercent:Int=0,val progressLabel:String?=null,val transcriptionPaused:Boolean=false,val processingFailure:TranscriptionFailure?=null)
