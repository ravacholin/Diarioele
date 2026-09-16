package com.capo.diarioclase.processing.work

import com.capo.diarioclase.data.db.BlockId
import com.capo.diarioclase.data.db.SessionId
import com.capo.diarioclase.processing.evidence.ClaimCategory
import com.capo.diarioclase.processing.evidence.ClaimOrigin
import com.capo.diarioclase.processing.evidence.ClaimStatus
import com.capo.diarioclase.processing.evidence.DiaryDraft
import com.capo.diarioclase.processing.evidence.EvidenceClaim
import com.capo.diarioclase.processing.evidence.EvidenceRef
import com.capo.diarioclase.processing.evidence.InterpretationMode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDraftReprojectorTest {

    private class FakeStore(private val claims: List<EvidenceClaim>) : LocalReprojectionStore {
        var savedMode: InterpretationMode? = null
        override suspend fun persistedClaims(sessionId: SessionId): List<EvidenceClaim> = claims
        override suspend fun mergeFieldEditsAndSave(draft: DiaryDraft): DiaryDraft {
            savedMode = draft.mode
            return draft
        }
    }

    @Test fun `reprojects the draft from persisted claims at the requested mode`() = runTest {
        val store = FakeStore(listOf(pageClaim(confidence = 0.95)))

        val draft = LocalDraftReprojector(store).reproject(SessionId("s"), InterpretationMode.EXHAUSTIVE)

        assertEquals(InterpretationMode.EXHAUSTIVE, draft.mode)
        assertEquals(InterpretationMode.EXHAUSTIVE, store.savedMode)
        assertTrue(draft.pages.contains("42"))
    }

    @Test fun `mode changes the projection locally without touching providers`() = runTest {
        // Un claim de confianza media: conservador lo deja por confirmar (no lo acepta);
        // exhaustivo lo acepta. Cambiar de modo reproyecta distinto solo con datos locales.
        val store = FakeStore(listOf(pageClaim(confidence = 0.6)))
        val reprojector = LocalDraftReprojector(store)

        val conservative = reprojector.reproject(SessionId("s"), InterpretationMode.CONSERVATIVE)
        val exhaustive = reprojector.reproject(SessionId("s"), InterpretationMode.EXHAUSTIVE)

        assertEquals("", conservative.pages)
        assertTrue(exhaustive.pages.contains("42"))
    }

    private fun pageClaim(confidence: Double) = EvidenceClaim(
        id = "c1",
        category = ClaimCategory.PAGE,
        value = "42",
        normalizedValue = "42",
        status = ClaimStatus.PERFORMED,
        confidence = confidence,
        origin = ClaimOrigin.GEMINI,
        evidence = EvidenceRef(BlockId("b"), 0, 1_000, "página cuarenta y dos"),
        active = true,
    )
}
