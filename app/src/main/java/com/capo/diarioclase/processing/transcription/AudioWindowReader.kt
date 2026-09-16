package com.capo.diarioclase.processing.transcription

import com.capo.diarioclase.recording.audio.AudioContainer
import java.io.File

fun interface AudioWindowReader {
    fun read(file: File, plan: AudioWindowPlan): FloatArray
}

class FormatAwareAudioWindowReader(
    private val wav: AudioWindowReader,
    private val ogg: AudioWindowReader,
) : AudioWindowReader {
    override fun read(file: File, plan: AudioWindowPlan): FloatArray =
        when (AudioContainer.fromPath(file.path)) {
            AudioContainer.WAV_PCM16 -> wav.read(file, plan)
            AudioContainer.OGG_OPUS -> ogg.read(file, plan)
        }
}
