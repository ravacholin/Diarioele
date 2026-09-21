package com.capo.diarioclase.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.capo.diarioclase.core.clock.Clock
import com.capo.diarioclase.processing.evidence.DiaryDraft
import com.capo.diarioclase.processing.evidence.ClaimCategory
import com.capo.diarioclase.processing.evidence.ClaimOrigin
import com.capo.diarioclase.processing.evidence.ClaimStatus
import com.capo.diarioclase.processing.evidence.EvidenceClaim
import com.capo.diarioclase.processing.evidence.EvidenceRef
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
    @Test fun `version eight to nine preserves legacy diaries and creates editorial table`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-8-9-${UUID.randomUUID()}.db"
        val v8 = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(8) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE diary_entries (id TEXT NOT NULL PRIMARY KEY,sessionId TEXT NOT NULL,pedagogicalDate TEXT NOT NULL,level TEXT,topics TEXT NOT NULL,activities TEXT NOT NULL,pages TEXT NOT NULL,completedExercises TEXT NOT NULL,homework TEXT NOT NULL,approvedAtEpochMs INTEGER NOT NULL,updatedAtEpochMs INTEGER NOT NULL,temporariesDeleted INTEGER NOT NULL)")
                        db.execSQL("CREATE UNIQUE INDEX index_diary_entries_sessionId ON diary_entries(sessionId)")
                        db.execSQL("INSERT INTO diary_entries VALUES ('d','s','2026-09-20',NULL,'tema legacy','','','','',1,1,1)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("unused")
                }).build(),
        )
        v8.writableDatabase
        v8.close()

        val v9 = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(9) {
                    override fun onCreate(db: SupportSQLiteDatabase) = error("unused")
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        DiarioDatabase.MIGRATION_8_9.migrate(db)
                    }
                }).build(),
        )
        try {
            val db = v9.writableDatabase
            assertEquals("tema legacy", queryString(db, "SELECT topics FROM diary_entries"))
            assertEquals(0, queryCount(db, "editorial_reports"))
            assertEquals("", queryString(db, "SELECT reportSummary FROM diary_entries"))
        } finally {
            v9.close()
            context.deleteDatabase(name)
        }
    }

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
            .addMigrations(
                DiarioDatabase.MIGRATION_2_3,
                DiarioDatabase.MIGRATION_3_4,
                DiarioDatabase.MIGRATION_4_5,
                DiarioDatabase.MIGRATION_5_6,
                DiarioDatabase.MIGRATION_6_7,
                DiarioDatabase.MIGRATION_7_8,
                DiarioDatabase.MIGRATION_8_9,
            ).allowMainThreadQueries().build()
        try {
            assertEquals(9, database.openHelper.writableDatabase.version)
            assertEquals(0, queryCount(database.openHelper.writableDatabase, "transcription_runs"))
            assertEquals(0, queryCount(database.openHelper.writableDatabase, "transcription_checkpoints"))
            assertEquals(0, queryCount(database.openHelper.writableDatabase, "interpretation_cache"))
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
    @Test fun `version five to six migration preserves data and adds empty semantic tables`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-5-6-${UUID.randomUUID()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(5) {
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
                        DiarioDatabase.MIGRATION_2_3.migrate(db)
                        DiarioDatabase.MIGRATION_3_4.migrate(db)
                        DiarioDatabase.MIGRATION_4_5.migrate(db)
                        db.execSQL("INSERT INTO sessions VALUES ('s1','2026-09-13',NULL,'AWAITING_REVIEW',1,1)")
                        // Dos claims con el mismo valor de proveedor pero de paquetes distintos: en v5 se
                        // distinguen solo por su id de fila. La migración les asigna providerClaimKey y confianzas.
                        db.execSQL("INSERT INTO evidence_claims VALUES ('packet-a-C1','s1','PAGE','14','14','PERFORMED',0.9,'GEMINI','b1',1000,2000,'página catorce',1)")
                        db.execSQL("INSERT INTO evidence_claims VALUES ('packet-b-C1','s1','PAGE','14','14','PERFORMED',0.8,'GROQ','b1',3000,4000,'página catorce',1)")
                        db.execSQL("INSERT INTO diary_drafts VALUES ('draft','s1','CONSERVATIVE','Tema','Actividad','14','','Tarea',1,1)")
                        db.execSQL("INSERT INTO interpretation_cache VALUES ('cache-1','packet-a','s1','GEMINI','gemini-free','p1','s1','{\"claims\":[]}',1)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("unused")
                }).build(),
        )
        try {
            assertEquals(5, helper.writableDatabase.version)
        } finally {
            helper.close()
        }
        val database = Room.databaseBuilder(context, DiarioDatabase::class.java, name)
            .addMigrations(
                DiarioDatabase.MIGRATION_5_6,
                DiarioDatabase.MIGRATION_6_7,
                DiarioDatabase.MIGRATION_7_8,
                DiarioDatabase.MIGRATION_8_9,
            )
            .allowMainThreadQueries().build()
        try {
            // Abrir con Room dispara la validación completa del esquema v8.
            val db = database.openHelper.writableDatabase
            assertEquals(9, db.version)
            // Datos v5 sobreviven.
            assertEquals(1, queryCount(db, "sessions"))
            assertEquals(2, queryCount(db, "evidence_claims"))
            assertEquals(1, queryCount(db, "diary_drafts"))
            assertEquals(1, queryCount(db, "interpretation_cache"))
            // Las columnas namespaced se rellenan desde los valores existentes.
            assertEquals("packet-a-C1", queryString(db, "SELECT providerClaimKey FROM evidence_claims WHERE id='packet-a-C1'"))
            assertEquals(0.9, queryDouble(db, "SELECT declaredConfidence FROM evidence_claims WHERE id='packet-a-C1'"), 0.0001)
            assertEquals(0.9, queryDouble(db, "SELECT effectiveConfidence FROM evidence_claims WHERE id='packet-a-C1'"), 0.0001)
            // Las tablas nuevas del grafo semántico existen y están vacías.
            assertEquals(0, queryCount(db, "interpretation_runs"))
            assertEquals(0, queryCount(db, "interpretation_packets"))
            assertEquals(0, queryCount(db, "provider_attempts"))
            assertEquals(0, queryCount(db, "claim_evidence"))
            assertEquals(0, queryCount(db, "claim_supersessions"))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun `version six to seven adds review tables, protects edited fields and records revisions`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-6-7-${UUID.randomUUID()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(6) {
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
                        DiarioDatabase.MIGRATION_2_3.migrate(db)
                        DiarioDatabase.MIGRATION_3_4.migrate(db)
                        DiarioDatabase.MIGRATION_4_5.migrate(db)
                        DiarioDatabase.MIGRATION_5_6.migrate(db)
                        db.execSQL("INSERT INTO sessions VALUES ('legacy','2026-09-15',NULL,'AWAITING_REVIEW',1,1)")
                        // Ficha legacy marcada como editada: la máscara debe cubrir los cinco campos.
                        db.execSQL("INSERT INTO diary_drafts VALUES ('draft','legacy','CONSERVATIVE','Tema editado','Actividad','14','7','Tarea editada',1,1)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("unused")
                }).build(),
        )
        try {
            assertEquals(6, helper.writableDatabase.version)
        } finally {
            helper.close()
        }
        val database = Room.databaseBuilder(context, DiarioDatabase::class.java, name)
            .addMigrations(
                DiarioDatabase.MIGRATION_6_7,
                DiarioDatabase.MIGRATION_7_8,
                DiarioDatabase.MIGRATION_8_9,
            )
            .allowMainThreadQueries().build()
        try {
            val db = database.openHelper.writableDatabase // valida el esquema v8 completo
            assertEquals(9, db.version)
            assertEquals(0, queryCount(db, "draft_field_revisions"))
            // La ficha legacy editada propaga la máscara a los cinco campos.
            val legacy = database.sessions().draft("legacy")!!
            assertTrue(legacy.editedTopics && legacy.editedHomework && legacy.editedPages)

            val store = RoomProcessingStore(database, Clock { 5 })

            // Una edición de tarea protege solo la tarea (máscara por campo).
            database.sessions().insertSession(SessionEntity("s2", "2026-09-15", null, "AWAITING_REVIEW", 2, 2))
            store.saveEvidence(SessionId("s2"), emptyList(), DiaryDraft("s2", InterpretationMode.CONSERVATIVE, "Auto", "", "", "", "", emptyList(), emptyList()))
            store.saveFieldEdit(SessionId("s2"), com.capo.diarioclase.processing.semantic.DiaryField.HOMEWORK, "Ejercicio 4")
            assertTrue(store.isFieldEdited(SessionId("s2"), com.capo.diarioclase.processing.semantic.DiaryField.HOMEWORK))
            assertFalse(store.isFieldEdited(SessionId("s2"), com.capo.diarioclase.processing.semantic.DiaryField.TOPICS))
            store.saveEvidence(
                SessionId("s2"),
                listOf(
                    EvidenceClaim(
                        id = "claim-1",
                        category = ClaimCategory.PAGE,
                        value = "1",
                        normalizedValue = "1",
                        status = ClaimStatus.PERFORMED,
                        confidence = 1.0,
                        origin = ClaimOrigin.LOCAL_RULE,
                        evidence = EvidenceRef(BlockId("b"), 0, 1, "página 1"),
                    ),
                ),
                DiaryDraft("s2", InterpretationMode.CONSERVATIVE, "Auto 2", "", "", "", "Tarea automática", emptyList(), emptyList()),
            )
            val protected = database.sessions().draft("s2")!!
            assertEquals("Ejercicio 4", protected.homework) // protegida
            assertEquals("Auto 2", protected.topics) // no editada, se actualiza

            // Rechazar un claim registra una revisión.
            store.reviewClaim("claim-1", ReviewAction.REJECT, null)
            assertEquals(ReviewAction.REJECT, store.revisions("claim-1").single().action)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun `version seven to eight adds reason and summary preserving data`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-7-8-${UUID.randomUUID()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(7) {
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
                        DiarioDatabase.MIGRATION_2_3.migrate(db)
                        DiarioDatabase.MIGRATION_3_4.migrate(db)
                        DiarioDatabase.MIGRATION_4_5.migrate(db)
                        DiarioDatabase.MIGRATION_5_6.migrate(db)
                        DiarioDatabase.MIGRATION_6_7.migrate(db)
                        db.execSQL("INSERT INTO sessions VALUES ('legacy','2026-09-17',NULL,'AWAITING_REVIEW',1,1)")
                        db.execSQL("INSERT INTO diary_drafts VALUES ('draft','legacy','CONSERVATIVE','Tema','Actividad','14','7','Tarea',1,0,0,0,0,0,0)")
                        db.execSQL("INSERT INTO evidence_claims VALUES ('c1','legacy','PAGE','14','14','PERFORMED',0.9,'GEMINI','b1',1000,2000,'página catorce',1,NULL,NULL,'c1',0.9,0.9,0)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("unused")
                }).build(),
        )
        try {
            assertEquals(7, helper.writableDatabase.version)
        } finally {
            helper.close()
        }
        val database = Room.databaseBuilder(context, DiarioDatabase::class.java, name)
            .addMigrations(DiarioDatabase.MIGRATION_7_8, DiarioDatabase.MIGRATION_8_9)
            .allowMainThreadQueries().build()
        try {
            val db = database.openHelper.writableDatabase // valida el esquema v8 completo
            assertEquals(9, db.version)
            // Datos v7 sobreviven; las columnas nuevas traen su default.
            assertEquals("Tema", queryString(db, "SELECT topics FROM diary_drafts WHERE id='draft'"))
            assertEquals("", queryString(db, "SELECT summary FROM diary_drafts WHERE id='draft'"))
            assertEquals(1, queryCount(db, "evidence_claims"))
            assertEquals(1, queryCount(db, "evidence_claims WHERE reason IS NULL"))

            // Las columnas nuevas se escriben y leen a través del store (mapeo Room correcto).
            val store = RoomProcessingStore(database, Clock { 9 })
            database.sessions().insertSession(SessionEntity("s2", "2026-09-17", null, "EXTRACTING", 2, 2))
            store.saveEvidence(
                SessionId("s2"), emptyList(),
                DiaryDraft("s2", InterpretationMode.CONSERVATIVE, "Auto", "", "", "", "", emptyList(), emptyList(), summary = "Resumen de prueba"),
            )
            assertEquals("Resumen de prueba", database.sessions().draft("s2")!!.summary)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private fun queryCount(database: SupportSQLiteDatabase, table: String): Int =
        database.query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private fun queryString(database: SupportSQLiteDatabase, sql: String): String =
        database.query(sql).use { cursor ->
            cursor.moveToFirst()
            cursor.getString(0)
        }

    private fun queryDouble(database: SupportSQLiteDatabase, sql: String): Double =
        database.query(sql).use { cursor ->
            cursor.moveToFirst()
            cursor.getDouble(0)
        }
}
