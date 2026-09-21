package com.capo.diarioclase.ui.archive

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.capo.diarioclase.core.clock.Clock
import com.capo.diarioclase.data.db.*
import com.capo.diarioclase.data.repository.IdProvider
import com.capo.diarioclase.data.repository.RoomDiaryRepository
import com.capo.diarioclase.diary.DiaryEntry
import com.capo.diarioclase.diary.DiarySaveResult
import com.capo.diarioclase.processing.evidence.InterpretationMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ArchiveViewModelTest {
    @Test fun `editorial diary search copy and edit preserve audited prose`() = runTest {
        val entry = seed("editorial", editorial = true)
        val vm = ArchiveViewModel(repository, scope = backgroundScope)
        vm.state.first { it.entries.isNotEmpty() }

        vm.onQuery("contraste de pasados")
        assertEquals(entry.id, vm.state.first { !it.searching }.entries.single().id)
        vm.selectDiary(entry.id)
        assertTrue(vm.copySelected()!!.contains("MATERIAL TRABAJADO\nPágina 42, ejercicio 3."))

        vm.startEditing()
        vm.onEdit(vm.state.value.editableFields!!.copy(topics = "intento de reemplazo", pedagogicalDate = "2026-09-13"))
        vm.saveEdit()
        vm.state.first { !it.busy }
        val saved = repository.getBySession(entry.sessionId)!!
        assertEquals("2026-09-13", saved.pedagogicalDate)
        assertEquals("Conectores concesivos", saved.topics)
        assertEquals("Contraste de pasados", saved.reportSummary)
    }
    private lateinit var database: DiarioDatabase
    private lateinit var repository: RoomDiaryRepository
    private var nextId = 0

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), DiarioDatabase::class.java,
        ).allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        repository = RoomDiaryRepository(database, Clock { 2_000 }, IdProvider { "entry-${++nextId}" })
    }

    @After fun tearDown() = database.close()

    @Test fun `query filters date level and all five fields without case or accents`() = runTest {
        seed("one")
        val vm = ArchiveViewModel(repository, scope = backgroundScope)
        vm.state.first { it.entries.size == 1 }
        for (query in listOf("2026-09-12", "b2", "CONCESÍVOS", "DEBATE", "42-43", "3 y 4", "tarea seis")) {
            vm.onQuery(query)
            val state = vm.state.first { it.query == query && !it.searching }
            assertEquals(query, listOf("entry-1"), state.entries.map { it.id })
        }
        vm.onQuery("inexistente")
        assertTrue(vm.state.first { !it.searching }.entries.isEmpty())
    }

    @Test fun `editing archived fields persists after temporary data is gone and recreation`() = runTest {
        val entry = seed("one")
        val vm = ArchiveViewModel(repository, scope = backgroundScope)
        vm.state.first { it.entries.isNotEmpty() }
        vm.selectDiary(entry.id)
        vm.startEditing()
        vm.onEdit(vm.state.value.editableFields!!.copy(homework = "Ejercicio 8"))
        vm.saveEdit()
        vm.state.first { !it.busy && it.editableFields == null }
        assertEquals("Ejercicio 8", repository.getBySession(entry.sessionId)!!.homework)
        assertTrue(repository.getBySession(entry.sessionId)!!.temporariesDeleted)
        val recreated = ArchiveViewModel(repository, scope = backgroundScope)
        assertEquals("Ejercicio 8", recreated.state.first { it.entries.isNotEmpty() }.entries.single().homework)
    }

    @Test fun `copy uses saved permanent fields and omits empty fields and metadata`() = runTest {
        val entry = seed("one")
        repository.update(entry.copy(activities = "", pages = "", completedExercises = ""))
        val vm = ArchiveViewModel(repository, scope = backgroundScope)
        vm.state.first { it.entries.isNotEmpty() }
        vm.selectDiary(entry.id)
        assertEquals("12/09/2026\n\nTemas: Conectores concesivos\nTarea: Tarea seis", vm.copySelected())
    }

    @Test fun `delete needs confirmation and keeps immutable target when search changes`() = runTest {
        val entry = seed("one")
        val other = seed("two")
        val vm = ArchiveViewModel(repository, scope = backgroundScope)
        vm.state.first { it.entries.size == 2 }
        vm.confirmDelete()
        runCurrent()
        assertNotNull(repository.getBySession(entry.sessionId))
        vm.selectDiary(entry.id)
        vm.requestDelete()
        assertNotNull(repository.getBySession(entry.sessionId))
        vm.onQuery("inexistente")
        vm.state.first { !it.searching }
        assertEquals(entry.id, vm.state.value.deleteConfirmation!!.id)
        vm.confirmDelete()
        vm.state.first { !it.busy && it.deleteConfirmation == null }
        assertNull(repository.getBySession(entry.sessionId))
        assertNotNull(repository.getBySession(other.sessionId))
    }

    @Test fun `changing selection cancels old confirmation and cannot delete new selection`() = runTest {
        val entry = seed("one")
        val other = seed("two")
        val vm = ArchiveViewModel(repository, scope = backgroundScope)
        vm.state.first { it.entries.size == 2 }
        vm.selectDiary(entry.id)
        vm.requestDelete()
        vm.selectDiary(other.id)
        vm.confirmDelete()
        runCurrent()
        assertNull(vm.state.value.deleteConfirmation)
        assertNotNull(repository.getBySession(entry.sessionId))
        assertNotNull(repository.getBySession(other.sessionId))
    }

    @Test fun `cancel confirmation preserves diary`() = runTest {
        val entry = seed("one")
        val vm = ArchiveViewModel(repository, scope = backgroundScope)
        vm.state.first { it.entries.isNotEmpty() }
        vm.selectDiary(entry.id)
        vm.requestDelete()
        vm.cancelDelete()
        vm.confirmDelete()
        runCurrent()
        assertNotNull(repository.getBySession(entry.sessionId))
    }

    @Test fun `pending diary deletion displays actionable failure without losing selection`() = runTest {
        val entry = seed("one", cleaned = false)
        val vm = ArchiveViewModel(repository, scope = backgroundScope)
        vm.state.first { it.entries.isNotEmpty() }
        vm.selectDiary(entry.id)
        vm.requestDelete()
        vm.confirmDelete()
        val state = vm.state.first { !it.busy && it.message != null }
        assertTrue(state.message!!.contains("limpieza"))
        assertEquals(entry.id, state.selectedDiary?.id)
        assertNull(state.deleteConfirmation)
        assertNotNull(repository.getBySession(entry.sessionId))
    }

    @Test fun `opening approved diary waits for archive flow and selects exact id`() = runTest {
        val vm = ArchiveViewModel(repository, scope = backgroundScope)
        vm.openSavedDiary("entry-2")
        seed("one")
        val target = seed("two")
        val state = vm.state.first { it.selectedDiary?.id == target.id }
        assertEquals("entry-2", state.selectedDiary!!.id)
    }

    @Test fun `mode defaults conservative and each selection persists immediately across recreation`() = runTest {
        val vm = ArchiveViewModel(repository, scope = backgroundScope)
        assertEquals(InterpretationMode.CONSERVATIVE, vm.state.value.mode)
        for (mode in listOf(InterpretationMode.BALANCED, InterpretationMode.EXHAUSTIVE, InterpretationMode.CONSERVATIVE)) {
            vm.setMode(mode)
            vm.state.first { !it.busy && it.mode == mode }
            assertEquals(mode, repository.observeMode().first())
            val recreated = ArchiveViewModel(repository, scope = backgroundScope)
            recreated.state.first { it.mode == mode }
        }
    }

    private suspend fun seed(session: String, cleaned: Boolean = true, editorial: Boolean = false): DiaryEntry {
        database.sessions().insertSession(SessionEntity(session, "2026-09-12", "B2", SessionState.ARCHIVED.name, 500, 600))
        database.sessions().saveEditorialReport(
            EditorialReportEntity(
                session, "hash", "READY",
                if (editorial) "{\"summary\":\"Contraste de pasados\",\"material\":[{\"text\":\"Página 42, ejercicio 3.\",\"source_claim_ids\":[\"p42\",\"e3\"]}],\"homework\":[],\"summary_source_claim_ids\":[\"topic\"],\"discarded\":[]}" else "{}",
                if (editorial) "Contraste de pasados" else "",
                if (editorial) "Página 42, ejercicio 3." else "",
                "", "GEMINI", "gemini-2.5-flash", "p", "s", "v", null, 700,
            ),
        )
        val result = repository.saveVerified(SessionId(session), DiaryDraftEntity(
            "draft-$session", session, "CONSERVATIVE", "Conectores concesivos", "Debate guiado",
            "42-43", "3 y 4", "Tarea seis", 1_000,
        )) as DiarySaveResult.Verified
        if (cleaned) repository.markTemporariesDeleted(result.entry.sessionId, result.entry.id)
        return repository.getBySession(result.entry.sessionId)!!
    }
}
