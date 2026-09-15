package com.capo.diarioclase.processing.work

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.capo.diarioclase.core.clock.Clock
import com.capo.diarioclase.data.db.DiarioDatabase
import com.capo.diarioclase.data.db.SessionEntity
import com.capo.diarioclase.processing.evidence.ClaimCategory
import com.capo.diarioclase.processing.evidence.ClaimOrigin
import com.capo.diarioclase.processing.evidence.ClaimStatus
import com.capo.diarioclase.processing.evidence.InterpretationMode
import com.capo.diarioclase.processing.semantic.ClaimIdentity
import com.capo.diarioclase.processing.semantic.InferenceProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomInterpretationJournalTest {
    private lateinit var database: DiarioDatabase
    private lateinit var journal: RoomInterpretationJournal

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, DiarioDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        journal = RoomInterpretationJournal(database, Clock { 7 })
    }

    @After fun tearDown() {
        database.close()
    }

    @Test fun evidence_and_supersessions_survive_reopen() = runTest {
        seedSession("s1")
        journal.beginRun(runRecord("run-1", "s1"))
        journal.saveClaims(listOf(claimWithTwoEvidenceAndSupersession(sessionId = "s1", runId = "run-1")))

        val loaded = journal.loadClaims("run-1")

        assertEquals(1, loaded.size)
        assertEquals(2, loaded.single().evidences.size)
        assertEquals(listOf("t-span-1", "t-span-2"), loaded.single().evidences.map { it.transcriptSpanId })
        assertEquals(listOf(false, true), loaded.single().evidences.map { it.contextual })
        assertEquals(listOf("claim-old"), loaded.single().supersedesClaimIds)
    }

    @Test fun same_provider_key_in_two_packets_persists_as_distinct_claims() = runTest {
        seedSession("s1")
        journal.beginRun(runRecord("run-1", "s1"))
        val idA = ClaimIdentity.id("run-1", "packet-a", InferenceProvider.GEMINI, "C1")
        val idB = ClaimIdentity.id("run-1", "packet-b", InferenceProvider.GEMINI, "C1")
        journal.saveClaims(
            listOf(
                claim(id = idA, sessionId = "s1", runId = "run-1", packetId = "packet-a", providerClaimKey = "C1"),
                claim(id = idB, sessionId = "s1", runId = "run-1", packetId = "packet-b", providerClaimKey = "C1"),
            ),
        )

        val loaded = journal.loadClaims("run-1")

        assertEquals(2, loaded.size)
        assertEquals(setOf(idA, idB), loaded.map { it.id }.toSet())
        assertTrue(loaded.all { it.providerClaimKey == "C1" })
    }

    @Test fun run_packet_and_attempt_lifecycle_persists_typed_outcomes() = runTest {
        seedSession("s1")
        journal.beginRun(runRecord("run-1", "s1"))
        journal.startPacket("run-1", "packet-a", ordinal = 0, requestHash = "req-hash", requestBytes = 1_200)
        journal.recordAttempt(
            ProviderAttemptRecord(
                id = "att-1",
                runId = "run-1",
                packetId = "packet-a",
                provider = InferenceProvider.GEMINI,
                modelId = "gemini-free",
                attempt = 1,
                cacheHit = false,
                outcome = "REMOTE_OK",
                durationMs = 42,
                startedAtEpochMs = 5,
            ),
        )
        journal.completePacket("run-1", "packet-a", InterpretationPacketState.REMOTE_OK, InferenceProvider.GEMINI)
        journal.completeRun("run-1", InterpretationRunState.REMOTE_OK)

        val dao = database.sessions()
        assertEquals(InterpretationRunState.REMOTE_OK.name, dao.interpretationRun("run-1")!!.state)
        val packet = dao.interpretationPackets("run-1").single()
        assertEquals(InterpretationPacketState.REMOTE_OK.name, packet.state)
        assertEquals(InferenceProvider.GEMINI.name, packet.provider)
        assertNotNull(packet.completedAtEpochMs)
        val attempt = dao.providerAttempts("run-1", "packet-a").single()
        assertEquals("REMOTE_OK", attempt.outcome)
        assertEquals("gemini-free", attempt.modelId)
    }

    @Test fun failing_a_run_records_typed_failure_without_completing_ok() = runTest {
        seedSession("s1")
        journal.beginRun(runRecord("run-1", "s1"))
        journal.failRun("run-1", InterpretationFailure.DEADLINE)

        val run = database.sessions().interpretationRun("run-1")!!
        assertEquals(InterpretationRunState.FAILED.name, run.state)
        assertEquals(InterpretationFailure.DEADLINE.name, run.failure)
        assertNotNull(run.completedAtEpochMs)
    }

    private suspend fun seedSession(id: String) {
        database.sessions().insertSession(SessionEntity(id, "2026-09-15", null, "AWAITING_REVIEW", 1, 1))
    }

    private fun runRecord(id: String, sessionId: String) = InterpretationRunRecord(
        id = id,
        sessionId = sessionId,
        appVersion = "0.5.2-integrity",
        whisperVersion = "ggml-base",
        promptVersion = "p1",
        schemaVersion = "s1",
        validatorVersion = "v1",
        transcriptHash = "hash",
        mode = InterpretationMode.CONSERVATIVE,
        startedAtEpochMs = 1,
    )

    private fun claim(
        id: String,
        sessionId: String,
        runId: String,
        packetId: String,
        providerClaimKey: String,
        evidences: List<PersistedEvidence> = listOf(PersistedEvidence("t-span-1", 0, false)),
        supersedesClaimIds: List<String> = emptyList(),
    ) = PersistedClaim(
        id = id,
        sessionId = sessionId,
        runId = runId,
        packetId = packetId,
        providerClaimKey = providerClaimKey,
        category = ClaimCategory.PAGE,
        value = "14",
        normalizedValue = "14",
        status = ClaimStatus.PERFORMED,
        origin = ClaimOrigin.GEMINI,
        declaredConfidence = 0.9,
        effectiveConfidence = 0.9,
        claimOrdinal = 0,
        blockId = "b1",
        startMs = 1_000,
        endMs = 2_000,
        excerpt = "página catorce",
        evidences = evidences,
        supersedesClaimIds = supersedesClaimIds,
    )

    private fun claimWithTwoEvidenceAndSupersession(sessionId: String, runId: String) = claim(
        id = ClaimIdentity.id(runId, "packet-a", InferenceProvider.GEMINI, "C1"),
        sessionId = sessionId,
        runId = runId,
        packetId = "packet-a",
        providerClaimKey = "C1",
        evidences = listOf(
            PersistedEvidence("t-span-1", 0, false),
            PersistedEvidence("t-span-2", 1, true),
        ),
        supersedesClaimIds = listOf("claim-old"),
    )
}
