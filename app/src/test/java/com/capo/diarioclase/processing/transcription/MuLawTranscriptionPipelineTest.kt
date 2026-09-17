package com.capo.diarioclase.processing.transcription
import com.capo.diarioclase.data.db.BlockId
import com.capo.diarioclase.recording.audio.FileSegmentStore
import com.capo.diarioclase.recording.audio.MuLawCodec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * Verifica la cadena completa que alimenta a Whisper: PCM16 capturado → almacenado en µ-law por
 * [FileSegmentStore] → leído y decodificado por [PcmWindowReader]. El lector debe devolver el
 * mismo audio que Whisper consumía antes (PCM16 normalizado), sin más pérdida que la del round-trip
 * µ-law, de modo que la transcripción local sigue funcionando.
 */
class MuLawTranscriptionPipelineTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `stored mu-law audio decodes back to the pcm whisper expects`() = runTest {
        val source = ShortArray(16_000) { i ->
            (sin(2.0 * PI * 220.0 * i / 16_000.0) * 12_000).toInt().toShort()
        }

        val store = FileSegmentStore(folder.root)
        val open = store.open(BlockId("clase"), 0)
        store.append(open, source, source.size)
        val ready = store.close(open)

        val samples = PcmWindowReader.read(File(ready.path), AudioWindowPlan(0, 0, 1_000, 1_000))

        assertEquals(source.size, samples.size)
        source.forEachIndexed { index, original ->
            val expected = MuLawCodec.decode(MuLawCodec.encode(original)).toFloat() / 32_768f
            assertEquals("muestra $index", expected, samples[index], 0f)
        }
    }
}
