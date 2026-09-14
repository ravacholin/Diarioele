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
    ],
    version = 4,
    exportSchema = false,
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
    }
}
