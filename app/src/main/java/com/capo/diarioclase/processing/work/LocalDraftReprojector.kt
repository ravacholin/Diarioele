package com.capo.diarioclase.processing.work

import com.capo.diarioclase.data.db.SessionId
import com.capo.diarioclase.processing.evidence.DiaryDraft
import com.capo.diarioclase.processing.evidence.DiaryFieldMaterializer
import com.capo.diarioclase.processing.evidence.EvidenceClaim
import com.capo.diarioclase.processing.evidence.InterpretationMode
import com.capo.diarioclase.processing.evidence.InterpretationProjector
import com.capo.diarioclase.processing.evidence.PagesAndExercisesComposer

/**
 * Almacén mínimo que necesita la reproyección local (Task I7): los claims ya persistidos de la
 * sesión y el guardado de la ficha respetando las ediciones del docente.
 */
interface LocalReprojectionStore {
    suspend fun persistedClaims(sessionId: SessionId): List<EvidenceClaim>
    suspend fun mergeFieldEditsAndSave(draft: DiaryDraft): DiaryDraft
}

/**
 * Reproyecta la ficha localmente a partir de los claims ya persistidos (Task I7).
 *
 * Cambiar de modo no vuelve a llamar a la red ni reprograma trabajo: recalcula la proyección
 * con los umbrales del nuevo modo sobre los claims guardados y guarda la ficha, conservando las
 * ediciones del docente. Es el reemplazo de `resumeProcessing` para el selector de modo.
 */
class LocalDraftReprojector(
    private val store: LocalReprojectionStore,
    private val materializer: DiaryFieldMaterializer = DiaryFieldMaterializer(
        InterpretationProjector(),
        PagesAndExercisesComposer(),
    ),
) {
    suspend fun reproject(sessionId: SessionId, mode: InterpretationMode): DiaryDraft {
        val claims = store.persistedClaims(sessionId)
        val generated = materializer.materialize(sessionId.value, mode, claims)
        return store.mergeFieldEditsAndSave(generated)
    }
}
