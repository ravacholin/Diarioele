package com.capo.diarioclase.ui.capture

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.capo.diarioclase.data.db.*
import com.capo.diarioclase.diary.DiaryClipboardFormatter
import com.capo.diarioclase.processing.evidence.*

@Composable
fun CaptureScreen(state:CaptureUiState,onStart:()->Unit,onPause:()->Unit,onResume:()->Unit,onMark:()->Unit,onFinalize:()->Unit,onProcess:(InterpretationMode)->Unit,onMode:(InterpretationMode,String,String,String,String,String)->Unit,onPauseProcessing:()->Unit,onResumeProcessing:(InterpretationMode)->Unit,onSave:(String,String,String,String,String)->Unit,onApprove:(String,String,String,String,String)->Unit,onRetryCleanup:()->Unit,interpretationMode:InterpretationMode=InterpretationMode.CONSERVATIVE){
 MaterialTheme(colorScheme=darkColorScheme(primary=Color.White,onPrimary=Color.Black,secondary=Color.LightGray,onSecondary=Color.Black,onSurfaceVariant=Color.LightGray,surfaceVariant=Color.DarkGray,error=Color.White,onError=Color.Black,background=Color.Black,surface=Color.Black,onBackground=Color.White,onSurface=Color.White,outline=Color.Gray)){
  when(state.status){CaptureStatus.REVIEW,CaptureStatus.PROCESSING->state.lastRecording?.let{RecordingReportScreen(state,it,onStart,onProcess,onPauseProcessing,onResumeProcessing,interpretationMode)};CaptureStatus.DRAFT->DraftScreen(state,onMode,onSave,onApprove);CaptureStatus.APPROVING->ApprovalProgressScreen(state);CaptureStatus.CLEANUP_PENDING,CaptureStatus.ARCHIVED->PermanentDiaryScreen(state,onRetryCleanup,onStart);else->CaptureControls(state,onStart,onPause,onResume,onMark,onFinalize)}
 }
}

@Composable private fun CaptureControls(state:CaptureUiState,onStart:()->Unit,onPause:()->Unit,onResume:()->Unit,onMark:()->Unit,onFinalize:()->Unit){
 var confirmFinalize by remember{mutableStateOf(false)}
 Column(Modifier.fillMaxSize().background(Color.Black).padding(24.dp),verticalArrangement=Arrangement.SpaceBetween){
  Column{AppLabel();Spacer(Modifier.height(42.dp));Text(when(state.status){CaptureStatus.IDLE->"LISTO";CaptureStatus.RECORDING->"GRABANDO";else->"EN PAUSA"},fontSize=44.sp,fontWeight=FontWeight.Black);if(state.currentBlock>0){Text("BLOQUE ${state.currentBlock}",color=Color.LightGray);Text("TOTAL ${formatDuration(state.totalDurationMs)}",color=Color.LightGray)};state.message?.let{Spacer(Modifier.height(16.dp));Text(it,color=Color.LightGray)}}
  Column(verticalArrangement=Arrangement.spacedBy(12.dp)){when(state.status){CaptureStatus.IDLE->MonoButton("EMPEZAR DÍA",!state.busy,onStart);CaptureStatus.RECORDING->{MonoButton("PAUSAR",!state.busy,onPause);MonoButton("MARCAR TAREA",!state.busy,onMark)};CaptureStatus.PAUSED->MonoButton("REANUDAR",!state.busy,onResume);else->Unit};if(state.status==CaptureStatus.RECORDING||state.status==CaptureStatus.PAUSED)MonoButton("FINALIZAR DÍA",!state.busy,{confirmFinalize=true},false)}
 }
 if(confirmFinalize)AlertDialog(onDismissRequest={confirmFinalize=false},shape=RectangleShape,containerColor=Color.Black,title={Text("FINALIZAR DÍA")},text={Text("Se cerrará la grabación. Después podrás escucharla y procesarla.")},confirmButton={TextButton(onClick={confirmFinalize=false;onFinalize()}){Text("FINALIZAR")}},dismissButton={TextButton(onClick={confirmFinalize=false}){Text("CANCELAR")}})
}

