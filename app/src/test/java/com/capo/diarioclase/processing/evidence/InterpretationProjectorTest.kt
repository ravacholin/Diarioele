package com.capo.diarioclase.processing.evidence
import com.capo.diarioclase.data.db.BlockId
import org.junit.Assert.assertEquals
import org.junit.Test
class InterpretationProjectorTest {
 private fun claim(id:String,confidence:Double)=EvidenceClaim(id,ClaimCategory.TOPIC,id,id,ClaimStatus.PERFORMED,confidence,ClaimOrigin.LOCAL_RULE,EvidenceRef(BlockId("b"),0,1,"evidence"))
 @Test fun `conservative mode separates certainty from confirmation`() {val result=InterpretationProjector().project(listOf(claim("certain",.9),claim("maybe",.7),claim("weak",.3)),InterpretationMode.CONSERVATIVE);assertEquals(listOf("certain"),result.accepted.map{it.id});assertEquals(listOf("maybe"),result.confirm.map{it.id});assertEquals(listOf("weak"),result.hidden.map{it.id})}
 @Test fun `exhaustive mode exposes weaker evidence without inventing`() {val blank=claim("blank",.9).copy(evidence=EvidenceRef(BlockId("b"),0,1,""));val result=InterpretationProjector().project(listOf(claim("weak",.56),blank),InterpretationMode.EXHAUSTIVE);assertEquals(listOf("weak"),result.accepted.map{it.id});assertEquals(1,result.hidden.size)}
}
