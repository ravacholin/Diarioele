package com.capo.diarioclase

import android.app.Application
import androidx.room.Room
import com.capo.diarioclase.core.clock.SystemClock
import com.capo.diarioclase.data.db.DiarioDatabase
import com.capo.diarioclase.data.repository.RoomDiaryRepository
import com.capo.diarioclase.data.repository.RoomMarkerStore
import com.capo.diarioclase.data.repository.RoomSegmentMetadataStore
import com.capo.diarioclase.data.repository.RoomSessionRepository
import com.capo.diarioclase.diary.cleanup.CleanupCoordinator
import com.capo.diarioclase.diary.cleanup.RoomTemporaryCleanupStore
import com.capo.diarioclase.processing.evidence.ClaimReducer
import com.capo.diarioclase.processing.evidence.InterpretationProjector
import com.capo.diarioclase.processing.evidence.LiteralClaimExtractor
import com.capo.diarioclase.processing.transcription.AndroidOnDeviceTranscriptionEngine
import com.capo.diarioclase.processing.transcription.WhisperModelInstaller
import com.capo.diarioclase.processing.transcription.WhisperNativeBridge
import com.capo.diarioclase.processing.transcription.WhisperTranscriptionEngine
import com.capo.diarioclase.processing.work.RoomProcessingStore
import com.capo.diarioclase.processing.work.TranscriptionCoordinator
import com.capo.diarioclase.recording.audio.CleanupFileStore
import com.capo.diarioclase.recording.audio.FileSegmentStore
import com.capo.diarioclase.recording.audio.PersistingSegmentStore
import com.capo.diarioclase.recording.recovery.RecordingRecovery
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DiarioClaseApp : Application() {
    lateinit var database: DiarioDatabase
    lateinit var repository: RoomSessionRepository
    lateinit var diaryRepository: RoomDiaryRepository
    lateinit var recovery: RecordingRecovery
    lateinit var processingStore: RoomProcessingStore
    lateinit var transcriptionCoordinator: TranscriptionCoordinator
    lateinit var transcriptionEngine: AndroidOnDeviceTranscriptionEngine
    lateinit var whisperEngine: WhisperTranscriptionEngine
    lateinit var cleanupFiles: CleanupFileStore
    lateinit var cleanupCoordinator: CleanupCoordinator

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        database = Room.databaseBuilder(this, DiarioDatabase::class.java, "diario.db")
            .addMigrations(
                DiarioDatabase.MIGRATION_1_2,
                DiarioDatabase.MIGRATION_2_3,
                DiarioDatabase.MIGRATION_3_4,
            )
            .build()
        repository = RoomSessionRepository(database, SystemClock)
        diaryRepository = RoomDiaryRepository(database, SystemClock)
        val rawSegments = FileSegmentStore(File(filesDir, "temporary_audio"))
        cleanupFiles = rawSegments
        cleanupCoordinator = CleanupCoordinator(
            diaryRepository,
            cleanupFiles,
            RoomTemporaryCleanupStore(database.sessions()),
            SystemClock,
        )
        val segments = PersistingSegmentStore(
            rawSegments,
            RoomSegmentMetadataStore(database.sessions()),
        )
        recovery = RecordingRecovery(
            repository,
            segments,
            RoomMarkerStore(database.sessions()),
        )
        processingStore = RoomProcessingStore(database, SystemClock)
        transcriptionEngine = AndroidOnDeviceTranscriptionEngine(this)
        whisperEngine = WhisperTranscriptionEngine(
            modelProvider = WhisperModelInstaller(this),
            nativeRuntime = WhisperNativeBridge(),
        )
        transcriptionCoordinator = TranscriptionCoordinator(processingStore, whisperEngine)
        appScope.launch {
            database.sessions().recoverInterruptedTranscriptions()
            recovery.onAppStart()
            database.sessions().latestCleanupPendingSession()
        }
    }
}