@Composable private fun RecordingReportScreen(state:CaptureUiState,report:RecordingReport,onStart:()->Unit,onProcess:(InterpretationMode)->Unit,onPauseProcessing:()->Unit,onResumeProcessing:(InterpretationMode)->Unit,interpretationMode:InterpretationMode){
 var player by remember(report.sessionId){mutableStateOf<MediaPlayer?>(null)};var playingId by remember(report.sessionId){mutableStateOf<SegmentId?>(null)};var playbackError by remember(report.sessionId){mutableStateOf<String?>(null)}
 DisposableEffect(report.sessionId){onDispose{player?.release()}}
 fun stop(){player?.release();player=null;playingId=null}
 fun toggle(segment:SegmentSummary){if(playingId==segment.id){stop();return};stop();playbackError=null;val next=MediaPlayer();runCatching{next.setDataSource(segment.path);next.setOnCompletionListener{it.release();player=null;playingId=null};next.prepare();next.start()}.onSuccess{player=next;playingId=segment.id}.onFailure{next.release();playbackError="No se pudo reproducir este segmento"}}
 LazyColumn(Modifier.fillMaxSize().background(Color.Black).padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
  item{Spacer(Modifier.height(24.dp));AppLabel();Spacer(Modifier.height(36.dp));Text(if(state.status==CaptureStatus.PROCESSING)"PROCESANDO" else "DÍA FINALIZADO",fontSize=38.sp,fontWeight=FontWeight.Black);Text(report.pedagogicalDate,color=Color.LightGray)}
  item{VerificationBox(report);playbackError?.let{Text(it,modifier=Modifier.padding(top=12.dp))}}
  items(report.blocks,key={it.id.value}){block->BlockReport(block,playingId,::toggle)}
  item{HorizontalDivider(color=Color.DarkGray);Text("WHISPER LOCAL · ESPAÑOL",fontSize=12.sp,fontWeight=FontWeight.Bold,letterSpacing=2.sp);Text("MODELO INTEGRADO",color=Color.LightGray,fontSize=11.sp,letterSpacing=1.sp);Spacer(Modifier.height(10.dp))
   val preparing=state.progressLabel=="PREPARANDO MODELO";val hasFailure=state.processingFailure!=null||report.segments.any{it.state==SegmentState.FAILED}
   val heading=when{state.status==CaptureStatus.PROCESSING&&state.transcriptionPaused->"EN PAUSA";state.status==CaptureStatus.PROCESSING&&preparing->"PREPARANDO MODELO";state.status==CaptureStatus.PROCESSING->"EN CURSO";hasFailure->"TRANSCRIPCIÓN INTERRUMPIDA";else->"LISTA PARA INICIAR"}
   Text(heading,fontSize=24.sp,fontWeight=FontWeight.Black);Text(state.message.orEmpty(),color=Color.LightGray);Spacer(Modifier.height(14.dp))
   if(state.status==CaptureStatus.PROCESSING){
    if(preparing)LinearProgressIndicator(Modifier.fillMaxWidth(),color=Color.White,trackColor=Color.DarkGray)
    else{LinearProgressIndicator(progress={state.progressPercent/100f},modifier=Modifier.fillMaxWidth(),color=Color.White,trackColor=Color.DarkGray);Spacer(Modifier.height(8.dp));Text("${state.progressPercent}% confirmado",color=Color.LightGray,fontSize=12.sp);Text("${formatDuration(state.processedMs)} / ${formatDuration(state.processingTotalMs)}",color=Color.LightGray,fontSize=12.sp)}
    Spacer(Modifier.height(12.dp))
    if(state.transcriptionPaused)MonoButton("RETOMAR",!state.busy,{onResumeProcessing(interpretationMode)})
    else if(!preparing)MonoButton("PAUSAR PROCESAMIENTO",!state.busy,onPauseProcessing,false)
   }else MonoButton(if(hasFailure)"REINTENTAR" else "PROCESAR AUDIO",report.allAudioReady&&!state.busy,{if(hasFailure)onResumeProcessing(interpretationMode) else onProcess(interpretationMode)})}
  item{Text("El audio se conserva durante toda esta fase. Un fallo de transcripción no lo elimina.",color=Color.LightGray,fontSize=12.sp);Spacer(Modifier.height(8.dp));MonoButton("COMENZAR OTRO DÍA",state.status!=CaptureStatus.PROCESSING,onStart,false);Spacer(Modifier.height(24.dp))}
 }
}

