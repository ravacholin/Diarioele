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
 @Test fun `delete reports missing file as failure`()=runTest{
  val store=FileSegmentStore(folder.root)
  assertTrue(store.delete(com.capo.diarioclase.data.db.SegmentId("missing")) is DeleteResult.Failed)
 }
 @Test fun `exists fails when directory cannot be listed`()=runTest{
  val store=FileSegmentStore(folder.root){null}
  try{store.exists(com.capo.diarioclase.data.db.SegmentId("missing"));fail("Expected listing failure")}catch(error:IOException){assertTrue(error.message?.contains("listar") == true)}
 }
}
