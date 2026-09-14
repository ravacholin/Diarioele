package com.capo.diarioclase.processing.work

import com.capo.diarioclase.data.db.SessionId
import com.capo.diarioclase.processing.evidence.InterpretationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionWorkerTest {
    @Test
    fun requestCarriesDurableSessionIdentityAndMode() {
        val request = TranscriptionWorker.request(
            SessionId("day"),
            InterpretationMode.CONSERVATIVE,
        )

        assertEquals(
            "day",
            request.workSpec.input.getString(TranscriptionWorker.KEY_SESSION_ID),
        )
        assertEquals(
            InterpretationMode.CONSERVATIVE.name,
            request.workSpec.input.getString(TranscriptionWorker.KEY_MODE),
        )
        assertTrue(request.tags.contains("transcription-day"))
    }
}