@Composable private fun DraftScreen(state:CaptureUiState,onMode:(InterpretationMode,String,String,String,String,String)->Unit,onSave:(String,String,String,String,String)->Unit,onApprove:(String,String,String,String,String)->Unit){
 val draft=state.draft?:return;val clipboard=LocalClipboardManager.current
 var topics by remember(draft.updatedAtEpochMs){mutableStateOf(draft.topics)};var activities by remember(draft.updatedAtEpochMs){mutableStateOf(draft.activities)};var pages by remember(draft.updatedAtEpochMs){mutableStateOf(draft.pages)};var exercises by remember(draft.updatedAtEpochMs){mutableStateOf(draft.exercises)};var homework by remember(draft.updatedAtEpochMs){mutableStateOf(draft.homework)}
 var confirmApproval by remember{mutableStateOf(false)}
 val projection=remember(state.claims,draft.mode){InterpretationProjector().project(state.claims,InterpretationMode.valueOf(draft.mode))}
 val copyText=DiaryClipboardFormatter().formatDraft(state.lastRecording?.pedagogicalDate,topics,activities,pages,exercises,homework)
 LazyColumn(Modifier.fillMaxSize().background(Color.Black).padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
  item{Spacer(Modifier.height(24.dp));AppLabel();Spacer(Modifier.height(36.dp));Text("FICHA DEL DÍA",fontSize=38.sp,fontWeight=FontWeight.Black);Text("BORRADOR EDITABLE",color=Color.LightGray)}
  item{Text("INTERPRETACIÓN",fontSize=12.sp,fontWeight=FontWeight.Bold,letterSpacing=2.sp);Spacer(Modifier.height(8.dp));Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)){val changeMode:(InterpretationMode)->Unit={mode->onMode(mode,topics,activities,pages,exercises,homework)};ModeButton("CONSERVADOR",InterpretationMode.CONSERVATIVE,draft.mode,!state.busy,changeMode);ModeButton("EQUILIBRADO",InterpretationMode.BALANCED,draft.mode,!state.busy,changeMode);ModeButton("EXHAUSTIVO",InterpretationMode.EXHAUSTIVE,draft.mode,!state.busy,changeMode)};Text("Conservador incluye solo datos explícitos y deja dudas por confirmar.",color=Color.LightGray,fontSize=12.sp)}
  item{val editorEnabled=draftEditorEnabled(state.busy);DraftField("TEMAS",topics,editorEnabled){topics=it};DraftField("ACTIVIDADES REALIZADAS",activities,editorEnabled){activities=it};DraftField("PÁGINAS",pages,editorEnabled){pages=it};DraftField("EJERCICIOS HECHOS",exercises,editorEnabled){exercises=it};DraftField("TAREA",homework,editorEnabled){homework=it}}
  item{MonoButton("GUARDAR CAMBIOS",!state.busy,{onSave(topics,activities,pages,exercises,homework)});Spacer(Modifier.height(8.dp));MonoButton("APROBAR Y BORRAR AUDIO",!state.busy,{confirmApproval=true});Spacer(Modifier.height(8.dp));MonoButton("COPIAR DIARIO",true,{clipboard.setText(AnnotatedString(copyText))},false);state.message?.let{Text(it,color=Color.LightGray,modifier=Modifier.padding(top=8.dp))}}
  item{EvidenceSection("EVIDENCIA ACEPTADA",projection.accepted)}
  item{EvidenceSection("POR CONFIRMAR",projection.confirm);if(projection.confirm.isEmpty())Text("No hay elementos dudosos visibles.",color=Color.LightGray)}
 item{Text("El audio todavía está guardado. Solo se eliminará después de que confirmes APROBAR Y BORRAR AUDIO.",color=Color.LightGray,fontSize=12.sp);Spacer(Modifier.height(28.dp))}
 }
 if(confirmApproval)AlertDialog(onDismissRequest={confirmApproval=false},shape=RectangleShape,containerColor=Color.Black,title={Text("APROBAR Y BORRAR AUDIO")},text={Text("La fecha y los cinco campos permanecen editables. El audio, la transcripción y la evidencia se eliminarán permanentemente.")},confirmButton={TextButton(onClick={confirmApproval=false;onApprove(topics,activities,pages,exercises,homework)}){Text("APROBAR")}},dismissButton={TextButton(onClick={confirmApproval=false}){Text("CANCELAR")}})
}

