package com.capo.diarioclase.processing.evidence

import com.capo.diarioclase.processing.semantic.DiaryField
import com.capo.diarioclase.processing.semantic.FieldTarget
import com.capo.diarioclase.processing.semantic.StatusFieldPolicy

/** Builds the five-field draft from one status-aware projection. */
class DiaryFieldMaterializer(
    private val projector: InterpretationProjector,
    private val composer: PagesAndExercisesComposer,
) {
    fun materialize(
        sessionId: String,
        mode: InterpretationMode,
        claims: List<EvidenceClaim>,
        summary: String = "",
    ): DiaryDraft {
        val presentation = projector.project(claims, mode)
        val acceptedByField = presentation.accepted.groupBy { claim ->
            val target = StatusFieldPolicy.target(claim.category, claim.status)
            require(target is FieldTarget.Field) {
                "Only field-targeted claims can be accepted: ${claim.id}."
            }
            target.field
        }
        return DiaryDraft(
            sessionId = sessionId,
            mode = mode,
            topics = values(acceptedByField[DiaryField.TOPICS]),
            activities = values(acceptedByField[DiaryField.ACTIVITIES]),
            pages = composer.compose(presentation.accepted),
            exercises = "",
            homework = values(acceptedByField[DiaryField.HOMEWORK]),
            accepted = presentation.accepted,
            confirm = presentation.confirm,
            summary = summary,
        )
    }

    private fun values(claims: List<EvidenceClaim>?): String =
        claims.orEmpty().joinToString("\n") { it.value }.trim()
}
