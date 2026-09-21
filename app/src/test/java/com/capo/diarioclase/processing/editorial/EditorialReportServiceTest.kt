package com.capo.diarioclase.processing.editorial

import com.capo.diarioclase.data.db.EditorialReportEntity
import com.capo.diarioclase.data.db.SessionId
import com.capo.diarioclase.processing.evidence.ClaimCategory
import com.capo.diarioclase.processing.evidence.ClaimOrigin
import com.capo.diarioclase.processing.evidence.ClaimStatus
import com.capo.diarioclase.processing.evidence.EvidenceClaim
import com.capo.diarioclase.processing.evidence.EvidenceRef
import com.capo.diarioclase.processing.evidence.InterpretationMode
import com.capo.diarioclase.processing.semantic.EphemeralCredential
import com.capo.diarioclase.processing.semantic.InferenceProvider
import com.capo.diarioclase.processing.semantic.ProviderModel
import com.capo.diarioclase.processing.semantic.ProviderOutcome
import com.capo.diarioclase.data.db.BlockId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorialReportServiceTest {
    @Test
    fun `matching ready report returns without provider call`() = runTest {
        val claims = listOf(pageClaim())
        val request = EditorialReportPacketBuilder().build("s", InterpretationMode.CONSERVATIVE, claims)
        val store = FakeStore().apply { seedReady(readyEntity(request.inputHash)) }
        val client = DeferredClient(CompletableDeferred(success(validReport)))
        val service = service(store, client)

        val outcome = service.generate(SessionId("s"), InterpretationMode.CONSERVATIVE, claims)

        assertTrue(outcome is EditorialGenerationOutcome.Ready)
        assertTrue((outcome as EditorialGenerationOutcome.Ready).cacheHit)
        assertEquals(0, client.calls)
    }

    @Test
    fun `late response is discarded after report becomes stale`() = runTest {
        val store = FakeStore()
        val response = CompletableDeferred<ProviderOutcome>()
        val client = DeferredClient(response)
        val service = service(store, client)

        val pending = async {
            service.generate(SessionId("s"), InterpretationMode.CONSERVATIVE, listOf(pageClaim()))
        }
        while (client.calls == 0) testScheduler.runCurrent()
        store.markStale("s")
        response.complete(success(validReport))

        assertTrue(pending.await() is EditorialGenerationOutcome.Obsolete)
        assertEquals(EditorialReportState.STALE.name, store.current.value!!.state)
    }

    @Test
    fun `validated prose is persisted without local rewriting`() = runTest {
        val store = FakeStore()
        val client = DeferredClient(CompletableDeferred(success(validReport)))
        val service = service(store, client)

        val outcome = service.generate(SessionId("s"), InterpretationMode.CONSERVATIVE, listOf(pageClaim()))

        assertTrue(outcome is EditorialGenerationOutcome.Ready)
        val entity = (outcome as EditorialGenerationOutcome.Ready).entity
        assertEquals("Página 42 — actividad exacta.", entity.materialText)
        assertEquals(validReport, entity.rawJson)
    }

    private fun service(store: FakeStore, client: DeferredClient): EditorialReportService {
        val router = EditorialReportRouter(
            clients = mapOf(InferenceProvider.GEMINI to client),
            validator = EditorialReportValidator(),
            credentialFor = { EphemeralCredential("secret") },
        )
        return EditorialReportService(
            builder = EditorialReportPacketBuilder(),
            router = router,
            store = store,
            enabledProviders = { listOf(ProviderModel(InferenceProvider.GEMINI, "gemini-2.5-flash")) },
        )
    }

    private class DeferredClient(
        private val outcome: CompletableDeferred<ProviderOutcome>,
    ) : EditorialProviderClient {
        var calls = 0
        override suspend fun generate(
            request: EditorialReportRequest,
            credential: EphemeralCredential,
            repair: EditorialRepair?,
        ): ProviderOutcome {
            calls++
            return outcome.await()
        }
    }

    private class FakeStore : EditorialReportStore {
        val current = MutableStateFlow<EditorialReportEntity?>(null)

        fun seedReady(entity: EditorialReportEntity) { current.value = entity }

        override suspend fun readyFor(sessionId: String, inputHash: String): EditorialReportEntity? =
            current.value?.takeIf { it.sessionId == sessionId && it.inputHash == inputHash && it.state == "READY" }

        override suspend fun begin(sessionId: String, inputHash: String) {
            current.value = readyEntity(inputHash).copy(state = "GENERATING", rawJson = "", summary = "", materialText = "")
        }

        override suspend fun saveReadyIfCurrent(
            sessionId: String,
            inputHash: String,
            ready: EditorialReportEntity,
        ): Boolean {
            val existing = current.value
            if (existing?.sessionId != sessionId || existing.inputHash != inputHash || existing.state != "GENERATING") return false
            current.value = ready
            return true
        }

        override suspend fun markFailedIfCurrent(sessionId: String, inputHash: String, failure: String) {
            val existing = current.value
            if (existing?.sessionId == sessionId && existing.inputHash == inputHash && existing.state == "GENERATING") {
                current.value = existing.copy(state = "FAILED", failure = failure)
            }
        }

        override suspend fun markStale(sessionId: String) {
            current.value = current.value?.takeIf { it.sessionId == sessionId }?.copy(state = "STALE")
        }

        override fun observe(sessionId: String): Flow<EditorialReportEntity?> = current
        override fun observeLatest(): Flow<EditorialReportEntity?> = current
    }

    private companion object {
        const val validReport =
            "{\"summary\":\"\",\"material\":[{\"text\":\"Página 42 — actividad exacta.\",\"source_claim_ids\":[\"page-42\"]}],\"homework\":[],\"summary_source_claim_ids\":[],\"discarded\":[]}"

        fun success(json: String) = ProviderOutcome.Success(InferenceProvider.GEMINI, "gemini-2.5-flash", json)

        fun readyEntity(hash: String) = EditorialReportEntity(
            "s", hash, "READY", validReport, "", "Página 42 — actividad exacta.", "",
            "GEMINI", "gemini-2.5-flash", "p", "s", "v", null, 1,
        )

        fun pageClaim(): EvidenceClaim {
            val evidence = EvidenceRef(BlockId("b"), 0, 1, "página 42", 1, 1, 1)
            return EvidenceClaim(
                "page-42", ClaimCategory.PAGE, "Página 42", "42", ClaimStatus.PERFORMED,
                .95, ClaimOrigin.GEMINI, evidence, evidences = listOf(evidence),
            )
        }
    }
}