@Composable private fun ApprovalProgressScreen(state:CaptureUiState){Column(Modifier.fillMaxSize().background(Color.Black).padding(24.dp),verticalArrangement=Arrangement.Center){AppLabel();Spacer(Modifier.height(36.dp));Text("APROBANDO",fontSize=38.sp,fontWeight=FontWeight.Black);Spacer(Modifier.height(16.dp));LinearProgressIndicator(Modifier.fillMaxWidth(),color=Color.White,trackColor=Color.DarkGray);Spacer(Modifier.height(16.dp));Text(state.message.orEmpty(),color=Color.LightGray)}}
@Composable private fun PermanentDiaryScreen(state:CaptureUiState,onRetryCleanup:()->Unit,onStart:()->Unit){val draft=state.draft;LazyColumn(Modifier.fillMaxSize().background(Color.Black).padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){item{Spacer(Modifier.height(24.dp));AppLabel();Spacer(Modifier.height(36.dp));Text(if(state.status==CaptureStatus.CLEANUP_PENDING)"LIMPIEZA PENDIENTE" else "DIARIO ARCHIVADO",fontSize=34.sp,fontWeight=FontWeight.Black);Text(state.lastRecording?.pedagogicalDate.orEmpty(),color=Color.LightGray);Spacer(Modifier.height(12.dp));Text(state.message.orEmpty(),color=Color.LightGray);if(state.status==CaptureStatus.CLEANUP_PENDING)Text("La ficha permanente está segura. Podés reintentar sin generar ni sobrescribir los campos.",color=Color.LightGray,fontSize=12.sp)};item{PermanentField("TEMAS",draft?.topics.orEmpty());PermanentField("ACTIVIDADES REALIZADAS",draft?.activities.orEmpty());PermanentField("PÁGINAS",draft?.pages.orEmpty());PermanentField("EJERCICIOS HECHOS",draft?.exercises.orEmpty());PermanentField("TAREA",draft?.homework.orEmpty())};if(state.status==CaptureStatus.CLEANUP_PENDING)item{MonoButton("REINTENTAR LIMPIEZA",!state.busy,onRetryCleanup)};if(state.status==CaptureStatus.ARCHIVED)item{MonoButton("NUEVO DÍA",!state.busy,onStart)};item{Spacer(Modifier.height(28.dp))}}}
@Composable private fun PermanentField(label:String,value:String){Column(Modifier.fillMaxWidth().border(1.dp,Color.DarkGray).padding(12.dp)){Text(label,fontSize=11.sp,fontWeight=FontWeight.Bold,letterSpacing=1.sp);Spacer(Modifier.height(6.dp));Text(value.ifBlank{"—"},color=Color.LightGray)}}

