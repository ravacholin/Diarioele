package com.capo.diarioclase.processing.transcription

import java.text.Normalizer
import java.util.Locale

object TranscriptDeduplicator {
    fun merge(
        existing: List<TranscriptSpan>,
        incoming: List<TranscriptSpan>,
    ): List<TranscriptSpan> {
        if (existing.isEmpty()) return incoming.sortedWith(spanOrder)
        if (incoming.isEmpty()) return existing.sortedWith(spanOrder)

        val confirmedBoundaryMs = existing.maxOf { it.endMs }
        val merged = existing.sortedWith(spanOrder).toMutableList()

        incoming.sortedWith(spanOrder).forEach { candidate ->
            if (candidate.endMs <= confirmedBoundaryMs) return@forEach

            val candidateText = candidate.text.normalizedForOverlap()
            val repeatsOverlap = candidate.startMs < confirmedBoundaryMs &&
                candidateText.isNotEmpty() &&
                existing.any { prior ->
                    prior.endMs > candidate.startMs &&
                        prior.text.normalizedForOverlap() == candidateText
                }

            if (!repeatsOverlap) merged += candidate
        }
        return merged.sortedWith(spanOrder)
    }

    private val spanOrder = compareBy<TranscriptSpan>(
        { it.startMs },
        { it.endMs },
        { it.id },
    )

    private fun String.normalizedForOverlap(): String =
        Normalizer.normalize(this, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9ñü]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
}
