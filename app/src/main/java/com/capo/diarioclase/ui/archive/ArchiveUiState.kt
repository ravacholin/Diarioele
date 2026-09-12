package com.capo.diarioclase.ui.archive

import com.capo.diarioclase.data.db.CerLevel
import com.capo.diarioclase.diary.DiaryEntry
import com.capo.diarioclase.processing.evidence.InterpretationMode

data class ArchiveFields(
    val pedagogicalDate: String,
    val level: CerLevel?,
    val topics: String,
    val activities: String,
    val pages: String,
    val completedExercises: String,
    val homework: String,
) {
    companion object {
        fun from(entry: DiaryEntry) = ArchiveFields(
            entry.pedagogicalDate, entry.level, entry.topics, entry.activities,
            entry.pages, entry.completedExercises, entry.homework,
        )
    }
}

data class DeleteConfirmation(val id: String, val pedagogicalDate: String)

data class ArchiveUiState(
    val query: String = "",
    val entries: List<DiaryEntry> = emptyList(),
    val selectedDiary: DiaryEntry? = null,
    val editableFields: ArchiveFields? = null,
    val deleteConfirmation: DeleteConfirmation? = null,
    val mode: InterpretationMode = InterpretationMode.CONSERVATIVE,
    val message: String? = null,
    val busy: Boolean = false,
    val searching: Boolean = true,
)
