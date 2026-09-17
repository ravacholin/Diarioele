package com.capo.diarioclase.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val pedagogicalDate: String,
    val level: String?,
    val state: String,
    val startedAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "blocks",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sessionId")],
)
data class BlockEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val ordinal: Int,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long?,
    val closeReason: String?,
)

@Entity(
    tableName = "audio_segments",
    foreignKeys = [ForeignKey(
        entity = BlockEntity::class,
        parentColumns = ["id"],
        childColumns = ["blockId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("blockId"), Index(value = ["path"], unique = true)],
)
data class AudioSegmentEntity(
    @PrimaryKey val id: String,
    val blockId: String,
    val ordinal: Int,
    val path: String,
    val byteCount: Long,
    val durationMs: Long,
    val sha256: String?,
    val state: String,
    val transcriptionAttempts: Int = 0,
    val lastTranscriptionFailure: String? = null,
)

@Entity(
    tableName = "markers",
    foreignKeys = [ForeignKey(
        entity = BlockEntity::class,
        parentColumns = ["id"],
        childColumns = ["blockId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sessionId"), Index("blockId")],
)
data class MarkerEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val blockId: String,
    val absoluteEpochMs: Long,
    val offsetMs: Long,
    val type: String,
    val note: String?,
)

@Entity(
    tableName = "transcript_spans",
    foreignKeys = [ForeignKey(
        entity = AudioSegmentEntity::class,
        parentColumns = ["id"],
        childColumns = ["audioSegmentId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("audioSegmentId"), Index("blockId")],
)
data class TranscriptSpanEntity(
    @PrimaryKey val id: String,
    val audioSegmentId: String,
    val blockId: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val confidence: Double,
)

@Entity(
    tableName = "evidence_claims",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sessionId"), Index(value = ["sessionId", "category"])],
)
data class EvidenceClaimEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val category: String,
    val value: String,
    val normalizedValue: String,
    val status: String,
    val confidence: Double,
    val origin: String,
    val blockId: String,
    val startMs: Long,
    val endMs: Long,
    val excerpt: String,
    val active: Boolean,
    // Identidad y calidad namespaced de Fase 5.2 (aditivas, con default para no romper
    // filas v5 ya existentes ni las llamadas posicionales del store local).
    val runId: String? = null,
    val packetId: String? = null,
    val providerClaimKey: String = "",
    val declaredConfidence: Double = 0.0,
    val effectiveConfidence: Double = 0.0,
    val claimOrdinal: Int = 0,
    // Justificación breve del claim (Nivel 3). Aditiva, con default para no romper filas legacy.
    val reason: String? = null,
)

@Entity(
    tableName = "diary_drafts",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sessionId", unique = true)],
)
data class DiaryDraftEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val mode: String,
    val topics: String,
    val activities: String,
    val pages: String,
    val exercises: String,
    val homework: String,
    val updatedAtEpochMs: Long,
    val userEdited: Boolean = false,
    // Máscara de edición por campo (Fase 6, Q4). `userEdited` se conserva como bandera global
    // legacy; la máscara permite proteger solo el campo que el docente tocó.
    val editedTopics: Boolean = false,
    val editedActivities: Boolean = false,
    val editedPages: Boolean = false,
    val editedExercises: Boolean = false,
    val editedHomework: Boolean = false,
    // Resumen de la clase generado por IA (Nivel 3). Aditiva, con default para filas legacy.
    val summary: String = "",
)

/** Acción de revisión del docente sobre un claim (Fase 6, Q4). */
enum class ReviewAction { ACCEPT, REJECT, CORRECT }

/** Revisión de campo/claim en el dominio (Fase 6, Q4). */
data class DraftFieldRevision(
    val id: String,
    val sessionId: String,
    val field: String,
    val beforeValue: String,
    val afterValue: String,
    val actor: String,
    val action: ReviewAction,
    val claimId: String?,
    val createdAtEpochMs: Long,
)

/**
 * Historial normalizado de revisiones de campos y claims (Fase 6, Q4). Registra cada edición
 * o decisión (aceptar/rechazar/corregir) antes de actualizar la ficha, con el valor previo y
 * el nuevo, para poder auditar y evaluar prompts sin volver a la red.
 */
@Entity(
    tableName = "draft_field_revisions",
    indices = [Index("sessionId"), Index("claimId")],
)
data class DraftFieldRevisionEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val field: String,
    val beforeValue: String,
    val afterValue: String,
    val actor: String,
    val action: String,
    val claimId: String?,
    val createdAtEpochMs: Long,
)

@Entity(tableName = "diary_entries", indices = [Index(value = ["sessionId"], unique = true)])
data class DiaryEntryEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val pedagogicalDate: String,
    val level: String?,
    val topics: String,
    val activities: String,
    val pages: String,
    val completedExercises: String,
    val homework: String,
    val approvedAtEpochMs: Long,
    val updatedAtEpochMs: Long,
    val temporariesDeleted: Boolean,
)

@Entity(tableName = "app_settings")
data class AppSettingEntity(@PrimaryKey val key: String, val value: String)

@Entity(
    tableName = "transcription_runs",
    foreignKeys = [ForeignKey(
        entity = SessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class TranscriptionRunEntity(
    @PrimaryKey val sessionId: String,
    val state: String,
    val pauseRequested: Boolean,
    val processedMs: Long,
    val totalMs: Long,
    val currentSegmentId: String?,
    val failure: String?,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "transcription_checkpoints",
    primaryKeys = ["audioSegmentId"],
    foreignKeys = [ForeignKey(
        entity = AudioSegmentEntity::class,
        parentColumns = ["id"],
        childColumns = ["audioSegmentId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sessionId")],
)
data class TranscriptionCheckpointEntity(
    val audioSegmentId: String,
    val sessionId: String,
    val confirmedUntilMs: Long,
    val totalMs: Long,
    val processedWindows: Int,
    val totalWindows: Int,
    val state: String,
    val failure: String?,
    val updatedAtEpochMs: Long,
)

data class SessionWithBlocks(
    @Embedded val session: SessionEntity,
    @Relation(parentColumn = "id", entityColumn = "sessionId") val blocks: List<BlockEntity>,
)

/**
 * Caché de respuestas de inferencia validadas (Task 6), acotada por sesión.
 *
 * `cacheId` es SHA-256 de sesión, paquete, proveedor, modelo, versiones de prompt/esquema
 * y versión del validador. Guarda el JSON validado para reutilizarlo sin volver a llamar a
 * un proveedor. Es temporal: se borra junto con el resto de datos de la sesión al aprobar.
 */
@Entity(
    tableName = "interpretation_cache",
    indices = [Index("sessionId"), Index("packetId")],
)
data class InterpretationCacheEntity(
    @PrimaryKey val cacheId: String,
    val packetId: String,
    val sessionId: String,
    val provider: String,
    val modelId: String,
    val promptVersion: String,
    val schemaVersion: String,
    val validatedJson: String,
    val createdAtEpochMs: Long,
)

/**
 * Corrida de interpretación semántica (Task I4). Registra identidad, versiones congeladas
 * (app, whisper, prompt, esquema y validador) y el hash del transcripto sobre el que se
 * infirió. `provenance` guarda el resumen tipado del router, nunca cuerpos HTTP ni claves.
 */
@Entity(tableName = "interpretation_runs", indices = [Index("sessionId")])
data class InterpretationRunEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val state: String,
    val appVersion: String,
    val whisperVersion: String,
    val promptVersion: String,
    val schemaVersion: String,
    val validatorVersion: String,
    val transcriptHash: String,
    val mode: String,
    val provenance: String?,
    val failure: String?,
    val startedAtEpochMs: Long,
    val completedAtEpochMs: Long?,
)

/**
 * Paquete de una corrida. La clave compuesta `runId+packetId` mantiene los paquetes de una
 * misma corrida sin colisionar entre corridas. Solo persiste el hash y el tamaño del
 * request, nunca el texto enviado al proveedor.
 */
@Entity(
    tableName = "interpretation_packets",
    primaryKeys = ["runId", "packetId"],
    indices = [Index("runId")],
)
data class InterpretationPacketEntity(
    val runId: String,
    val packetId: String,
    val ordinal: Int,
    val state: String,
    val requestHash: String,
    val requestBytes: Int,
    val provider: String?,
    val startedAtEpochMs: Long?,
    val completedAtEpochMs: Long?,
)

/**
 * Intento contra un proveedor para un paquete. Guarda solo el resultado tipado (`outcome`),
 * el modelo, si hubo acierto de caché y la duración. Nunca cuerpos, encabezados ni claves.
 */
@Entity(tableName = "provider_attempts", indices = [Index(value = ["runId", "packetId"])])
data class ProviderAttemptEntity(
    @PrimaryKey val id: String,
    val runId: String,
    val packetId: String,
    val provider: String,
    val modelId: String,
    val attempt: Int,
    val cacheHit: Boolean,
    val outcome: String,
    val durationMs: Long,
    val startedAtEpochMs: Long,
)

/**
 * Evidencia de un claim resuelta a un span de transcripción local real. `ordinal` conserva
 * el orden de la evidencia y `contextual` marca los spans que solo aportan contexto.
 */
@Entity(primaryKeys = ["claimId", "transcriptSpanId"], tableName = "claim_evidence")
data class ClaimEvidenceEntity(
    val claimId: String,
    val transcriptSpanId: String,
    val ordinal: Int,
    val contextual: Boolean,
)

/** Supersesión: `newClaimId` reemplaza a `oldClaimId` dentro de la misma corrida. */
@Entity(primaryKeys = ["newClaimId", "oldClaimId"], tableName = "claim_supersessions")
data class ClaimSupersessionEntity(
    val newClaimId: String,
    val oldClaimId: String,
)