@Composable private fun RowScope.ModeButton(label:String,mode:InterpretationMode,current:String,enabled:Boolean,onMode:(InterpretationMode)->Unit){val selected=current==mode.name;Button(onClick={onMode(mode)},enabled=enabled,shape=RectangleShape,modifier=Modifier.weight(1f).height(50.dp).border(1.dp,if(selected)Color.White else Color.DarkGray),contentPadding=PaddingValues(3.dp),colors=ButtonDefaults.buttonColors(containerColor=if(selected)Color.White else Color.Black,contentColor=if(selected)Color.Black else Color.White)){Text(label,fontSize=9.sp,fontWeight=FontWeight.Bold)}}
@Composable private fun DraftField(label:String,value:String,enabled:Boolean,onValue:(String)->Unit){Text(label,fontSize=11.sp,fontWeight=FontWeight.Bold,letterSpacing=1.sp);OutlinedTextField(value,onValue,Modifier.fillMaxWidth(),enabled=enabled,shape=RectangleShape,minLines=2,colors=OutlinedTextFieldDefaults.colors(focusedBorderColor=Color.White,unfocusedBorderColor=Color.DarkGray));Spacer(Modifier.height(14.dp))}
internal fun draftEditorEnabled(busy:Boolean)=!busy
@Composable private fun EvidenceSection(title:String,claims:List<EvidenceClaim>){Text(title,fontSize=12.sp,fontWeight=FontWeight.Bold,letterSpacing=2.sp);Spacer(Modifier.height(8.dp));claims.forEach{claim->Column(Modifier.fillMaxWidth().border(1.dp,Color.DarkGray).padding(12.dp)){Text("${categoryLabel(claim.category)}  ${"%.0f".format(claim.confidence*100)}%",fontSize=11.sp,color=Color.LightGray);Text(claim.value,fontWeight=FontWeight.Bold);Text("“${claim.evidence.excerpt}”",color=Color.LightGray,fontSize=12.sp)}}}
@Composable private fun VerificationBox(report:RecordingReport){val retained=report.segments.count{it.exists};Column(Modifier.fillMaxWidth().border(1.dp,Color.White).padding(16.dp)){Text(if(report.allAudioReady)"AUDIO GUARDADO" else "AUDIO INCOMPLETO",fontSize=24.sp,fontWeight=FontWeight.Black);Spacer(Modifier.height(8.dp));Metric("BLOQUES",report.blocks.size.toString());Metric("SEGMENTOS CONSERVADOS","$retained / ${report.segments.size}");Metric("DURACIÓN GRABADA",formatDuration(report.recordedDurationMs))}}
@Composable private fun BlockReport(block:BlockRecordingSummary,playingId:SegmentId?,onPlay:(SegmentSummary)->Unit){Column(verticalArrangement=Arrangement.spacedBy(10.dp)){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("BLOQUE ${block.number}",fontSize=22.sp,fontWeight=FontWeight.Black);Text(formatDuration(block.durationMs),color=Color.LightGray)};block.segments.forEachIndexed{index,segment->SegmentRow(index+1,segment,playingId==segment.id,onPlay)}}}
@Composable private fun SegmentRow(number:Int,segment:SegmentSummary,playing:Boolean,onPlay:(SegmentSummary)->Unit){val usable=segment.exists;Column(Modifier.fillMaxWidth().border(1.dp,Color.DarkGray).padding(12.dp)){Text("SEGMENTO $number",fontWeight=FontWeight.Bold);Text("${formatDuration(segment.durationMs)}  ·  ${formatBytes(segment.byteCount)}",color=Color.LightGray);Text(segmentStateLabel(segment.state),color=Color.LightGray);Spacer(Modifier.height(8.dp));MonoButton(if(playing)"DETENER" else "ESCUCHAR",usable,{onPlay(segment)},playing)}}
@Composable private fun Metric(label:String,value:String){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(label,fontSize=12.sp,color=Color.LightGray);Text(value,fontWeight=FontWeight.Bold)}}
@Composable private fun AppLabel(){Text("DIARIO DE CLASE",fontSize=13.sp,fontWeight=FontWeight.Bold,letterSpacing=2.sp)}
@Composable private fun MonoButton(text:String,enabled:Boolean,onClick:()->Unit,inverted:Boolean=true){Button(onClick=onClick,enabled=enabled,shape=RectangleShape,modifier=Modifier.fillMaxWidth().height(58.dp).border(1.dp,Color.White),colors=ButtonDefaults.buttonColors(containerColor=if(inverted)Color.White else Color.Black,contentColor=if(inverted)Color.Black else Color.White,disabledContainerColor=Color.DarkGray)){Text(text,fontWeight=FontWeight.Bold,letterSpacing=1.sp)}}
private fun categoryLabel(category:ClaimCategory)=when(category){ClaimCategory.TOPIC->"TEMA";ClaimCategory.ACTIVITY->"ACTIVIDAD";ClaimCategory.PAGE->"PÁGINA";ClaimCategory.EXERCISE->"EJERCICIO";ClaimCategory.HOMEWORK->"TAREA"}
private fun segmentStateLabel(state:SegmentState)=when(state){SegmentState.READY->"LISTO PARA PROCESAR";SegmentState.TRANSCRIBING->"TRANSCRIBIENDO";SegmentState.TRANSCRIBED->"TRANSCRIPCIÓN GUARDADA";SegmentState.FAILED->"FALLÓ. AUDIO CONSERVADO";else->state.name}
private fun formatDuration(durationMs:Long):String{val seconds=durationMs/1_000;return "%02d:%02d:%02d".format(seconds/3_600,(seconds%3_600)/60,seconds%60)}
private fun formatBytes(bytes:Long):String="%.1f MB".format(bytes/1_000_000.0)
