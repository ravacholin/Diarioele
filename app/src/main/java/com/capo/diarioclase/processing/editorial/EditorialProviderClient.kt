package com.capo.diarioclase.processing.editorial

import com.capo.diarioclase.processing.semantic.EphemeralCredential
import com.capo.diarioclase.processing.semantic.ProviderOutcome

interface EditorialProviderClient {
    suspend fun generate(
        request: EditorialReportRequest,
        credential: EphemeralCredential,
        repair: EditorialRepair? = null,
    ): ProviderOutcome
}
