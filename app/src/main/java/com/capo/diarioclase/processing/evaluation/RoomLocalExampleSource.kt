package com.capo.diarioclase.processing.evaluation

import android.content.Context
import com.capo.diarioclase.data.db.SessionId
import com.capo.diarioclase.processing.semantic.SemanticQualityGate
import com.capo.diarioclase.processing.work.InterpretationRunVersions
import com.capo.diarioclase.processing.work.RoomProcessingStore
import java.io.File

/**
 * Fuente del corpus respaldada en Room (Fase 6, Q7). Resuelve spans, claims persistidos,
 * revisiones y campos finales desde [RoomProcessingStore]. No expone audio ni id de sesión al
 * ejemplo: el repositorio anonimiza.
 */
class RoomLocalExampleSource(
    private val store: RoomProcessingStore,
    private val versions: InterpretationRunVersions = InterpretationRunVersions(),
) : LocalExampleSource {

    override suspend fun orderedSpans(sessionId: String): List<ExampleSourceSpan> =
        store.transcript(SessionId(sessionId)).map { ExampleSourceSpan(it.id, it.text) }

    override suspend fun claims(sessionId: String) = store.persistedClaims(SessionId(sessionId))

    override suspend fun revisions(sessionId: String) = store.sessionRevisions(sessionId)

    override suspend fun finalFields(sessionId: String): Map<String, String> =
        store.draft(SessionId(sessionId))?.let {
            mapOf(
                "TOPICS" to it.topics,
                "ACTIVITIES" to it.activities,
                "PAGES" to it.pages,
                "EXERCISES" to it.exercises,
                "HOMEWORK" to it.homework,
            )
        } ?: emptyMap()

    override fun pipelineVersions(): PipelineVersions = PipelineVersions(
        appVersion = versions.appVersion,
        whisperVersion = versions.whisperVersion,
        promptVersion = versions.promptVersion,
        schemaVersion = versions.schemaVersion,
        validatorVersion = versions.validatorVersion,
        scoreVersion = SemanticQualityGate.SCORE_VERSION,
    )
}

/**
 * Almacenamiento del corpus en un archivo privado sin backup (Fase 6, Q7). Vive en
 * `noBackupFilesDir`, así queda fuera del backup de Android y se borra con delete-all.
 */
class NoBackupExampleStorage(context: Context) : LocalExampleStorage {
    private val file = File(context.applicationContext.noBackupFilesDir, "local-eval-corpus.jsonl")

    override fun readAll(): String = if (file.exists()) file.readText(Charsets.UTF_8) else ""

    override fun writeAll(content: String) {
        file.writeText(content, Charsets.UTF_8)
    }

    override fun deleteAll() {
        if (file.exists()) file.delete()
    }
}
