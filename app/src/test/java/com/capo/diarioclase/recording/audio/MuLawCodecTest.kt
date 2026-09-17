package com.capo.diarioclase.recording.audio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
class MuLawCodecTest {
 @Test fun `silence round trips exactly`() {
  assertEquals(0, MuLawCodec.decode(MuLawCodec.encode(0)).toInt())
 }
 @Test fun `round trip preserves sign and stays within mu-law tolerance`() {
  var value=-32_768
  while(value<=32_767){
   val restored=MuLawCodec.decode(MuLawCodec.encode(value.toShort())).toInt()
   if(value>0)assertTrue("positivo",restored>=0) else if(value<0)assertTrue("negativo",restored<=0)
   val magnitude=abs(value)
   if(magnitude>=512)assertTrue("error relativo en $value (dio $restored)",abs(restored-value).toDouble()/magnitude<=0.07)
   else assertTrue("error absoluto en $value (dio $restored)",abs(restored-value)<=33)
   value+=137
  }
 }
 @Test fun `every byte decodes to a value that re-encodes to itself`() {
  // Invariante de idempotencia sobre el dominio µ-law, salvo el cero negativo (0x7F),
  // que colapsa al cero positivo (0xFF) por diseño de G.711.
  for(code in 0..255){
   if(code==0x7F)continue
   val restored=MuLawCodec.decode(code.toByte())
   assertEquals("code $code",code.toByte(),MuLawCodec.encode(restored))
  }
 }
}
