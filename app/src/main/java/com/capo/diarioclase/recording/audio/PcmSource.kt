package com.capo.diarioclase.recording.audio
import java.io.Closeable
interface PcmSource:Closeable { suspend fun read(target:ShortArray):Int }

