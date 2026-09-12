package com.capo.diarioclase.processing.evidence
import com.capo.diarioclase.data.db.BlockId
import com.capo.diarioclase.processing.transcription.TranscriptSpan
import org.junit.Assert.*
import org.junit.Test
class LiteralClaimExtractorTest {
 @Test fun `extracts pages exercises activities and homework with evidence`() {val spans=listOf("Hoy trabajamos los pasados","Vamos a la página cuarenta y dos","Hacemos el ejercicio tres en parejas","El cinco queda para mañana").mapIndexed{i,t->TranscriptSpan("s$i","audio",BlockId("b"),i*1000L,(i+1)*1000L,t,.9)};val claims=LiteralClaimExtractor{(idCounter++).toString()}.extract(spans);assertTrue(claims.any{it.category==ClaimCategory.PAGE&&it.value=="42"});assertTrue(claims.any{it.category==ClaimCategory.EXERCISE&&it.value=="3 (p. 42)"});assertTrue(claims.any{it.category==ClaimCategory.ACTIVITY});assertTrue(claims.any{it.category==ClaimCategory.HOMEWORK});assertTrue(claims.all{it.evidence.excerpt.isNotBlank()})}
 companion object {var idCounter=0}
}
