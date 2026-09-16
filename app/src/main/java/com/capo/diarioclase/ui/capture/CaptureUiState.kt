package com.capo.diarioclase.ui.capture
import com.capo.diarioclase.data.db.*
import com.capo.diarioclase.processing.evidence.EvidenceClaim
import com.capo.diarioclase.processing.transcription.TranscriptionFailure
enum class CaptureStatus { IDLE,RECORDING,PAUSED,REVIEW,PROCESSING,DRAFT,APPROVING,CLEANUP_PENDING,ARCHIVED }
/**
 * Estado semántico observable de la corrida de interpretación (Task I7b), leído del journal.
 * `state` es el nombre del [com.capo.diarioclase.processing.work.InterpretationRunState];
 * `failure` el motivo semántico si lo hubo. `canContinueLocal` habilita la acción de forzar
 * lo local mientras la interpretación está en curso.
 */
data class SemanticRunUi(val state:String,val failure:String?=null,val canContinueLocal:Boolean=false)
data class CaptureUiState(val status:CaptureStatus=CaptureStatus.IDLE,val sessionId:SessionId?=null,val currentBlockId:BlockId?=null,val currentBlock:Int=0,val totalDurationMs:Long=0,val homeworkMarkers:Int=0,val busy:Boolean=false,val message:String?=null,val lastRecording:RecordingReport?=null,val draft:DiaryDraftEntity?=null,val claims:List<EvidenceClaim> = emptyList(),val diaryId:String?=null,val processedMs:Long=0,val processingTotalMs:Long=0,val progressPercent:Int=0,val progressLabel:String?=null,val transcriptionPaused:Boolean=false,val processingFailure:TranscriptionFailure?=null,val semanticRun:SemanticRunUi?=null)
