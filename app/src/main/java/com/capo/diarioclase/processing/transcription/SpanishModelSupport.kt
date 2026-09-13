package com.capo.diarioclase.processing.transcription

enum class SpanishModelAvailability { READY, PENDING, DOWNLOADABLE, UNSUPPORTED }

data class SpanishModelChoice(
    val languageTag: String?,
    val availability: SpanishModelAvailability,
)

sealed interface SpanishModelDownloadState {
    data object Checking : SpanishModelDownloadState
    data class Downloading(val languageTag: String, val completedPercent: Int) : SpanishModelDownloadState
    data class Scheduled(val languageTag: String) : SpanishModelDownloadState
    data class Ready(val languageTag: String) : SpanishModelDownloadState
    data object Unsupported : SpanishModelDownloadState
    data class Failed(val errorCode: Int) : SpanishModelDownloadState
}

fun chooseSpanishModel(
    installed: List<String>,
    pending: List<String>,
    supported: List<String>,
): SpanishModelChoice {
    preferredSpanish(installed)?.let { return SpanishModelChoice(it, SpanishModelAvailability.READY) }
    preferredSpanish(pending)?.let { return SpanishModelChoice(it, SpanishModelAvailability.PENDING) }
    preferredSpanish(supported)?.let { return SpanishModelChoice(it, SpanishModelAvailability.DOWNLOADABLE) }
    return SpanishModelChoice(null, SpanishModelAvailability.UNSUPPORTED)
}

private fun preferredSpanish(languages: List<String>): String? {
    val spanish = languages.filter { it.replace('_', '-').startsWith("es", ignoreCase = true) }
    return spanish.firstOrNull { it.replace('_', '-').equals("es-AR", ignoreCase = true) }
        ?: spanish.firstOrNull()
}
