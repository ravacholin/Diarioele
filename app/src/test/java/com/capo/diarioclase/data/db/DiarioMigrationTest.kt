package com.capo.diarioclase.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.capo.diarioclase.core.clock.Clock
import com.capo.diarioclase.processing.evidence.DiaryDraft
import com.capo.diarioclase.processing.evidence.InterpretationMode
import com.capo.diarioclase.processing.work.RoomProcessingStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class DiarioMigrationTest {
    @Test fun `version two migration protects existing edited drafts through reprocessing`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-${UUID.randomUUID()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE sessions (id TEXT NOT NULL PRIMARY KEY,pedagogicalDate TEXT NOT NULL,level TEXT,state TEXT NOT NULL,startedAtEpochMs INTEGER NOT NULL,updatedAtEpochMs INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE blocks (id TEXT NOT NULL PRIMARY KEY,sessionId TEXT NOT NULL,ordinal INTEGER NOT NULL,startedAtEpochMs INTEGER NOT NULL,endedAtEpochMs INTEGER,closeReason TEXT,FOREIGN KEY(sessionId) REFERENCES sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                        db.execSQL("CREATE INDEX index_blocks_sessionId ON blocks(sessionId)")
                        db.execSQL("CREATE TABLE audio_segments (id TEXT NOT NULL PRIMARY KEY,blockId TEXT NOT NULL,ordinal INTEGER NOT NULL,path TEXT NOT NULL,byteCount INTEGER NOT NULL,durationMs INTEGER NOT NULL,sha256 TEXT,state TEXT NOT NULL,transcriptionAttempts INTEGER NOT NULL,FOREIGN KEY(blockId) REFERENCES blocks(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                        db.execSQL("CREATE INDEX index_audio_segments_blockId ON audio_segments(blockId)")
                        db.execSQL("CREATE UNIQUE INDEX index_audio_segments_path ON audio_segments(path)")
                        db.execSQL("CREATE TABLE markers (id TEXT NOT NULL PRIMARY KEY,sessionId TEXT NOT NULL,blockId TEXT NOT NULL,absoluteEpochMs INTEGER NOT NULL,offsetMs INTEGER NOT NULL,type TEXT NOT NULL,note TEXT,FOREIGN KEY(blockId) REFERENCES blocks(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                        db.execSQL("CREATE INDEX index_markers_sessionId ON markers(sessionId)")
                        db.execSQL("CREATE INDEX index_markers_blockId ON markers(blockId)")
                        DiarioDatabase.MIGRATION_1_2.migrate(db)
                        db.execSQL("INSERT INTO sessions VALUES ('legacy','2026-09-11',NULL,'AWAITING_REVIEW',1,1)")
                        db.execSQL("INSERT INTO diary_drafts VALUES ('draft','legacy','CONSERVATIVE','Tema editado','Actividad oral','42','7','Tarea escrita',1)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("unused")
                }).build(),
        )
        try {
            assertEquals(2, helper.writableDatabase.version)
        } finally {
            helper.close()
        }
        val database = Room.databaseBuilder(context, DiarioDatabase::class.java, name)
            .addMigrations(DiarioDatabase.MIGRATION_2_3).allowMainThreadQueries().build()
        try {
            val legacy = database.sessions().draft("legacy")!! // Opening invokes Room's full schema validation.
            assertTrue(legacy.userEdited)
            val store = RoomProcessingStore(database, Clock { 2 })
            store.saveEvidence(SessionId("legacy"), emptyList(), DiaryDraft("legacy", InterpretationMode.EXHAUSTIVE, "Automático", "", "", "", "", emptyList(), emptyList()))
            val protected = database.sessions().draft("legacy")!!
            assertEquals(listOf("Tema editado", "Actividad oral", "42", "7", "Tarea escrita"), listOf(protected.topics, protected.activities, protected.pages, protected.exercises, protected.homework))
            database.sessions().insertSession(SessionEntity("new", "2026-09-12", null, "EXTRACTING", 2, 2))
            store.saveEvidence(SessionId("new"), emptyList(), DiaryDraft("new", InterpretationMode.CONSERVATIVE, "Nuevo", "", "", "", "", emptyList(), emptyList()))
            assertFalse(database.sessions().draft("new")!!.userEdited)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
