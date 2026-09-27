package com.capo.diarioclase.recording.audio
import com.capo.diarioclase.data.db.BlockId
import com.capo.diarioclase.data.db.SegmentState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
class FileSegmentStoreTest {
 @get:Rule val folder=TemporaryFolder()
 @Test fun `close writes playable duration checksum and ready name`()=runTest{
  val store=FileSegmentStore(folder.root)
  val open=store.open(BlockId("block"),0)
  store.append(open,ShortArray(16_000){10},16_000)
  val ready=store.close(open)
  assertEquals(1_000,ready.durationMs);assertEquals(SegmentState.READY,ready.state);assertTrue(ready.path.endsWith(".ready.wav"));assertEquals(64,ready.sha256.length)
 }
 @Test fun `repair closes partial PCM without touching ready segments`()=runTest{
  val store=FileSegmentStore(folder.root)
  val open=store.open(BlockId("block"),0);store.append(open,ShortArray(8_000){2},8_000)
  val repaired=store.repairOpenSegments().single()
  assertEquals(500,repaired.durationMs);assertEquals(SegmentState.READY,repaired.state)
  assertTrue(store.repairOpenSegments().isEmpty())
 }
 @Test fun `repair keeps legacy PCM16 open segments intact`()=runTest{
  val store=FileSegmentStore(folder.root)
  val legacy=java.io.File(folder.root,"block__0__legacy-id.open.wav")
  val pcm=ByteArray(16_000*2){(it%7).toByte()}
  legacy.writeBytes(WavHeader.forLegacyPcm16(0).encode()+pcm)
  val repaired=store.repairOpenSegments().single()
  assertEquals(1_000,repaired.durationMs)
  val bytes=java.io.File(repaired.path).readBytes()
  assertEquals(44+pcm.size,bytes.size)
  assertArrayEquals(WavHeader.forLegacyPcm16(pcm.size).encode(),bytes.copyOfRange(0,44))
  assertArrayEquals(pcm,bytes.copyOfRange(44,bytes.size))
 }
 @Test fun `append syncs every two seconds of audio instead of every buffer`()=runTest{
  var syncs=0
  val store=FileSegmentStore(folder.root,syncFile={syncs++;it.fd.sync()})
  val open=store.open(BlockId("block"),0)
  // 5 s de audio en bloques de 4096 muestras (~256 ms): antes eran 20 sincronizaciones.
  repeat(20){store.append(open,ShortArray(4096){(it%50).toShort()},4096)}
  assertEquals(2,syncs)
  val ready=store.close(open)
  assertEquals(3,syncs)
  assertEquals(20L*4096*1_000/16_000,ready.durationMs)
  assertEquals(WAV_HEADER_BYTES+20L*4096,java.io.File(ready.path).length())
 }
 @Test fun `audio appended without close survives repair`()=runTest{
  val store=FileSegmentStore(folder.root,syncFile={})
  val open=store.open(BlockId("block"),0)
  store.append(open,ShortArray(4_000){3},4_000);store.append(open,ShortArray(4_000){3},4_000)
  val repaired=FileSegmentStore(folder.root).repairOpenSegments().single()
  assertEquals(500,repaired.durationMs)
 }
 @Test fun `delete reports missing file as failure`()=runTest{
  val store=FileSegmentStore(folder.root)
  assertTrue(store.delete(com.capo.diarioclase.data.db.SegmentId("missing")) is DeleteResult.Failed)
 }
 @Test fun `exists fails when directory cannot be listed`()=runTest{
  val store=FileSegmentStore(folder.root){null}
  try{store.exists(com.capo.diarioclase.data.db.SegmentId("missing"));fail("Expected listing failure")}catch(error:IOException){assertTrue(error.message?.contains("listar") == true)}
 }
}
