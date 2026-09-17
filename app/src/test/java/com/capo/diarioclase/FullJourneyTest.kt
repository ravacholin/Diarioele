package com.capo.diarioclase

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.capo.diarioclase.core.clock.Clock
import com.capo.diarioclase.data.db.AudioSegmentEntity
import com.capo.diarioclase.data.db.BlockCloseReason
import com.capo.diarioclase.data.db.BlockEntity
import com.capo.diarioclase.data.db.CerLevel
import com.capo.diarioclase.data.db.DiarioDatabase
import com.capo.diarioclase.data.db.DiaryDraftEntity
import com.capo.diarioclase.data.db.SegmentId
import com.capo.diarioclase.data.db.SegmentState
import com.capo.diarioclase.data.db.SessionEntity
import com.capo.diarioclase.data.db.SessionId
import com.capo.diarioclase.data.db.SessionState
import com.capo.diarioclase.data.db.TranscriptionCheckpointEntity
import com.capo.diarioclase.data.db.TranscriptionRunEntity
import com.capo.diarioclase.data.repository.IdProvider
import com.capo.diarioclase.data.repository.RoomDiaryRepository
import com.capo.diarioclase.data.repository.RoomSessionRepository
import com.capo.diarioclase.data.repository.SessionRepository
import com.capo.diarioclase.diary.DiaryClipboardFormatter
import com.capo.diarioclase.diary.DiaryEntry
import com.capo.diarioclase.diary.cleanup.CleanupCoordinator
import com.capo.diarioclase.diary.cleanup.CleanupOutcome
import com.capo.diarioclase.diary.cleanup.RoomTemporaryCleanupStore
import com.capo.diarioclase.data.db.TranscriptSpanEntity
import com.capo.diarioclase.processing.evidence.ClaimCategory
import com.capo.diarioclase.processing.evidence.ClaimStatus
import com.capo.diarioclase.processing.evidence.DiaryFieldMaterializer
import com.capo.diarioclase.processing.evidence.InterpretationMode
import com.capo.diarioclase.processing.evidence.InterpretationProjector
import com.capo.diarioclase.processing.evidence.PagesAndExercisesComposer
import com.capo.diarioclase.processing.semantic.EphemeralCredential
import com.capo.diarioclase.processing.semantic.FallbackClaimExtractor
import com.capo.diarioclase.processing.semantic.FreeInferenceRouter
import com.capo.diarioclase.processing.semantic.InferenceAttemptContext
import com.capo.diarioclase.processing.semantic.InferenceProvider
import com.capo.diarioclase.processing.semantic.InferenceProviderClient
import com.capo.diarioclase.processing.semantic.InterpretationPacketBuilder
import com.capo.diarioclase.processing.semantic.InterpretationRequest
import com.capo.diarioclase.processing.semantic.ProviderClaimsCodec
import com.capo.diarioclase.processing.semantic.ProviderModel
import com.capo.diarioclase.processing.semantic.ProviderOutcome
import com.capo.diarioclase.processing.semantic.ProviderRetryPolicy
import com.capo.diarioclase.processing.semantic.ProviderSemanticClaim
import com.capo.diarioclase.processing.semantic.RouterSemanticInterpreter
import com.capo.diarioclase.processing.semantic.SemanticClaimReducer
import com.capo.diarioclase.processing.semantic.SemanticResponseValidator
import com.capo.diarioclase.processing.transcription.TranscriptSpan
import com.capo.diarioclase.processing.work.InterpretationBudget
import com.capo.diarioclase.processing.work.LocalDraftReprojector
import com.capo.diarioclase.processing.work.RoomProcessingStore
import com.capo.diarioclase.recording.audio.CleanupFileStore
import com.capo.diarioclase.recording.audio.DeleteResult
import com.capo.diarioclase.recording.audio.FileSegmentStore
import com.capo.diarioclase.ui.capture.CaptureActions
import com.capo.diarioclase.ui.capture.CaptureStatus
import com.capo.diarioclase.ui.capture.CaptureViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FullJourneyTest {
    private lateinit var context: Context
    private lateinit var database: DiarioDatabase
    private lateinit var audioRoot: File
    private lateinit var files: FileSegmentStore
    private lateinit var archive: RoomDiaryRepository
    private lateinit var cleanup: CleanupCoordinator

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, DiarioDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        audioRoot = Files.createTempDirectory("diario-full-journey-").toFile()
        files = FileSegmentStore(audioRoot)
        archive = RoomDiaryRepository(database, Clock { 1_000 }, TestIds())
        cleanup = CleanupCoordinator(archive, files, RoomTemporaryCleanupStore(database.sessions()), Clock { 1_000 })
    }

    @After fun tearDown() {
        database.close()
        audioRoot.deleteRecursively()
    }

    @Test fun `review approval cleanup archive edit search and copy`() = runTest {
        val sessionId = SessionId("session")
        val blockId = "block"
        database.sessions().insertSession(SessionEntity(sessionId.value, "2026-09-12", CerLevel.B1.name, SessionState.AWAITING_REVIEW.name, 1, 1))
        database.sessions().insertBlock(BlockEntity(blockId, sessionId.value, 0, 1, 2, BlockCloseReason.FINALIZED.name))
        val openAudio = files.open(com.capo.diarioclase.data.db.BlockId(blockId), 0)
        files.append(openAudio, shortArrayOf(1, -1, 2, -2), 4)
        val readyAudio = files.close(openAudio)
        assertTrue(File(readyAudio.path).name.endsWith(".ready.wav"))
        database.sessions().saveSegment(AudioSegmentEntity(readyAudio.id.value, blockId, 0, readyAudio.path, File(readyAudio.path).length(), readyAudio.durationMs, readyAudio.sha256, SegmentState.READY.name))
        val draft = DiaryDraftEntity("draft", sessionId.value, InterpretationMode.CONSERVATIVE.name, "Pasado", "Lectura", "12", "3", "Escribir", 1)
        database.sessions().saveDraft(draft)
        database.sessions().saveTranscriptionRun(TranscriptionRunEntity(sessionId.value, "COMPLETED", false, readyAudio.durationMs, readyAudio.durationMs, null, null, 1))
        database.sessions().saveCheckpoint(TranscriptionCheckpointEntity(readyAudio.id.value, sessionId.value, readyAudio.durationMs, readyAudio.durationMs, 1, 1, "COMPLETED", null, 1))
        assertNotNull(database.sessions().transcriptionRun(sessionId.value))

        val outcome = cleanup.approveAndClean(sessionId, draft)

        assertTrue(outcome is CleanupOutcome.Archived)
        val archived = archive.getBySession(sessionId) ?: error("missing permanent diary")
        assertEquals(
            DiaryEntry("diary", sessionId, "2026-09-12", CerLevel.B1, "Pasado", "Lectura", "12", "3", "Escribir", 1_000, 1_000, true),
            archived,
        )
        assertFalse(File(readyAudio.path).exists())
        assertFalse(files.exists(readyAudio.id))
        assertNull(database.sessions().transcriptionRun(sessionId.value))
        assertTrue(database.sessions().checkpoints(sessionId.value).isEmpty())
        assertEquals(0, database.sessions().temporaryRowCount(sessionId.value))

        archive.update(archived.copy(topics = "Pasados y narración"))
        val edited = archive.getBySession(sessionId) ?: error("missing edited diary")
        assertEquals("Pasados y narración", edited.topics)
        assertEquals(1, archive.observeEntries("narracion").first().size)
        val copied = DiaryClipboardFormatter().format(edited)
        assertTrue(copied.contains("Pasados y narración"))
        assertFalse(copied.contains("temporariesDeleted", ignoreCase = true))
    }

    @Test fun `cleanup failure preserves whisper run and checkpoints`() = runTest {
        val sessionId = SessionId("whisper-session")
        val blockId = "whisper-block"
        database.sessions().insertSession(SessionEntity(sessionId.value, "2026-09-13", CerLevel.B1.name, SessionState.AWAITING_REVIEW.name, 1, 1))
        database.sessions().insertBlock(BlockEntity(blockId, sessionId.value, 0, 1, 2, BlockCloseReason.FINALIZED.name))
        val openAudio = files.open(com.capo.diarioclase.data.db.BlockId(blockId), 0)
        files.append(openAudio, shortArrayOf(1, -1, 2, -2), 4)
        val readyAudio = files.close(openAudio)
        database.sessions().saveSegment(AudioSegmentEntity(readyAudio.id.value, blockId, 0, readyAudio.path, File(readyAudio.path).length(), readyAudio.durationMs, readyAudio.sha256, SegmentState.READY.name))
        val draft = DiaryDraftEntity("whisper-draft", sessionId.value, InterpretationMode.CONSERVATIVE.name, "Pasado", "Lectura", "12", "3", "Escribir", 1)
        database.sessions().saveDraft(draft)
        database.sessions().saveTranscriptionRun(TranscriptionRunEntity(sessionId.value, "COMPLETED", false, readyAudio.durationMs, readyAudio.durationMs, null, null, 1))
        database.sessions().saveCheckpoint(TranscriptionCheckpointEntity(readyAudio.id.value, sessionId.value, readyAudio.durationMs, readyAudio.durationMs, 1, 1, "COMPLETED", null, 1))

        val failingFiles = object : CleanupFileStore {
            override suspend fun delete(segmentId: SegmentId) = DeleteResult.Failed("bloqueado")
            override suspend fun exists(segmentId: SegmentId) = true
        }
        val guardedCleanup = CleanupCoordinator(archive, failingFiles, RoomTemporaryCleanupStore(database.sessions()), Clock { 1_000 })

        val outcome = guardedCleanup.approveAndClean(sessionId, draft)

        assertTrue(outcome is CleanupOutcome.Pending)
        assertNotNull(database.sessions().transcriptionRun(sessionId.value))
        assertEquals(1, database.sessions().checkpoints(sessionId.value).size)
        assertTrue(File(readyAudio.path).exists())
        assertTrue(database.sessions().temporaryRowCount(sessionId.value) > 0)
    }

    @Test fun `file backed reopen surfaces pending cleanup without deleting temporaries`() = verifyCleanupRecovery(SessionState.CLEANUP_PENDING)

    @Test fun `file backed reopen surfaces approved boundary without deleting temporaries`() = verifyCleanupRecovery(SessionState.APPROVED)

    private fun verifyCleanupRecovery(recoveredState: SessionState) = runTest {
        val databaseName = "phase3-pending-${UUID.randomUUID()}.db"
        val sessionId = SessionId("pending-session")
        val audioRoot = Files.createTempDirectory("diario-pending-reopen-").toFile()
        val files = FileSegmentStore(audioRoot)
        val first = database(databaseName)
        var firstClosed = false
        try {
            first.sessions().insertSession(SessionEntity(sessionId.value, "2026-09-12", CerLevel.A2.name, recoveredState.name, 1, 1))
            first.sessions().insertBlock(BlockEntity("pending-block", sessionId.value, 0, 1, 2, BlockCloseReason.FINALIZED.name))
            val openAudio = files.open(com.capo.diarioclase.data.db.BlockId("pending-block"), 0)
            files.append(openAudio, shortArrayOf(1, -1), 2)
            val readyAudio = files.close(openAudio)
            assertTrue(File(readyAudio.path).name.endsWith(".ready.wav"))
            first.sessions().saveSegment(AudioSegmentEntity(readyAudio.id.value, "pending-block", 0, readyAudio.path, File(readyAudio.path).length(), readyAudio.durationMs, readyAudio.sha256, SegmentState.READY.name))
            val draft = DiaryDraftEntity("pending-draft", sessionId.value, InterpretationMode.CONSERVATIVE.name, "Narración", "Lectura", "12", "3", "Tarea", 1)
            first.sessions().saveDraft(draft)
            val firstArchive = RoomDiaryRepository(first, Clock { 1_000 }, TestIds())
            assertNotNull(firstArchive.saveVerified(sessionId, draft))
            first.close()
            firstClosed = true

            val reopened = database(databaseName)
            try {
                val repository = RoomSessionRepository(reopened, Clock { 1_000 })
                val reopenedArchive = RoomDiaryRepository(reopened, Clock { 1_000 }, TestIds())
                assertEquals(sessionId.value, reopened.sessions().latestCleanupPendingSession()?.id)
                val pending = repository.observeLatestCleanupPendingRecording().first() ?: error("missing pending recording")
                val permanent = reopenedArchive.getBySession(sessionId) ?: error("missing permanent diary")
                val actions = NoopCaptureActions()
                // Persistence is verified through the reopened Room repositories above. Feed those
                // recovered values directly to the UI boundary so this assertion does not race
                // Room's real executor or keep a database collector alive after close().
                val capture = CaptureViewModel(
                    RecoverySessionRepository(pending),
                    actions,
                    backgroundScope,
                    diaries = flowOf(listOf(permanent)),
                )
                runCurrent()

                assertEquals(recoveredState, pending.state)
                assertEquals(sessionId, pending.sessionId)
                assertEquals(DiaryEntry("diary", sessionId, "2026-09-12", CerLevel.A2, "Narración", "Lectura", "12", "3", "Tarea", 1_000, 1_000, false), permanent)
                assertEquals(CaptureStatus.CLEANUP_PENDING, capture.state.value.status)
                assertEquals("diary", capture.state.value.diaryId)
                assertEquals("Narración", capture.state.value.draft?.topics)
                assertEquals(0, actions.retryCalls)
                assertTrue(File(readyAudio.path).exists())
                assertTrue(files.exists(readyAudio.id))
                assertEquals(2, reopened.sessions().temporaryRowCount(sessionId.value))
            } finally {
                reopened.close()
            }
        } finally {
            if (!firstClosed) first.close()
            context.deleteDatabase(databaseName)
            audioRoot.deleteRecursively()
        }
    }

    @Test fun `phase 5_2 integrity journey`() = runTest {
        val dbName = "phase52-journey-${UUID.randomUUID()}.db"
        val sessionId = SessionId("journey")
        val budget = InterpretationBudget(60_000, 120_000)

        // Respuesta "gold" por paquete (bloque 1, luego bloque 2). La clave de proveedor "C1" se
        // repite entre paquetes para probar que la identidad global evita colisiones (Task I5).
        val goldBlock1 = ProviderClaimsCodec.encode(
            listOf(
                ProviderSemanticClaim("C1", "PAGE", "14", "14", "PERFORMED", 0.96, listOf("B1-S1"), emptyList()),
                ProviderSemanticClaim("C2", "EXERCISE", "3 (p. 14)", "3", "PERFORMED", 0.95, listOf("B1-S1", "B1-S2"), emptyList()),
            ),
        )
        val goldBlock2 = ProviderClaimsCodec.encode(
            listOf(
                ProviderSemanticClaim("C1", "EXERCISE", "4", "4", "ASSIGNED", 0.95, listOf("B2-S1", "B2-S2"), emptyList()),
            ),
        )
        var providerCalls = 0
        val queue = ArrayDeque(listOf(goldBlock1, goldBlock2))
        val client = object : InferenceProviderClient {
            override suspend fun infer(
                request: InterpretationRequest,
                credential: EphemeralCredential,
                attempt: InferenceAttemptContext,
            ): ProviderOutcome {
                providerCalls++
                return ProviderOutcome.Success(InferenceProvider.GEMINI, "gemini-free", queue.removeFirst())
            }
        }
        val interpreter = RouterSemanticInterpreter(
            packetBuilder = InterpretationPacketBuilder(),
            router = FreeInferenceRouter(
                clients = mapOf(InferenceProvider.GEMINI to client),
                validator = SemanticResponseValidator(),
                fallback = FallbackClaimExtractor(),
                retryPolicy = ProviderRetryPolicy(retryDelayMs = 0),
                cache = null,
                credentialFor = { EphemeralCredential("k") },
                onDelay = {},
                nowEpochMs = { 1 },
            ),
            reducer = SemanticClaimReducer(),
            fallback = FallbackClaimExtractor(),
            enabledProviders = { listOf(ProviderModel(InferenceProvider.GEMINI, "gemini-free")) },
            runIdFactory = { "run-journey" },
        )
        val materializer = DiaryFieldMaterializer(InterpretationProjector(), PagesAndExercisesComposer())

        // Dos segmentos de audio con el reloj reiniciado: el bloque 2 empieza en 0 y no debe
        // adelantarse al bloque 1 (cronología de Task I2).
        val spans = listOf(
            TranscriptSpan("t-a1", "segA", com.capo.diarioclase.data.db.BlockId("blk1"), 8_000, 9_000, "Vamos a la página catorce", 0.9),
            TranscriptSpan("t-a2", "segA", com.capo.diarioclase.data.db.BlockId("blk1"), 9_000, 10_000, "Hacemos el ejercicio tres", 0.9),
            TranscriptSpan("t-b1", "segB", com.capo.diarioclase.data.db.BlockId("blk2"), 0, 1_000, "El ejercicio cuatro", 0.9),
            TranscriptSpan("t-b2", "segB", com.capo.diarioclase.data.db.BlockId("blk2"), 1_000, 2_000, "queda para casa", 0.9),
        )

        val firstPages: String
        val firstHomework: String
        val first = journeyDatabase(dbName)
        try {
            seedTranscript(first, sessionId, spans)
            val store = RoomProcessingStore(first, Clock { 5 })
            val claims = interpreter.interpret(sessionId, spans, budget).claims
            val draft = materializer.materialize(sessionId.value, InterpretationMode.CONSERVATIVE, claims)
            store.saveEvidence(sessionId, claims, draft)
            firstPages = draft.pages
            firstHomework = draft.homework
        } finally {
            first.close()
        }

        assertEquals("Página 14: ejercicio 3", firstPages)
        assertEquals("4", firstHomework)
        assertEquals(2, providerCalls) // un paquete por bloque

        val reopened = journeyDatabase(dbName)
        try {
            val store = RoomProcessingStore(reopened, Clock { 6 })
            val persisted = store.persistedClaims(sessionId)
            val assigned = persisted.single { it.category == ClaimCategory.EXERCISE && it.status == ClaimStatus.ASSIGNED }
            assertEquals("4", assigned.value)
            assertEquals(2, assigned.evidences.size) // la evidencia sobrevive al reabrir

            // Cambiar de modo reproyecta localmente: no hay llamadas nuevas a proveedores.
            val callsBeforeModeChange = providerCalls
            val reprojected = LocalDraftReprojector(store, materializer).reproject(sessionId, InterpretationMode.EXHAUSTIVE)
            assertEquals(callsBeforeModeChange, providerCalls)
            assertEquals(InterpretationMode.EXHAUSTIVE, reprojected.mode)
        } finally {
            reopened.close()
            context.deleteDatabase(dbName)
        }
    }

    private suspend fun seedTranscript(db: DiarioDatabase, sessionId: SessionId, spans: List<TranscriptSpan>) {
        val dao = db.sessions()
        dao.insertSession(SessionEntity(sessionId.value, "2026-09-16", CerLevel.B1.name, SessionState.EXTRACTING.name, 1, 1))
        dao.insertBlock(BlockEntity("blk1", sessionId.value, 0, 1, 2, BlockCloseReason.FINALIZED.name))
        dao.insertBlock(BlockEntity("blk2", sessionId.value, 1, 3, 4, BlockCloseReason.FINALIZED.name))
        dao.saveSegment(AudioSegmentEntity("segA", "blk1", 0, "/tmp/segA.wav", 1, 2_000, null, SegmentState.TRANSCRIBED.name))
        dao.saveSegment(AudioSegmentEntity("segB", "blk2", 0, "/tmp/segB.wav", 1, 2_000, null, SegmentState.TRANSCRIBED.name))
        dao.insertTranscript(
            spans.map { TranscriptSpanEntity(it.id, it.audioSegmentId, it.blockId.value, it.startMs, it.endMs, it.text, it.confidence) },
        )
    }

    private fun journeyDatabase(name: String) = Room.databaseBuilder(context, DiarioDatabase::class.java, name)
        .allowMainThreadQueries()
        .build()

    private fun database(name: String) = Room.databaseBuilder(context, DiarioDatabase::class.java, name)
        .addMigrations(DiarioDatabase.MIGRATION_1_2, DiarioDatabase.MIGRATION_2_3)
        .allowMainThreadQueries()
        .build()

    private class TestIds : IdProvider {
        override fun next() = "diary"
    }

    private class NoopCaptureActions : CaptureActions {
        var retryCalls = 0
        override suspend fun startNewDay() = Unit
        override suspend fun resume(id: SessionId) = Unit
        override suspend fun pause() = Unit
        override suspend fun markHomework(sessionId: SessionId, blockId: com.capo.diarioclase.data.db.BlockId) = Unit
        override suspend fun finalizeDay(sessionId: SessionId, state: SessionState) = Unit
        override suspend fun retryCleanup(sessionId: SessionId): CleanupOutcome {
            retryCalls += 1
            error("retry must be user initiated")
        }
    }

    private class RecoverySessionRepository(
        private val pending: com.capo.diarioclase.data.db.RecordingReport,
    ) : SessionRepository {
        override fun observeActiveSession(): Flow<com.capo.diarioclase.data.db.SessionAggregate?> = flowOf(null)
        override fun observeLatestFinalizedRecording(): Flow<com.capo.diarioclase.data.db.RecordingReport?> = flowOf(null)
        override fun observeLatestCleanupPendingRecording(): Flow<com.capo.diarioclase.data.db.RecordingReport?> = flowOf(pending)
        override suspend fun createSession(level: CerLevel?) = error("not used")
        override suspend fun startBlock(sessionId: SessionId) = error("not used")
        override suspend fun closeBlock(blockId: com.capo.diarioclase.data.db.BlockId, reason: BlockCloseReason) = error("not used")
        override suspend fun finalizeSession(sessionId: SessionId) = error("not used")
        override suspend fun updateSessionState(sessionId: SessionId, state: SessionState) = error("not used")
    }
}
