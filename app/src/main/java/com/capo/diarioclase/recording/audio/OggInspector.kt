package com.capo.diarioclase.recording.audio

import java.io.File

fun interface EncodedAudioInspector {
    fun inspect(file: File): EncodedAudioInfo
}
