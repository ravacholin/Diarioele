package com.capo.diarioclase.recording.audio

import com.capo.diarioclase.data.db.SegmentId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class TemporaryAudioFiles(
    private val root: File,
    private val listFiles: () -> Array<File>? = { root.listFiles() },
) : CleanupFileStore {
    init {
        root.mkdirs()
    }

    override suspend fun exists(segmentId: SegmentId): Boolean =
        withContext(Dispatchers.IO) {
            readyFiles(segmentId).isNotEmpty()
        }

    override suspend fun delete(segmentId: SegmentId): DeleteResult =
        withContext(Dispatchers.IO) {
            val targets = readyFiles(segmentId)
            when {
                targets.isEmpty() -> DeleteResult.Failed("Archivo inexistente")
                targets.all(File::delete) -> DeleteResult.Deleted
                else -> DeleteResult.Failed("No se pudo borrar")
            }
        }

    private fun readyFiles(segmentId: SegmentId): List<File> =
        listedFiles().filter { file ->
            READY_SUFFIXES.any { suffix ->
                file.name.endsWith("__${segmentId.value}$suffix")
            }
        }

    private fun listedFiles(): Array<File> =
        listFiles() ?: throw IOException("No se pudo listar el audio temporal")

    private companion object {
        val READY_SUFFIXES = listOf(".ready.wav", ".ready.ogg")
    }
}
