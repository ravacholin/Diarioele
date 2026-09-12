package com.capo.diarioclase.ui.archive

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.capo.diarioclase.diary.DiaryClipboardFormatter
import com.capo.diarioclase.diary.DiaryEntry
import com.capo.diarioclase.diary.DiaryRepository
import com.capo.diarioclase.processing.evidence.InterpretationMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class ArchiveViewModel(
    private val repository: DiaryRepository,
    private val formatter: DiaryClipboardFormatter = DiaryClipboardFormatter(),
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val workScope = scope ?: viewModelScope
    private val _state = MutableStateFlow(ArchiveUiState())
    val state: StateFlow<ArchiveUiState> = _state.asStateFlow()
    private val query = MutableStateFlow("")
    private var allEntries: List<DiaryEntry> = emptyList()
    private var pendingDiaryId: String? = null

    init {
        // Selection is resolved independently of search so filtering cannot retarget a detail or deletion.
        workScope.launch {
            repository.observeEntries("").catch { error ->
                if (error is CancellationException) throw error
                _state.update { it.copy(message = "No se pudieron leer los diarios") }
            }.collect { entries ->
                allEntries = entries
                _state.update { current ->
                    val selected = entries.find { it.id == (pendingDiaryId ?: current.selectedDiary?.id) }
                    if (selected?.id == pendingDiaryId) pendingDiaryId = null
                    current.copy(
                        selectedDiary = selected,
                        editableFields = current.editableFields.takeIf { selected != null },
                        deleteConfirmation = current.deleteConfirmation.takeIf { selected != null },
                    )
                }
            }
        }
        workScope.launch {
            query.flatMapLatest { requested ->
                repository.observeEntries(requested).map { requested to it }.catch { error ->
                    if (error is CancellationException) throw error
                    _state.update { it.copy(searching = false, message = "No se pudo realizar la búsqueda") }
                }
            }.collect { (requested, entries) ->
                _state.update { if (it.query == requested) it.copy(entries = entries, searching = false) else it }
            }
        }
        workScope.launch {
            repository.observeMode().catch { error ->
                if (error is CancellationException) throw error
                _state.update { it.copy(message = "No se pudo leer la configuración") }
            }.collect { mode -> _state.update { it.copy(mode = mode) } }
        }
    }

    fun onQuery(value: String) {
        if (query.value == value) return
        _state.update { it.copy(query = value, searching = true) }
        query.value = value
    }

    fun selectDiary(id: String) {
        if (state.value.busy) return
        val entry = allEntries.find { it.id == id } ?: state.value.entries.find { it.id == id } ?: return
        pendingDiaryId = null
        _state.update { it.copy(selectedDiary = entry, editableFields = null, deleteConfirmation = null, message = null) }
    }

    /** Approval may finish before Room's list flow emits the new row. Keep the exact target. */
    fun openSavedDiary(id: String) {
        pendingDiaryId = id
        val entry = allEntries.find { it.id == id }
        if (entry != null) pendingDiaryId = null
        _state.update { it.copy(selectedDiary = entry, editableFields = null, deleteConfirmation = null, message = null) }
    }

    fun closeDiary() {
        if (state.value.busy) return
        pendingDiaryId = null
        _state.update { it.copy(selectedDiary = null, editableFields = null, deleteConfirmation = null, message = null) }
    }

    fun startEditing() {
        val current = state.value
        val selected = current.selectedDiary ?: return
        if (current.busy || current.deleteConfirmation != null) return
        _state.update { it.copy(editableFields = ArchiveFields.from(selected), message = null) }
    }

    fun onEdit(fields: ArchiveFields) {
        if (state.value.busy || state.value.editableFields == null) return
        _state.update { it.copy(editableFields = fields) }
    }

    fun cancelEdit() {
        if (!state.value.busy) _state.update { it.copy(editableFields = null, message = null) }
    }

    fun saveEdit() {
        val selected = state.value.selectedDiary ?: return
        val fields = state.value.editableFields ?: return
        mutate("No se pudieron guardar los cambios") {
            require(runCatching { LocalDate.parse(fields.pedagogicalDate) }.isSuccess) { "Usá una fecha válida: AAAA-MM-DD" }
            // Reread the permanent record: never overwrite cleanup flags/timestamps from a stale editor.
            val latest = repository.getBySession(selected.sessionId)
            check(latest?.id == selected.id) { "Este diario ya no está disponible" }
            repository.update(latest!!.copy(
                pedagogicalDate = fields.pedagogicalDate, level = fields.level,
                topics = fields.topics, activities = fields.activities, pages = fields.pages,
                completedExercises = fields.completedExercises, homework = fields.homework,
            ))
            _state.update { it.copy(editableFields = null, message = "Cambios guardados") }
        }
    }

    fun copySelected(): String? {
        val selected = state.value.selectedDiary ?: return null
        return try {
            formatter.format(selected)
        } catch (_: Exception) {
            _state.update { it.copy(message = "No se pudo copiar el diario") }
            null
        }
    }

    fun onCopied() { _state.update { it.copy(message = "Diario copiado") } }

    fun requestDelete() {
        val current = state.value
        val selected = current.selectedDiary ?: return
        if (current.busy || current.editableFields != null) return
        _state.update { it.copy(deleteConfirmation = DeleteConfirmation(selected.id, selected.pedagogicalDate)) }
    }

    fun cancelDelete() {
        if (!state.value.busy) _state.update { it.copy(deleteConfirmation = null) }
    }

    fun confirmDelete() {
        val target = state.value.deleteConfirmation ?: return
        if (state.value.selectedDiary?.id != target.id) return
        mutate("No se pudo eliminar el diario", onFailure = { it.copy(deleteConfirmation = null) }) {
            repository.delete(target.id)
            _state.update { it.copy(selectedDiary = null, editableFields = null, deleteConfirmation = null, message = "Diario eliminado") }
        }
    }

    fun setMode(mode: InterpretationMode) = mutate("No se pudo guardar la configuración") {
        repository.setMode(mode)
        _state.update { it.copy(message = "Configuración guardada") }
    }

    private fun mutate(
        failureMessage: String,
        onFailure: (ArchiveUiState) -> ArchiveUiState = { it },
        action: suspend () -> Unit,
    ) {
        if (state.value.busy) return
        _state.update { it.copy(busy = true, message = null) }
        workScope.launch {
            try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { onFailure(it).copy(message = error.message ?: failureMessage) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}
