package com.capo.diarioclase.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SessionEntity::class,
        BlockEntity::class,
        AudioSegmentEntity::class,
        MarkerEntity::class,
        TranscriptSpanEntity::class,
        EvidenceClaimEntity::class,
        DiaryDraftEntity::class,
        DiaryEntryEntity::class,
        AppSettingEntity::class,
        TranscriptionRunEntity::class,
        TranscriptionCheckpointEntity::class,
        InterpretationCacheEntity::class,
        InterpretationRunEntity::class,
        InterpretationPacketEntity::class,
        ProviderAttemptEntity::class,
        ClaimEvidenceEntity::class,
        ClaimSupersessionEntity::class,
        DraftFieldRevisionEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
abstract class DiarioDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE audio_segments ADD COLUMN lastTranscriptionFailure TEXT")
                db.execSQL("CREATE TABLE IF NOT EXISTS transcript_spans (id TEXT NOT NULL PRIMARY KEY,audioSegmentId TEXT NOT NULL,blockId TEXT NOT NULL,startMs INTEGER NOT NULL,endMs INTEGER NOT NULL,text TEXT NOT NULL,confidence REAL NOT NULL,FOREIGN KEY(audioSegmentId) REFERENCES audio_segments(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transcript_spans_audioSegmentId ON transcript_spans(audioSegmentId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transcript_spans_blockId ON transcript_spans(blockId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS evidence_claims (id TEXT NOT NULL PRIMARY KEY,sessionId TEXT NOT NULL,category TEXT NOT NULL,value TEXT NOT NULL,normalizedValue TEXT NOT NULL,status TEXT NOT NULL,confidence REAL NOT NULL,origin TEXT NOT NULL,blockId TEXT NOT NULL,startMs INTEGER NOT NULL,endMs INTEGER NOT NULL,excerpt TEXT NOT NULL,active INTEGER NOT NULL,FOREIGN KEY(sessionId) REFERENCES sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_evidence_claims_sessionId ON evidence_claims(sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_evidence_claims_sessionId_category ON evidence_claims(sessionId,category)")
                db.execSQL("CREATE TABLE IF NOT EXISTS diary_drafts (id TEXT NOT NULL PRIMARY KEY,sessionId TEXT NOT NULL,mode TEXT NOT NULL,topics TEXT NOT NULL,activities TEXT NOT NULL,pages TEXT NOT NULL,exercises TEXT NOT NULL,homework TEXT NOT NULL,updatedAtEpochMs INTEGER NOT NULL,FOREIGN KEY(sessionId) REFERENCES sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_diary_drafts_sessionId ON diary_drafts(sessionId)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS diary_entries (id TEXT NOT NULL PRIMARY KEY,sessionId TEXT NOT NULL,pedagogicalDate TEXT NOT NULL,level TEXT,topics TEXT NOT NULL,activities TEXT NOT NULL,pages TEXT NOT NULL,completedExercises TEXT NOT NULL,homework TEXT NOT NULL,approvedAtEpochMs INTEGER NOT NULL,updatedAtEpochMs INTEGER NOT NULL,temporariesDeleted INTEGER NOT NULL)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_diary_entries_sessionId ON diary_entries(sessionId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS app_settings (key TEXT NOT NULL PRIMARY KEY,value TEXT NOT NULL)")
                // Version 2 had no edit-provenance flag. Treat all old drafts as edited to avoid
                // replacing a teacher's corrections. Rebuild without a SQL default to match Room.
                db.execSQL("CREATE TABLE diary_drafts_v3 (id TEXT NOT NULL PRIMARY KEY,sessionId TEXT NOT NULL,mode TEXT NOT NULL,topics TEXT NOT NULL,activities TEXT NOT NULL,pages TEXT NOT NULL,exercises TEXT NOT NULL,homework TEXT NOT NULL,updatedAtEpochMs INTEGER NOT NULL,userEdited INTEGER NOT NULL,FOREIGN KEY(sessionId) REFERENCES sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("INSERT INTO diary_drafts_v3 SELECT id,sessionId,mode,topics,activities,pages,exercises,homework,updatedAtEpochMs,1 FROM diary_drafts")
                db.execSQL("DROP TABLE diary_drafts")
                db.execSQL("ALTER TABLE diary_drafts_v3 RENAME TO diary_drafts")
                db.execSQL("CREATE UNIQUE INDEX index_diary_drafts_sessionId ON diary_drafts(sessionId)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS transcription_runs (sessionId TEXT NOT NULL PRIMARY KEY,state TEXT NOT NULL,pauseRequested INTEGER NOT NULL,processedMs INTEGER NOT NULL,totalMs INTEGER NOT NULL,currentSegmentId TEXT,failure TEXT,updatedAtEpochMs INTEGER NOT NULL,FOREIGN KEY(sessionId) REFERENCES sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE TABLE IF NOT EXISTS transcription_checkpoints (audioSegmentId TEXT NOT NULL PRIMARY KEY,sessionId TEXT NOT NULL,confirmedUntilMs INTEGER NOT NULL,totalMs INTEGER NOT NULL,processedWindows INTEGER NOT NULL,totalWindows INTEGER NOT NULL,state TEXT NOT NULL,failure TEXT,updatedAtEpochMs INTEGER NOT NULL,FOREIGN KEY(audioSegmentId) REFERENCES audio_segments(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transcription_checkpoints_sessionId ON transcription_checkpoints(sessionId)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS interpretation_cache (cacheId TEXT NOT NULL PRIMARY KEY,packetId TEXT NOT NULL,sessionId TEXT NOT NULL,provider TEXT NOT NULL,modelId TEXT NOT NULL,promptVersion TEXT NOT NULL,schemaVersion TEXT NOT NULL,validatedJson TEXT NOT NULL,createdAtEpochMs INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_interpretation_cache_sessionId ON interpretation_cache(sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_interpretation_cache_packetId ON interpretation_cache(packetId)")
            }
        }

        /**
         * v5→v6: persiste el grafo semántico completo. Es aditiva y no destructiva; ningún
         * dato v5 se pierde. Las columnas namespaced de `evidence_claims` traen default para
         * cubrir las filas legacy y luego se rellenan a partir de los valores existentes.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE evidence_claims ADD COLUMN runId TEXT")
                db.execSQL("ALTER TABLE evidence_claims ADD COLUMN packetId TEXT")
                db.execSQL("ALTER TABLE evidence_claims ADD COLUMN providerClaimKey TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE evidence_claims ADD COLUMN declaredConfidence REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE evidence_claims ADD COLUMN effectiveConfidence REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE evidence_claims ADD COLUMN claimOrdinal INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE evidence_claims SET providerClaimKey=id,declaredConfidence=confidence,effectiveConfidence=confidence")

                db.execSQL("CREATE TABLE IF NOT EXISTS interpretation_runs (id TEXT NOT NULL PRIMARY KEY,sessionId TEXT NOT NULL,state TEXT NOT NULL,appVersion TEXT NOT NULL,whisperVersion TEXT NOT NULL,promptVersion TEXT NOT NULL,schemaVersion TEXT NOT NULL,validatorVersion TEXT NOT NULL,transcriptHash TEXT NOT NULL,mode TEXT NOT NULL,provenance TEXT,failure TEXT,startedAtEpochMs INTEGER NOT NULL,completedAtEpochMs INTEGER)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_interpretation_runs_sessionId ON interpretation_runs(sessionId)")

                db.execSQL("CREATE TABLE IF NOT EXISTS interpretation_packets (runId TEXT NOT NULL,packetId TEXT NOT NULL,ordinal INTEGER NOT NULL,state TEXT NOT NULL,requestHash TEXT NOT NULL,requestBytes INTEGER NOT NULL,provider TEXT,startedAtEpochMs INTEGER,completedAtEpochMs INTEGER,PRIMARY KEY(runId,packetId))")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_interpretation_packets_runId ON interpretation_packets(runId)")

                db.execSQL("CREATE TABLE IF NOT EXISTS provider_attempts (id TEXT NOT NULL PRIMARY KEY,runId TEXT NOT NULL,packetId TEXT NOT NULL,provider TEXT NOT NULL,modelId TEXT NOT NULL,attempt INTEGER NOT NULL,cacheHit INTEGER NOT NULL,outcome TEXT NOT NULL,durationMs INTEGER NOT NULL,startedAtEpochMs INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_provider_attempts_runId_packetId ON provider_attempts(runId,packetId)")

                db.execSQL("CREATE TABLE IF NOT EXISTS claim_evidence (claimId TEXT NOT NULL,transcriptSpanId TEXT NOT NULL,ordinal INTEGER NOT NULL,contextual INTEGER NOT NULL,PRIMARY KEY(claimId,transcriptSpanId))")

                db.execSQL("CREATE TABLE IF NOT EXISTS claim_supersessions (newClaimId TEXT NOT NULL,oldClaimId TEXT NOT NULL,PRIMARY KEY(newClaimId,oldClaimId))")
            }
        }

        /**
         * v6→v7: revisión estructurada del docente (Fase 6, Q4). Agrega la máscara de edición por
         * campo a `diary_drafts` y la tabla `draft_field_revisions`. Es aditiva y no destructiva.
         * Las filas legacy con `userEdited=1` marcan los cinco campos como editados, de forma
         * conservadora, para no reemplazar correcciones previas del docente.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE diary_drafts ADD COLUMN editedTopics INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE diary_drafts ADD COLUMN editedActivities INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE diary_drafts ADD COLUMN editedPages INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE diary_drafts ADD COLUMN editedExercises INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE diary_drafts ADD COLUMN editedHomework INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "UPDATE diary_drafts SET editedTopics=userEdited,editedActivities=userEdited," +
                        "editedPages=userEdited,editedExercises=userEdited,editedHomework=userEdited",
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS draft_field_revisions (id TEXT NOT NULL PRIMARY KEY," +
                        "sessionId TEXT NOT NULL,field TEXT NOT NULL,beforeValue TEXT NOT NULL," +
                        "afterValue TEXT NOT NULL,actor TEXT NOT NULL,action TEXT NOT NULL," +
                        "claimId TEXT,createdAtEpochMs INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_draft_field_revisions_sessionId ON draft_field_revisions(sessionId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_draft_field_revisions_claimId ON draft_field_revisions(claimId)")
            }
        }
    }
}
