package com.capo.diarioclase.recording.audio

class WavHeader private constructor(private val dataBytes: Int) {
    fun encode(): ByteArray = ByteArray(44).also { out ->
        out.putAscii(0, "RIFF"); out.putIntLe(4, 36 + dataBytes); out.putAscii(8, "WAVE")
        out.putAscii(12, "fmt "); out.putIntLe(16, 16); out.putShortLe(20, 1)
        out.putShortLe(22, CHANNELS); out.putIntLe(24, SAMPLE_RATE)
        out.putIntLe(28, SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8)
        out.putShortLe(32, CHANNELS * BITS_PER_SAMPLE / 8); out.putShortLe(34, BITS_PER_SAMPLE)
        out.putAscii(36, "data"); out.putIntLe(40, dataBytes)
    }
    companion object { fun forPcm(dataBytes: Int) = WavHeader(dataBytes.coerceAtLeast(0)) }
}
const val SAMPLE_RATE = 16_000
const val CHANNELS = 1
const val BITS_PER_SAMPLE = 16
const val WAV_HEADER_BYTES = 44L
private fun ByteArray.putAscii(i:Int,s:String)=s.forEachIndexed{x,c->this[i+x]=c.code.toByte()}
private fun ByteArray.putShortLe(i:Int,v:Int){this[i]=v.toByte();this[i+1]=(v ushr 8).toByte()}
private fun ByteArray.putIntLe(i:Int,v:Int){this[i]=v.toByte();this[i+1]=(v ushr 8).toByte();this[i+2]=(v ushr 16).toByte();this[i+3]=(v ushr 24).toByte()}
