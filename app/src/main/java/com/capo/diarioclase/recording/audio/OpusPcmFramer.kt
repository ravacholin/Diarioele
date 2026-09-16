package com.capo.diarioclase.recording.audio

/** Reframes arbitrary AudioRecord reads into Opus' fixed 20 ms PCM frames. */
internal class OpusPcmFramer(sampleRate: Int) {
    private val frameSamples: Int
    private val pending: ShortArray
    private var pendingCount = 0
    private var finished = false

    init {
        require(sampleRate > 0 && sampleRate % FRAMES_PER_SECOND == 0) {
            "La frecuencia no permite cuadros Opus de 20 ms"
        }
        frameSamples = sampleRate / FRAMES_PER_SECOND
        pending = ShortArray(frameSamples)
    }

    fun append(
        pcm: ShortArray,
        offset: Int,
        count: Int,
        emit: (ShortArray) -> Unit,
    ) {
        check(!finished) { "El flujo PCM ya terminó" }
        require(offset >= 0 && count >= 0 && offset + count <= pcm.size)

        var sourceOffset = offset
        var remaining = count
        while (remaining > 0) {
            val copied = minOf(remaining, frameSamples - pendingCount)
            pcm.copyInto(
                destination = pending,
                destinationOffset = pendingCount,
                startIndex = sourceOffset,
                endIndex = sourceOffset + copied,
            )
            pendingCount += copied
            sourceOffset += copied
            remaining -= copied
            if (pendingCount == frameSamples) {
                emit(pending)
                pendingCount = 0
            }
        }
    }

    fun finish(emit: (ShortArray) -> Unit) {
        if (finished) return
        finished = true
        if (pendingCount == 0) return
        pending.fill(0, fromIndex = pendingCount)
        emit(pending)
        pendingCount = 0
    }

    private companion object {
        const val FRAMES_PER_SECOND = 50
    }
}
