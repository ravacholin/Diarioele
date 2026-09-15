package com.capo.diarioclase

import android.app.Application
import androidx.room.Room
import androidx.work.WorkManager
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
import com.capo.diarioclase.processing.semantic.DefaultInferenceHttpTransport
import com.capo.diarioclase.processing.semantic.EphemeralCredential
import com.capo.diarioclase.processing.semantic.FallbackClaimExtractor
import com.capo.diarioclase.processing.semantic.FreeInferenceRouter
import com.capo.diarioclase.processing.semantic.GeminiProviderClient
import com.capo.diarioclase.processing.semantic.InferenceProvider
import com.capo.diarioclase.processing.semantic.InterpretationPacketBuilder
import com.capo.diarioclase.processing.semantic.InterpretationPromptFactory
import com.capo.diarioclase.processing.semantic.KeystoreProviderCredentialStore
import com.capo.diarioclase.processing.semantic.OpenAiCompatibleProfile
import com.capo.diarioclase.processing.semantic.OpenAiCompatibleProviderClient
import com.capo.diarioclase.processing.semantic.ProviderCredentialStore
import com.capo.diarioclase.processing.semantic.ProviderModel
import com.capo.diarioclase.processing.semantic.ProviderSettingsController
import com.capo.diarioclase.processing.semantic.ProviderSettingsStore
import com.capo.diarioclase.processing.semantic.RealProviderConnectionTester
import com.capo.diarioclase.processing.semantic.RoomInterpretationCache
import com.capo.diarioclase.processing.semantic.RouterSemanticInterpreter
import com.capo.diarioclase.processing.semantic.SemanticClaimReducer
import com.capo.diarioclase.processing.semantic.SemanticResponseValidator
import com.capo.diarioclase.processing.transcription.WhisperModelInstaller
import com.capo.diarioclase.processing.transcription.WhisperNativeBridge
import com.capo.diarioclase.processing.transcription.WhisperTranscriptionEngine
import com.capo.diarioclase.processing.work.RoomProcessingStore
import com.capo.diarioclase.processing.work.TranscriptionCoordinator
import com.capo.diarioclase.processing.work.DaoTranscriptionRunCommands
import com.capo.diarioclase.processing.work.TranscriptionWorkScheduler
import com.capo.diarioclase.processing.work.WorkManagerEnqueuer
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
    val transcriptionScheduler: TranscriptionWorkScheduler by lazy {
        TranscriptionWorkScheduler(
            commands = DaoTranscriptionRunCommands(database.sessions()),
            work = WorkManagerEnqueuer(WorkManager.getInstance(this)),
        )
    }
    lateinit var whisperEngine: WhisperTranscriptionEngine
    lateinit var cleanupFiles: CleanupFileStore
    lateinit var cleanupCoordinator: CleanupCoordinator
    lateinit var providerSettings: ProviderSettingsStore
    lateinit var providerCredentials: ProviderCredentialStore
    lateinit var providerSettingsController: ProviderSettingsController

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        database = Room.databaseBuilder(this, DiarioDatabase::class.java, "diario.db")
            .addMigrations(
                DiarioDatabase.MIGRATION_1_2,
                DiarioDatabase.MIGRATION_2_3,
                DiarioDatabase.MIGRATION_3_4,
                DiarioDatabase.MIGRATION_4_5,
                DiarioDatabase.MIGRATION_5_6,
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
        whisperEngine = WhisperTranscriptionEngine(
            modelProvider = WhisperModelInstaller(this),
            nativeRuntime = WhisperNativeBridge(),
        )
        providerSettings = ProviderSettingsStore(this)
        providerCredentials = KeystoreProviderCredentialStore(this)
        val transport = DefaultInferenceHttpTransport()
        val promptFactory = InterpretationPromptFactory()
        val clients = mapOf(
            InferenceProvider.GEMINI to GeminiProviderClient(transport, promptFactory),
            InferenceProvider.GROQ to OpenAiCompatibleProviderClient(OpenAiCompatibleProfile.GROQ, transport, promptFactory),
            InferenceProvider.OPENROUTER to OpenAiCompatibleProviderClient(OpenAiCompatibleProfile.OPENROUTER, transport, promptFactory),
        )
        val credentials = providerCredentials
        val router = FreeInferenceRouter(
            clients = clients,
            validator = SemanticResponseValidator(),
            fallback = FallbackClaimExtractor(),
            cache = RoomInterpretationCache(database.sessions()),
            credentialFor = { provider ->
                credentials.readCredential(provider)?.let { chars ->
                    val value = String(chars)
                    chars.fill(Char(0))
                    EphemeralCredential(value)
                }
            },
        )
        val settings = providerSettings
        val interpreter = RouterSemanticInterpreter(
            packetBuilder = InterpretationPacketBuilder(),
            router = router,
            reducer = SemanticClaimReducer(),
            enabledProviders = {
                settings.enabledProfilesInOrder().map { ProviderModel(it.provider, it.modelId) }
            },
        )
        transcriptionCoordinator = TranscriptionCoordinator(processingStore, whisperEngine, interpreter = interpreter)
        providerSettingsController = ProviderSettingsController(
            settings = providerSettings,
            credentials = providerCredentials,
            connectionTester = RealProviderConnectionTester(clients, providerCredentials),
        )
        appScope.launch {
            database.sessions().recoverInterruptedTranscriptions()
            recovery.onAppStart()
            database.sessions().latestCleanupPendingSession()
        }
    }
}
