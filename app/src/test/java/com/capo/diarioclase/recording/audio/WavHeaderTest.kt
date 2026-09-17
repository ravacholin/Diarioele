package com.capo.diarioclase.recording.audio
import org.junit.Assert.assertEquals
import org.junit.Test
class WavHeaderTest {
 @Test fun `header describes sixteen kHz mono eight bit mu-law`() {
  val b=WavHeader.forMuLaw(32_000).encode()
  assertEquals("RIFF",String(b.copyOfRange(0,4)));assertEquals("WAVE",String(b.copyOfRange(8,12)))
  assertEquals("fmt ",String(b.copyOfRange(12,16)));assertEquals("fact",String(b.copyOfRange(38,42)));assertEquals("data",String(b.copyOfRange(50,54)))
  assertEquals(7,b.leShort(20));assertEquals(1,b.leShort(22));assertEquals(16_000,b.leInt(24))
  assertEquals(16_000,b.leInt(28));assertEquals(1,b.leShort(32));assertEquals(8,b.leShort(34))
  assertEquals(32_000,b.leInt(46));assertEquals(32_000,b.leInt(54));assertEquals(32_050,b.leInt(4))
 }
}
private fun ByteArray.leShort(i:Int)=((this[i].toInt() and 255) or ((this[i+1].toInt() and 255) shl 8))
private fun ByteArray.leInt(i:Int)=leShort(i) or (leShort(i+2) shl 16)
