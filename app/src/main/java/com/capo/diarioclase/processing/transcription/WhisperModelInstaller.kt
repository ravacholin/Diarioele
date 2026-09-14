package com.capo.diarioclase.processing.transcription

import android.content.Context
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ModelMissingException(cause: Throwable? = null) :
    IllegalStateException("No se encontró el modelo local de español", cause)

class ModelInvalidException :
    IllegalStateException("El modelo local no coincide con la versión verificada")

fun interface WhisperModelProvider {
    suspend fun ensureInstalled(): File
}

class WhisperModelInstaller(
    private val context: Context,
) : WhisperModelProvider {
    override suspend fun ensureInstalled(): File = withContext(Dispatchers.IO) {
        val directory = File(context.noBackupFilesDir, MODEL_DIRECTORY)
        check(directory.exists() || directory.mkdirs()) {
            "No se pudo crear la carpeta privada del modelo"
        }

        val destination = File(directory, MODEL_FILE)
        if (destination.isFile && destination.isVerifiedModel()) {
            return@withContext destination
        }
        if (destination.exists() && !destination.delete()) {
            throw ModelInvalidException()
        }

        val partial = File(directory, "$MODEL_FILE.part")
        if (partial.exists() && !partial.delete()) {
            throw ModelInvalidException()
        }

        try {
            copyVerifiedAsset(partial)
            moveAtomically(partial, destination)
            if (!destination.isVerifiedModel()) {
                destination.delete()
                throw ModelInvalidException()
            }
            destination
        } catch (missing: FileNotFoundException) {
            throw ModelMissingException(missing)
        } finally {
            partial.delete()
        }
    }

    private fun copyVerifiedAsset(partial: File) {
        val digest = MessageDigest.getInstance("SHA-256")
        var totalBytes = 0L

        context.assets.open(MODEL_ASSET).use { input ->
            FileOutputStream(partial).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    totalBytes += count
                }
                output.fd.sync()
            }
        }

        val actualSha = digest.digest().toHex()
        if (totalBytes != EXPECTED_SIZE || actualSha != EXPECTED_SHA256) {
            throw ModelInvalidException()
        }
    }

    private fun File.isVerifiedModel(): Boolean {
        if (!isFile || length() != EXPECTED_SIZE) return false
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex() == EXPECTED_SHA256
    }

    private fun moveAtomically(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte) }

    companion object {
        const val MODEL_ASSET = "models/ggml-base.bin"
        const val MODEL_DIRECTORY = "models"
        const val MODEL_FILE = "ggml-base.bin"
        const val EXPECTED_SIZE = 147_951_465L
        const val EXPECTED_SHA256 =
            "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe"
    }
}
