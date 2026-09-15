package com.capo.diarioclase.data.db
import androidx.room.*
import kotlinx.coroutines.flow.Flow
@Dao interface SessionDao {
 @Insert suspend fun insertSession(x:SessionEntity); @Update suspend fun updateSession(x:SessionEntity)
 @Insert suspend fun insertBlock(x:BlockEntity); @Update suspend fun updateBlock(x:BlockEntity)
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun saveSegment(x:AudioSegmentEntity)
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun saveTranscriptionRun(run:TranscriptionRunEntity)
 @Query("SELECT * FROM transcription_runs WHERE sessionId=:sessionId LIMIT 1") suspend fun transcriptionRun(sessionId:String):TranscriptionRunEntity?
 @Query("SELECT * FROM transcription_runs WHERE sessionId=:sessionId LIMIT 1") fun observeTranscriptionRun(sessionId:String):Flow<TranscriptionRunEntity?>
 @Query("UPDATE transcription_runs SET pauseRequested=1 WHERE sessionId=:sessionId") suspend fun requestTranscriptionPause(sessionId:String):Int
 @Query("UPDATE transcription_runs SET pauseRequested=0 WHERE sessionId=:sessionId") suspend fun clearTranscriptionPause(sessionId:String):Int
 @Query("DELETE FROM transcription_runs WHERE sessionId=:sessionId") suspend fun deleteTranscriptionRun(sessionId:String)
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun saveCheckpoint(checkpoint:TranscriptionCheckpointEntity)
 @Query("SELECT * FROM transcription_checkpoints WHERE audioSegmentId=:audioSegmentId LIMIT 1") suspend fun checkpoint(audioSegmentId:String):TranscriptionCheckpointEntity?
 @Query("SELECT * FROM transcription_checkpoints WHERE sessionId=:sessionId ORDER BY audioSegmentId") suspend fun checkpoints(sessionId:String):List<TranscriptionCheckpointEntity>
 @Query("DELETE FROM transcription_checkpoints WHERE sessionId=:sessionId") suspend fun deleteCheckpoints(sessionId:String)
 @Query("SELECT * FROM audio_segments WHERE id=:id") suspend fun segment(id:String):AudioSegmentEntity?
 @Insert suspend fun insertMarker(x:MarkerEntity)
 @Query("SELECT * FROM markers WHERE blockId=:id ORDER BY offsetMs") suspend fun markers(id:String):List<MarkerEntity>
 @Query("SELECT * FROM sessions WHERE id=:id") suspend fun session(id:String):SessionEntity?
 @Query("SELECT * FROM blocks WHERE id=:id") suspend fun block(id:String):BlockEntity?
 @Query("SELECT COUNT(*) FROM blocks WHERE sessionId=:id") suspend fun blockCount(id:String):Int
 @Query("SELECT * FROM blocks WHERE sessionId=:id AND endedAtEpochMs IS NULL LIMIT 1") suspend fun openBlock(id:String):BlockEntity?
 @Query("SELECT * FROM sessions WHERE state IN ('RECORDING','PAUSED') ORDER BY startedAtEpochMs DESC LIMIT 1") suspend fun activeSession():SessionEntity?
 @Transaction @Query("SELECT * FROM sessions WHERE state IN ('RECORDING','PAUSED') ORDER BY startedAtEpochMs DESC LIMIT 1") fun observeActiveSession():Flow<SessionWithBlocks?>
 @Transaction @Query("SELECT * FROM sessions WHERE state IN ('FINALIZED','TRANSCRIBING','EXTRACTING','AWAITING_REVIEW') ORDER BY updatedAtEpochMs DESC LIMIT 1") fun observeLatestFinalizedSession():Flow<SessionWithBlocks?>
 @Transaction @Query("SELECT * FROM sessions WHERE state IN ('APPROVED','CLEANUP_PENDING') ORDER BY updatedAtEpochMs DESC LIMIT 1") fun observeLatestCleanupPendingSession():Flow<SessionWithBlocks?>
 @Query("SELECT * FROM sessions WHERE state IN ('APPROVED','CLEANUP_PENDING') ORDER BY updatedAtEpochMs DESC LIMIT 1") suspend fun latestCleanupPendingSession():SessionEntity?
 @Query("SELECT audio_segments.* FROM audio_segments INNER JOIN blocks ON blocks.id=audio_segments.blockId WHERE blocks.sessionId=:sessionId ORDER BY blocks.ordinal,audio_segments.ordinal") fun observeSegmentsForSession(sessionId:String):Flow<List<AudioSegmentEntity>>
 @Query("SELECT audio_segments.* FROM audio_segments INNER JOIN blocks ON blocks.id=audio_segments.blockId WHERE blocks.sessionId=:sessionId ORDER BY blocks.ordinal,audio_segments.ordinal") suspend fun processingSegments(sessionId:String):List<AudioSegmentEntity>
 @Query("UPDATE audio_segments SET state='TRANSCRIBING',transcriptionAttempts=transcriptionAttempts+1,lastTranscriptionFailure=NULL WHERE id=:id") suspend fun markTranscribing(id:String)
 @Query("UPDATE audio_segments SET state='TRANSCRIBED',lastTranscriptionFailure=NULL WHERE id=:id") suspend fun markTranscribed(id:String)
 @Query("UPDATE audio_segments SET state='FAILED',lastTranscriptionFailure=:failure WHERE id=:id") suspend fun markTranscriptionFailed(id:String,failure:String)
 @Query("UPDATE audio_segments SET state='FAILED',lastTranscriptionFailure='INTERRUPTED' WHERE state='TRANSCRIBING'") suspend fun recoverInterruptedTranscriptions()
 @Query("DELETE FROM transcript_spans WHERE audioSegmentId=:segmentId") suspend fun deleteTranscript(segmentId:String)
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun insertTranscript(spans:List<TranscriptSpanEntity>)
 @Query("SELECT transcript_spans.* FROM transcript_spans INNER JOIN audio_segments ON audio_segments.id=transcript_spans.audioSegmentId INNER JOIN blocks ON blocks.id=audio_segments.blockId WHERE blocks.sessionId=:sessionId ORDER BY blocks.ordinal,audio_segments.ordinal,transcript_spans.startMs") suspend fun transcript(sessionId:String):List<TranscriptSpanEntity>
 @Query("SELECT * FROM transcript_spans WHERE audioSegmentId=:audioSegmentId ORDER BY startMs,endMs,id") suspend fun transcriptForSegment(audioSegmentId:String):List<TranscriptSpanEntity>
 @Query("DELETE FROM evidence_claims WHERE sessionId=:sessionId AND origin!='USER_EDIT'") suspend fun deleteMachineClaims(sessionId:String)
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun insertClaims(claims:List<EvidenceClaimEntity>)
 @Query("SELECT * FROM evidence_claims WHERE sessionId=:sessionId ORDER BY category,startMs") fun observeClaims(sessionId:String):Flow<List<EvidenceClaimEntity>>
 @Query("SELECT * FROM evidence_claims WHERE sessionId=:sessionId ORDER BY category,startMs") suspend fun claimsSnapshot(sessionId:String):List<EvidenceClaimEntity>
 @Query("SELECT * FROM evidence_claims WHERE sessionId=(SELECT id FROM sessions ORDER BY updatedAtEpochMs DESC LIMIT 1) ORDER BY category,startMs") fun observeLatestClaims():Flow<List<EvidenceClaimEntity>>
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun saveDraft(draft:DiaryDraftEntity)
 @Query("SELECT * FROM diary_drafts WHERE sessionId=:sessionId LIMIT 1") suspend fun draft(sessionId:String):DiaryDraftEntity?
 @Query("SELECT diary_drafts.* FROM diary_drafts INNER JOIN sessions ON sessions.id=diary_drafts.sessionId ORDER BY sessions.updatedAtEpochMs DESC LIMIT 1") fun observeLatestDraft():Flow<DiaryDraftEntity?>
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun saveDiary(entry:DiaryEntryEntity)
 @Query("SELECT * FROM diary_entries WHERE sessionId=:sessionId LIMIT 1") suspend fun diaryBySession(sessionId:String):DiaryEntryEntity?
 @Query("SELECT * FROM diary_entries WHERE id=:diaryId LIMIT 1") suspend fun diaryById(diaryId:String):DiaryEntryEntity?
 @Query("UPDATE diary_entries SET temporariesDeleted=1 WHERE sessionId=:sessionId AND id=:diaryId") suspend fun markDiaryTemporariesDeleted(sessionId:String,diaryId:String):Int
 @Query("SELECT * FROM diary_entries ORDER BY pedagogicalDate DESC,updatedAtEpochMs DESC") fun observeDiaries():Flow<List<DiaryEntryEntity>>
 @Query("DELETE FROM diary_entries WHERE id=:diaryId") suspend fun deleteDiary(diaryId:String)
 @Query("DELETE FROM transcript_spans WHERE audioSegmentId IN (SELECT audio_segments.id FROM audio_segments INNER JOIN blocks ON blocks.id=audio_segments.blockId WHERE blocks.sessionId=:sessionId)") suspend fun deleteTranscriptsForSession(sessionId:String)
 @Query("DELETE FROM evidence_claims WHERE sessionId=:sessionId") suspend fun deleteEvidenceForSession(sessionId:String)
 @Query("DELETE FROM diary_drafts WHERE sessionId=:sessionId") suspend fun deleteDraftForSession(sessionId:String)
 @Query("SELECT audio_segments.* FROM audio_segments INNER JOIN blocks ON blocks.id=audio_segments.blockId WHERE blocks.sessionId=:sessionId AND audio_segments.state!='DELETED' ORDER BY blocks.ordinal,audio_segments.ordinal") suspend fun segmentsForCleanup(sessionId:String):List<AudioSegmentEntity>
 @Query("UPDATE audio_segments SET state='DELETED' WHERE id IN (:ids)") suspend fun markSegmentsDeleted(ids:List<String>)
 @Query("SELECT (SELECT COUNT(*) FROM audio_segments INNER JOIN blocks ON blocks.id=audio_segments.blockId WHERE blocks.sessionId=:sessionId AND audio_segments.state!='DELETED') + (SELECT COUNT(*) FROM transcript_spans INNER JOIN audio_segments ON audio_segments.id=transcript_spans.audioSegmentId INNER JOIN blocks ON blocks.id=audio_segments.blockId WHERE blocks.sessionId=:sessionId) + (SELECT COUNT(*) FROM evidence_claims WHERE sessionId=:sessionId) + (SELECT COUNT(*) FROM diary_drafts WHERE sessionId=:sessionId) + (SELECT COUNT(*) FROM transcription_checkpoints WHERE sessionId=:sessionId) + (SELECT COUNT(*) FROM transcription_runs WHERE sessionId=:sessionId) + (SELECT COUNT(*) FROM interpretation_cache WHERE sessionId=:sessionId)") suspend fun temporaryRowCount(sessionId:String):Int
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun saveInterpretationCache(entry:InterpretationCacheEntity)
 @Query("SELECT * FROM interpretation_cache WHERE sessionId=:sessionId AND packetId=:packetId") suspend fun interpretationCache(sessionId:String,packetId:String):List<InterpretationCacheEntity>
 @Query("DELETE FROM interpretation_cache WHERE sessionId=:sessionId") suspend fun deleteInterpretationCacheForSession(sessionId:String)
 @Query("SELECT COUNT(*) FROM interpretation_cache WHERE sessionId=:sessionId") suspend fun interpretationCacheCount(sessionId:String):Int
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun saveInterpretationRun(run:InterpretationRunEntity)
 @Query("SELECT * FROM interpretation_runs WHERE id=:runId LIMIT 1") suspend fun interpretationRun(runId:String):InterpretationRunEntity?
 @Query("UPDATE interpretation_runs SET state=:state,failure=:failure,completedAtEpochMs=:completedAt WHERE id=:runId") suspend fun updateInterpretationRun(runId:String,state:String,failure:String?,completedAt:Long?):Int
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun saveInterpretationPacket(packet:InterpretationPacketEntity)
 @Query("UPDATE interpretation_packets SET state=:state,provider=:provider,completedAtEpochMs=:completedAt WHERE runId=:runId AND packetId=:packetId") suspend fun updateInterpretationPacket(runId:String,packetId:String,state:String,provider:String?,completedAt:Long?):Int
 @Query("SELECT * FROM interpretation_packets WHERE runId=:runId ORDER BY ordinal") suspend fun interpretationPackets(runId:String):List<InterpretationPacketEntity>
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun saveProviderAttempt(attempt:ProviderAttemptEntity)
 @Query("SELECT * FROM provider_attempts WHERE runId=:runId AND packetId=:packetId ORDER BY attempt") suspend fun providerAttempts(runId:String,packetId:String):List<ProviderAttemptEntity>
 @Query("DELETE FROM claim_evidence WHERE claimId=:claimId") suspend fun deleteClaimEvidence(claimId:String)
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun insertClaimEvidence(rows:List<ClaimEvidenceEntity>)
 @Query("SELECT * FROM claim_evidence WHERE claimId=:claimId ORDER BY ordinal") suspend fun claimEvidence(claimId:String):List<ClaimEvidenceEntity>
 @Query("DELETE FROM claim_supersessions WHERE newClaimId=:claimId") suspend fun deleteClaimSupersessions(claimId:String)
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun insertClaimSupersessions(rows:List<ClaimSupersessionEntity>)
 @Query("SELECT oldClaimId FROM claim_supersessions WHERE newClaimId=:claimId ORDER BY oldClaimId") suspend fun claimSupersessions(claimId:String):List<String>
 @Query("SELECT * FROM evidence_claims WHERE runId=:runId ORDER BY claimOrdinal,id") suspend fun claimsForRun(runId:String):List<EvidenceClaimEntity>
 @Query("UPDATE sessions SET state=:state,updatedAtEpochMs=:nowEpochMs WHERE id=:sessionId") suspend fun updateSessionStateUnchecked(sessionId:String,state:String,nowEpochMs:Long)
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun saveSetting(setting:AppSettingEntity)
 @Query("SELECT COALESCE((SELECT value FROM app_settings WHERE key='interpretation_mode' LIMIT 1),'CONSERVATIVE')") fun observeInterpretationMode():Flow<String>
}
