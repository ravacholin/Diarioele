package com.capo.diarioclase.recording.audio

import com.capo.diarioclase.data.db.BlockId
import com.capo.diarioclase.data.db.SegmentId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

class OpusSegmentStore(
    private val root: File,
    private val encoderFactory: StreamingAudioEncoderFactory,
    private val inspector: EncodedAudioInspector,
    private val config: AudioEncodingConfig = AudioEncodingConfig(),
    private val listFiles: () -> Array<File>? = { root.listFiles() },
) : SegmentStore, CleanupFileStore {
    private val encoders = mutableMapOf<SegmentId, StreamingAudioEncoder>()

    init {
        root.mkdirs()
    }

    override suspend fun open(blockId: BlockId, ordinal: Int): OpenSegment =
        withContext(Dispatchers.IO) {
            val id = SegmentId(UUID.randomUUID().toString())
            val safeBlockId = blockId.value.replace(Regex("[^A-Za-z0-9-]"), "_")
            val file = File(root, "${safeBlockId}__${ordinal}__${id.value}.open.ogg")
            val encoder = encoderFactory.create(file, config)
            encoders[id] = encoder
            OpenSegment(id, blockId, file.absolutePath)
        }

    override suspend fun append(segment: OpenSegment, pcm: ShortArray, count: Int) =
        withContext(Dispatchers.IO) {
            require(count in 0..pcm.size)
            encoderFor(segment).append(pcm, count)
        }

    override suspend fun close(segment: OpenSegment): ReadySegment =
        withContext(Dispatchers.IO) {
            val encoder = encoderFor(segment)
            try {
                encoder.finish()
            } finally {
                encoders.remove(segment.id)
                encoder.close()
            }

            val openFile = File(segment.path)
            sync(openFile)
            val inspected = inspector.inspect(openFile)
            promote(openFile, segment.id, segment.blockId, inspected)
        }

    override suspend fun abort(segment: OpenSegment) =
        withContext(Dispatchers.IO) {
            encoders.remove(segment.id)?.close()
        }

    override suspend fun repairOpenSegments(): List<ReadySegment> =
        withContext(Dispatchers.IO) {
            val files = listedFiles()
            val repaired = files
                .filter { it.name.endsWith(".open.ogg") }
                .sortedBy { it.name }
                .mapNotNull { file -> repair(file) }
            val alreadyReady = files
                .filter { it.name.endsWith(".ready.ogg") }
                .sortedBy { it.name }
                .mapNotNull { file -> inspectReady(file) }
            repaired + alreadyReady
        }

    override suspend fun delete(segmentId: SegmentId): DeleteResult =
        withContext(Dispatchers.IO) {
            val file = listedFiles().firstOrNull {
                it.name.endsWith("__${segmentId.value}.ready.ogg")
            }
            when {
                file == null -> DeleteResult.Failed("Archivo inexistente")
                file.delete() -> DeleteResult.Deleted
                else -> DeleteResult.Failed("No se pudo borrar")
            }
        }

    override suspend fun exists(segmentId: SegmentId): Boolean =
        withContext(Dispatchers.IO) {
            listedFiles().any { it.name.endsWith("__${segmentId.value}.ready.ogg") }
        }

    private fun encoderFor(segment: OpenSegment): StreamingAudioEncoder =
        encoders[segment.id]
            ?: throw IllegalStateException("El segmento no tiene un encoder activo")

    private fun repair(file: File): ReadySegment? {
        val identity = parseIdentity(file) ?: return null
        return try {
            val inspected = inspector.inspect(file)
            promote(file, identity.id, identity.blockId, inspected)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }
    }

    private fun inspectReady(file: File): ReadySegment? {
        val identity = parseIdentity(file) ?: return null
        return try {
            val inspected = inspector.inspect(file)
            ReadySegment(
                id = identity.id,
                blockId = identity.blockId,
                path = file.absolutePath,
                durationMs = inspected.durationMs,
                sha256 = sha256(file),
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        }
    }

    private fun promote(
        openFile: File,
        id: SegmentId,
        blockId: BlockId,
        info: EncodedAudioInfo,
    ): ReadySegment {
        val readyFile = File(
            openFile.parentFile,
            openFile.name.removeSuffix(".open.ogg") + ".ready.ogg",
        )
        try {
            Files.move(
                openFile.toPath(),
                readyFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(openFile.toPath(), readyFile.toPath())
        }
        return ReadySegment(
            id = id,
            blockId = blockId,
            path = readyFile.absolutePath,
            durationMs = info.durationMs,
            sha256 = sha256(readyFile),
        )
    }

    private fun parseIdentity(file: File): SegmentIdentity? {
        val stem = file.name
            .removeSuffix(".open.ogg")
            .removeSuffix(".ready.ogg")
        val parts = stem.split("__")
        if (parts.size < 3) return null
        return SegmentIdentity(
            id = SegmentId(parts.last()),
            blockId = BlockId(parts.dropLast(2).joinToString("__")),
        )
    }

    private fun listedFiles(): Array<File> =
        listFiles() ?: throw IOException("No se pudo listar el audio temporal")

    private fun sync(file: File) {
        RandomAccessFile(file, "rw").use { it.fd.sync() }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class SegmentIdentity(
        val id: SegmentId,
        val blockId: BlockId,
    )
}
