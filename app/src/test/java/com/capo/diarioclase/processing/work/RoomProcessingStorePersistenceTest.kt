package com.capo.diarioclase.processing.work

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.capo.diarioclase.core.clock.Clock
import com.capo.diarioclase.data.db.AudioSegmentEntity
import com.capo.diarioclase.data.db.BlockEntity
import com.capo.diarioclase.data.db.BlockId
import com.capo.diarioclase.data.db.DiarioDatabase
import com.capo.diarioclase.data.db.SessionEntity
import com.capo.diarioclase.data.db.SessionId
import com.capo.diarioclase.data.db.TranscriptSpanEntity
import com.capo.diarioclase.data.db.ReviewAction
import com.capo.diarioclase.processing.evidence.ClaimCategory
import com.capo.diarioclase.processing.evidence.ClaimOrigin
import com.capo.diarioclase.processing.evidence.ClaimStatus
import com.capo.diarioclase.processing.evidence.DiaryDraft
import com.capo.diarioclase.processing.evidence.EvidenceClaim
import com.capo.diarioclase.processing.evidence.EvidenceRef
import com.capo.diarioclase.processing.evidence.InterpretationMode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class RoomProcessingStorePersistenceTest {

    @Test fun `evidence and supersessions survive reopen`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "store-${UUID.randomUUID()}.db"

        // Primera apertura: sembrar transcripción y guardar el grafo de claims.
        val first = Room.databaseBuilder(context, DiarioDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val dao = first.sessions()
            dao.insertSession(SessionEntity("s", "2026-09-16", null, "AWAITING_REVIEW", 1, 1))
            dao.insertBlock(BlockEntity("b", "s", 0, 1, null, null))
            dao.saveSegment(AudioSegmentEntity("seg", "b", 0, "/tmp/seg.wav", 1, 4_000, null, "TRANSCRIBED"))
            dao.insertTranscript(
                listOf(
                    TranscriptSpanEntity("t1", "seg", "b", 0, 1_000, "página catorce", 0.9),
                    TranscriptSpanEntity("t2", "seg", "b", 1_000, 2_000, "ejercicio tres", 0.9),
                ),
            )
            val store = RoomProcessingStore(first, Clock { 2 })
            store.saveEvidence(
                SessionId("s"),
                listOf(
                    claim(
                        id = "assigned-4",
                        transcriptSpanIds = listOf("t1", "t2"),
                        evidences = listOf(evidence("b", 0, 1_000, false), evidence("b", 1_000, 2_000, true)),
                        supersedes = listOf("claim-old"),
                        active = true,
                    ),
                    claim(id = "claim-old", transcriptSpanIds = listOf("t1"), evidences = listOf(evidence("b", 0, 1_000, false)), active = false),
                ),
                DiaryDraft("s", InterpretationMode.CONSERVATIVE, "", "", "", "", "", emptyList(), emptyList()),
            )
        } finally {
            first.close()
        }

        // Segunda apertura: reconstruir los claims desde la base persistida.
        val reopened = Room.databaseBuilder(context, DiarioDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val store = RoomProcessingStore(reopened, Clock { 3 })
            val claims = store.persistedClaims(SessionId("s"))

            val survivor = claims.single { it.id == "assigned-4" }
            assertEquals(2, survivor.evidences.size)
            assertEquals(listOf("t1", "t2"), survivor.transcriptSpanIds)
            assertEquals(listOf(false, true), survivor.evidences.map { it.contextual })
            assertEquals(listOf("claim-old"), survivor.supersedesClaimKeys)
            assertFalse(claims.single { it.id == "claim-old" }.active)
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun `accepting an uncertain page moves it into the draft immediately`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "review-${UUID.randomUUID()}.db"
        val database = Room.databaseBuilder(context, DiarioDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val dao = database.sessions()
            dao.insertSession(SessionEntity("s", "2026-09-17", null, "AWAITING_REVIEW", 1, 1))
            val store = RoomProcessingStore(database, Clock { 2 })
            store.saveEvidence(
                SessionId("s"),
                listOf(
                    EvidenceClaim(
                        id = "page-2",
                        category = ClaimCategory.PAGE,
                        value = "2",
                        normalizedValue = "2",
                        status = ClaimStatus.UNCERTAIN,
                        confidence = 0.8,
                        origin = ClaimOrigin.GEMINI,
                        evidence = EvidenceRef(BlockId("b"), 0, 1_000, "página 2"),
                    ),
                ),
                DiaryDraft("s", InterpretationMode.CONSERVATIVE, "", "", "", "", "", emptyList(), emptyList()),
            )

            store.reviewClaim("page-2", ReviewAction.ACCEPT, null)

            assertEquals(ClaimStatus.PERFORMED, store.persistedClaims(SessionId("s")).single().status)
            assertEquals("Página 2", dao.draft("s")!!.pages)
            assertEquals(ReviewAction.ACCEPT, store.revisions("page-2").single().action)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun `two concurrent accepts are both present in the regenerated draft`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "concurrent-review-${UUID.randomUUID()}.db"
        val database = Room.databaseBuilder(context, DiarioDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val dao = database.sessions()
            dao.insertSession(SessionEntity("s", "2026-09-17", null, "AWAITING_REVIEW", 1, 1))
            val store = RoomProcessingStore(database, Clock { 2 })
            store.saveEvidence(
                SessionId("s"),
                listOf(
                    reviewPage("page-1", "1", startMs = 0),
                    reviewPage("page-2", "2", startMs = 1),
                ),
                DiaryDraft("s", InterpretationMode.CONSERVATIVE, "", "", "", "", "", emptyList(), emptyList()),
            )

            listOf("page-1", "page-2").map { id ->
                async { store.reviewClaim(id, ReviewAction.ACCEPT, null) }
            }.awaitAll()

            assertEquals("Página 1\nPágina 2", dao.draft("s")!!.pages)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun `reviewing an unknown claim fails without recording a revision`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "unknown-review-${UUID.randomUUID()}.db"
        val database = Room.databaseBuilder(context, DiarioDatabase::class.java, name)
            .allowMainThreadQueries().build()
        try {
            val dao = database.sessions()
            dao.insertSession(SessionEntity("s", "2026-09-17", null, "AWAITING_REVIEW", 1, 1))
            val store = RoomProcessingStore(database, Clock { 2 })

            val failure = runCatching {
                store.reviewClaim("missing", ReviewAction.ACCEPT, null)
            }.exceptionOrNull()

            assertEquals(IllegalArgumentException::class, failure!!::class)
            assertEquals(emptyList<DraftFieldRevision>(), store.revisions("missing"))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    private fun evidence(block: String, startMs: Long, endMs: Long, contextual: Boolean) =
        EvidenceRef(BlockId(block), startMs, endMs, "cita", contextual = contextual)

    private fun reviewPage(id: String, value: String, startMs: Long = 0) = EvidenceClaim(
        id = id,
        category = ClaimCategory.PAGE,
        value = value,
        normalizedValue = value,
        status = ClaimStatus.UNCERTAIN,
        confidence = 0.8,
        origin = ClaimOrigin.GEMINI,
        evidence = EvidenceRef(BlockId("b"), startMs, startMs + 1_000, "página $value"),
    )

    private fun claim(
        id: String,
        transcriptSpanIds: List<String>,
        evidences: List<EvidenceRef>,
        supersedes: List<String> = emptyList(),
        active: Boolean,
    ) = EvidenceClaim(
        id = id,
        category = ClaimCategory.PAGE,
        value = "14",
        normalizedValue = "14",
        status = ClaimStatus.PERFORMED,
        confidence = 0.95,
        origin = ClaimOrigin.GEMINI,
        evidence = evidences.first(),
        active = active,
        evidences = evidences,
        supersedesClaimKeys = supersedes,
        transcriptSpanIds = transcriptSpanIds,
    )
}
