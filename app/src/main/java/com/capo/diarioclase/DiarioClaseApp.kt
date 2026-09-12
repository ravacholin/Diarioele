package com.capo.diarioclase
import android.app.Application
import androidx.room.Room
import com.capo.diarioclase.core.clock.SystemClock
import com.capo.diarioclase.data.db.DiarioDatabase
import com.capo.diarioclase.data.repository.*
import com.capo.diarioclase.diary.cleanup.CleanupCoordinator
import com.capo.diarioclase.diary.cleanup.RoomTemporaryCleanupStore
import com.capo.diarioclase.recording.audio.*
import com.capo.diarioclase.recording.recovery.RecordingRecovery
import com.capo.diarioclase.processing.evidence.*
import com.capo.diarioclase.processing.transcription.AndroidOnDeviceTranscriptionEngine
import com.capo.diarioclase.processing.work.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
class DiarioClaseApp:Application(){lateinit var database:DiarioDatabase;lateinit var repository:RoomSessionRepository;lateinit var diaryRepository:RoomDiaryRepository;lateinit var recovery:RecordingRecovery;lateinit var processingStore:RoomProcessingStore;lateinit var transcriptionCoordinator:TranscriptionCoordinator;lateinit var transcriptionEngine:AndroidOnDeviceTranscriptionEngine;lateinit var cleanupFiles:CleanupFileStore;lateinit var cleanupCoordinator:CleanupCoordinator
 private val appScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
 override fun onCreate(){super.onCreate();database=Room.databaseBuilder(this,DiarioDatabase::class.java,"diario.db").addMigrations(DiarioDatabase.MIGRATION_1_2,DiarioDatabase.MIGRATION_2_3).build();repository=RoomSessionRepository(database,SystemClock);diaryRepository=RoomDiaryRepository(database,SystemClock);val rawSegments=FileSegmentStore(File(filesDir,"temporary_audio"));cleanupFiles=rawSegments;cleanupCoordinator=CleanupCoordinator(diaryRepository,cleanupFiles,RoomTemporaryCleanupStore(database.sessions()),SystemClock);val segments=PersistingSegmentStore(rawSegments,RoomSegmentMetadataStore(database.sessions()));recovery=RecordingRecovery(repository,segments,RoomMarkerStore(database.sessions()));processingStore=RoomProcessingStore(database,SystemClock);transcriptionEngine=AndroidOnDeviceTranscriptionEngine(this);transcriptionCoordinator=TranscriptionCoordinator(processingStore,transcriptionEngine);appScope.launch{database.sessions().recoverInterruptedTranscriptions();recovery.onAppStart();database.sessions().latestCleanupPendingSession()}}
}
