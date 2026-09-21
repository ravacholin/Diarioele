package com.capo.diarioclase.ui.capture

import com.capo.diarioclase.processing.editorial.EditorialReportState
import com.capo.diarioclase.processing.editorial.EditorialReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureScreenLogicTest {
    @Test fun `only ready editorial report can be approved`() {
        assertTrue(canApproveEditorial(editorial(EditorialReportState.READY)))
        assertFalse(canApproveEditorial(editorial(EditorialReportState.GENERATING)))
        assertFalse(canApproveEditorial(editorial(EditorialReportState.STALE)))
        assertFalse(canApproveEditorial(editorial(EditorialReportState.FAILED)))
        assertFalse(canApproveEditorial(EditorialReportUi(EditorialReportState.READY, null, null, null)))
        assertFalse(canApproveEditorial(null))
    }

    @Test fun `editorial action labels distinguish generation repair and rewrite`() {
        assertEquals("GENERANDO FICHA FINAL", editorialActionLabel(EditorialReportState.GENERATING))
        assertEquals("REGENERAR FICHA FINAL", editorialActionLabel(EditorialReportState.STALE))
        assertEquals("REINTENTAR FICHA FINAL", editorialActionLabel(EditorialReportState.FAILED))
        assertEquals("REGENERAR REDACCIÓN", editorialActionLabel(EditorialReportState.READY))
        assertEquals("GENERAR FICHA FINAL", editorialActionLabel(null))
    }

    private fun editorial(state: EditorialReportState) = EditorialReportUi(
        state,
        if (state == EditorialReportState.READY) EditorialReport("Resumen", emptyList(), emptyList(), emptyList(), emptyList()) else null,
        null,
        null,
    )
}
