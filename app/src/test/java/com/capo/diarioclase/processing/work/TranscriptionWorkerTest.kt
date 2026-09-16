package com.capo.diarioclase.processing.work

import com.capo.diarioclase.data.db.SessionId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pruebas del título de la notificación por fase (Fase 6, Q5). Función pura, sin WorkManager.
 */
class TranscriptionWorkerTest {

    private fun progress(processedMs: Long, totalMs: Long, state: TranscriptionRunState) =
        TranscriptionProgress(SessionId("s"), processedMs, totalMs, 0, 0, 0, state, null)

    @Test
    fun `while transcribing the title stays transcribing`() {
        assertEquals(
            "TRANSCRIBIENDO EN ESPAÑOL",
            transcriptionNotificationTitle(progress(30_000, 60_000, TranscriptionRunState.PROCESSING)),
        )
    }

    @Test
    fun `once transcription completes and work continues the title is generating the ficha`() {
        assertEquals(
            "GENERANDO LA FICHA",
            transcriptionNotificationTitle(progress(60_000, 60_000, TranscriptionRunState.PROCESSING)),
        )
    }

    @Test
    fun `a null or preparing progress stays transcribing`() {
        assertEquals("TRANSCRIBIENDO EN ESPAÑOL", transcriptionNotificationTitle(null))
        assertEquals(
            "TRANSCRIBIENDO EN ESPAÑOL",
            transcriptionNotificationTitle(progress(0, 0, TranscriptionRunState.PREPARING)),
        )
    }
}
