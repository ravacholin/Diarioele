# OGG/Opus Direct Recording Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace new temporary WAV recordings with direct OGG/Opus recording while preserving local Whisper transcription, crash recovery, legacy WAV support and verified cleanup.

**Architecture:** Keep `AudioRecord` as the PCM source, encode each buffer immediately through Android `MediaCodec` into an OGG `MediaMuxer`, and rotate one-minute files. Introduce format-aware window readers so Whisper still receives mono 16 kHz `FloatArray` windows, with OGG decoded only in memory and legacy WAV handled by the existing reader.

**Tech Stack:** Kotlin, Android API 29+, `AudioRecord`, `MediaCodec`, `MediaMuxer`, `MediaExtractor`, whisper.cpp JNI, coroutines, JUnit, Robolectric, Android instrumented tests.

**Spec:** `docs/superpowers/specs/2026-09-16-ogg-opus-recording-design.md`

## Global Constraints

- Minimum supported Android version is Android 10, API 29.
- New recordings use OGG/Opus mono at 40 kbit/s.
- Capture remains PCM16 mono at 16 kHz and is encoded immediately; no PCM or WAV intermediate file is permitted.
- Recording segments rotate every 60 seconds.
- Whisper windows remain 30 seconds with 2 seconds overlap.
- Whisper input remains mono 16 kHz normalized `FloatArray` PCM.
- Existing `.ready.wav` files remain readable and removable.
- Audio never leaves the device.
- FFmpeg and new native codec libraries are prohibited.
- No recovery, encoding or transcription failure may delete or overwrite source audio.
- JVM tests use fakes and fixtures; CI does not use microphone hardware or network access.
- Files reserved by the active Phase 5 integration are modified only in the final wiring task after coordination.

---

### Task O1: Introduce format-aware audio contracts

**Files:**
- Create: `app/src/main/java/com/capo/diarioclase/recording/audio/AudioContainer.kt`
- Create: `app/src/main/java/com/capo/diarioclase/processing/transcription/AudioWindowReader.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/processing/transcription/AudioWindowing.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/processing/work/TranscriptionCoordinator.kt`
- Create: `app/src/test/java/com/capo/diarioclase/recording/audio/AudioContainerTest.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/transcription/FormatAwareAudioWindowReaderTest.kt`
- Modify: `app/src/test/java/com/capo/diarioclase/processing/transcription/AudioWindowingTest.kt`
- Modify: `app/src/test/java/com/capo/diarioclase/processing/work/TranscriptionCoordinatorTest.kt`

**Interfaces:**
- Produces: `AudioContainer.fromPath(path): AudioContainer`, `AudioWindowReader.read(file, plan): FloatArray`, `FormatAwareAudioWindowReader`.
- Preserves: `PcmWindowReader.read(file, plan)` as the WAV implementation and the current `TranscriptionCoordinator` test injection point.

- [ ] **Step 1: Write failing container tests**

```kotlin
@Test fun detects_controlled_ready_and_open_extensions() {
    assertEquals(AudioContainer.WAV_PCM16, AudioContainer.fromPath("a.ready.wav"))
    assertEquals(AudioContainer.OGG_OPUS, AudioContainer.fromPath("a.open.ogg"))
}

@Test fun rejects_unknown_audio_extension() {
    assertFailsWith<IllegalArgumentException> { AudioContainer.fromPath("a.mp3") }
}
```

- [ ] **Step 2: Run the tests and verify RED**

Run:

```bash
./gradlew testDebugUnitTest --tests '*AudioContainerTest'
```

Expected: FAIL because `AudioContainer` does not exist.

- [ ] **Step 3: Implement the minimal format contract**

```kotlin
enum class AudioContainer {
    WAV_PCM16,
    OGG_OPUS;

    companion object {
        fun fromPath(path: String): AudioContainer = when {
            path.endsWith(".wav", ignoreCase = true) -> WAV_PCM16
            path.endsWith(".ogg", ignoreCase = true) -> OGG_OPUS
            else -> throw IllegalArgumentException("Formato de audio temporal no compatible")
        }
    }
}
```

- [ ] **Step 4: Write the failing routing test**

```kotlin
@Test fun routes_legacy_wav_and_new_ogg_to_their_reader() {
    val wav = RecordingReader(FloatArray(1) { 1f })
    val ogg = RecordingReader(FloatArray(1) { 2f })
    val reader = FormatAwareAudioWindowReader(wav, ogg)

    assertArrayEquals(floatArrayOf(1f), reader.read(File("a.ready.wav"), plan), 0f)
    assertArrayEquals(floatArrayOf(2f), reader.read(File("a.ready.ogg"), plan), 0f)
    assertEquals(1, wav.calls)
    assertEquals(1, ogg.calls)
}
```

- [ ] **Step 5: Run the routing test and verify RED**

Run:

```bash
./gradlew testDebugUnitTest --tests '*FormatAwareAudioWindowReaderTest'
```

Expected: FAIL because the reader interfaces do not exist.

- [ ] **Step 6: Implement reader routing and adapt the coordinator**

```kotlin
fun interface AudioWindowReader {
    fun read(file: File, plan: AudioWindowPlan): FloatArray
}

class FormatAwareAudioWindowReader(
    private val wav: AudioWindowReader,
    private val ogg: AudioWindowReader,
) : AudioWindowReader {
    override fun read(file: File, plan: AudioWindowPlan) = when (AudioContainer.fromPath(file.name)) {
        AudioContainer.WAV_PCM16 -> wav.read(file, plan)
        AudioContainer.OGG_OPUS -> ogg.read(file, plan)
    }
}
```

Make `PcmWindowReader` implement `AudioWindowReader`. Change the coordinator constructor to receive one `AudioWindowReader`; preserve a lambda-compatible secondary test constructor only if existing tests require it.

- [ ] **Step 7: Run focused and coordinator tests**

```bash
./gradlew testDebugUnitTest --tests '*AudioContainerTest' --tests '*FormatAwareAudioWindowReaderTest' --tests '*AudioWindowingTest' --tests '*TranscriptionCoordinatorTest'
```

Expected: PASS with WAV behavior unchanged.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase/recording/audio app/src/main/java/com/capo/diarioclase/processing app/src/test/java/com/capo/diarioclase/recording/audio app/src/test/java/com/capo/diarioclase/processing
git commit -m "refactor: route transcription by audio container"
```

### Task O2: Define and probe Android Opus capability

**Files:**
- Create: `app/src/main/java/com/capo/diarioclase/recording/audio/AudioCapability.kt`
- Create: `app/src/main/java/com/capo/diarioclase/recording/audio/AndroidOpusCapabilityProbe.kt`
- Create: `app/src/test/java/com/capo/diarioclase/recording/audio/AudioCapabilityTest.kt`
- Create: `app/src/androidTest/java/com/capo/diarioclase/recording/audio/AndroidOpusCapabilityProbeTest.kt`

**Interfaces:**
- Consumes: `MediaCodecList`, `MediaFormat.MIMETYPE_AUDIO_OPUS`, API level.
- Produces: `AudioCapabilityProbe.opusOggSupport(): AudioCapability` with typed unsupported reasons.

- [ ] **Step 1: Write failing capability decision tests**

```kotlin
@Test fun requires_api_encoder_and_decoder() {
    assertEquals(Unsupported(API_TOO_OLD), decide(api = 28, encoder = true, decoder = true))
    assertEquals(Unsupported(OPUS_ENCODER_MISSING), decide(api = 35, encoder = false, decoder = true))
    assertEquals(Unsupported(OPUS_DECODER_MISSING), decide(api = 35, encoder = true, decoder = false))
    assertEquals(Supported, decide(api = 35, encoder = true, decoder = true))
}
```

- [ ] **Step 2: Verify RED**

```bash
./gradlew testDebugUnitTest --tests '*AudioCapabilityTest'
```

Expected: FAIL because the decision model does not exist.

- [ ] **Step 3: Implement pure decision logic and Android probe**

Use `MediaCodecList(MediaCodecList.ALL_CODECS)` and codec capabilities for `audio/opus`. The probe must not instantiate the microphone and must return a typed reason rather than throw.

- [ ] **Step 4: Add the device instrumentation test**

```kotlin
@Test fun target_device_exposes_usable_opus_path() {
    assertEquals(AudioCapability.Supported, AndroidOpusCapabilityProbe().opusOggSupport())
}
```

- [ ] **Step 5: Run JVM tests and compile instrumentation tests**

```bash
./gradlew testDebugUnitTest --tests '*AudioCapabilityTest' assembleDebugAndroidTest
```

Expected: JVM PASS and instrumentation APK compiles. Physical execution remains required on Moto g max.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase/recording/audio app/src/test app/src/androidTest
git commit -m "feat: probe native OGG Opus capability"
```

### Task O3: Encode PCM directly into recoverable OGG segments

**Files:**
- Create: `app/src/main/java/com/capo/diarioclase/recording/audio/StreamingAudioEncoder.kt`
- Create: `app/src/main/java/com/capo/diarioclase/recording/audio/AndroidOpusEncoder.kt`
- Create: `app/src/main/java/com/capo/diarioclase/recording/audio/OpusSegmentStore.kt`
- Create: `app/src/main/java/com/capo/diarioclase/recording/audio/OggInspector.kt`
- Create: `app/src/test/java/com/capo/diarioclase/recording/audio/OpusSegmentStoreTest.kt`
- Create: `app/src/test/java/com/capo/diarioclase/recording/audio/OggRecoveryTest.kt`
- Create: `app/src/androidTest/java/com/capo/diarioclase/recording/audio/AndroidOpusEncoderTest.kt`

**Interfaces:**
- Consumes: PCM16 mono 16 kHz blocks from `RecordingCoordinator`.
- Produces: `OpusSegmentStore : SegmentStore, CleanupFileStore`, `.open.ogg`, validated `.ready.ogg`, `EncodedAudioInfo`.

- [ ] **Step 1: Write failing store lifecycle tests with a fake encoder**

```kotlin
@Test fun close_promotes_only_a_valid_encoded_segment() = runTest {
    val encoder = FakeEncoder(info = EncodedAudioInfo(1_000, 16_000, 1, "audio/opus"))
    val store = OpusSegmentStore(root, encoderFactory = { _, _ -> encoder }, inspector = ValidInspector)
    val open = store.open(BlockId("b"), 0)
    store.append(open, ShortArray(16_000), 16_000)
    val ready = store.close(open)

    assertTrue(open.path.endsWith(".open.ogg"))
    assertTrue(ready.path.endsWith(".ready.ogg"))
    assertEquals(1_000, ready.durationMs)
}

@Test fun failed_validation_keeps_open_source() = runTest {
    val store = store(inspector = InvalidInspector)
    val open = store.open(BlockId("b"), 0)
    assertFailsWith<IllegalStateException> { store.close(open) }
    assertTrue(File(open.path).exists())
    assertFalse(root.listFiles().orEmpty().any { it.name.endsWith(".ready.ogg") })
}
```

- [ ] **Step 2: Verify RED**

```bash
./gradlew testDebugUnitTest --tests '*OpusSegmentStoreTest' --tests '*OggRecoveryTest'
```

Expected: FAIL because the encoder and store do not exist.

- [ ] **Step 3: Implement the fake-driven store**

Keep one encoder instance keyed by `SegmentId`. `append` forwards buffers without persisting PCM. `close` calls `finish`, syncs the file descriptor, inspects the OGG, atomically renames it and hashes the final bytes. `abort` keeps `.open.ogg`.

- [ ] **Step 4: Implement `AndroidOpusEncoder`**

Configure:

```kotlin
MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, 16_000, 1).apply {
    setInteger(MediaFormat.KEY_BIT_RATE, 40_000)
    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8_192)
}
```

Feed little-endian PCM16 into encoder input buffers with monotonic presentation timestamps derived from the accepted sample count. Add the muxer track only after `INFO_OUTPUT_FORMAT_CHANGED`. On finish, queue EOS, drain EOS, stop and release muxer and codec in `finally` blocks.

- [ ] **Step 5: Add real codec round-trip instrumentation**

Generate a deterministic 3-second two-tone PCM fixture, encode it, inspect the OGG track and assert duration between 2.9 and 3.1 seconds, MIME `audio/opus`, one channel and increasing sample timestamps.

- [ ] **Step 6: Run unit tests and compile device tests**

```bash
./gradlew testDebugUnitTest --tests '*OpusSegmentStoreTest' --tests '*OggRecoveryTest'
./gradlew assembleDebugAndroidTest
```

Expected: PASS and instrumentation APK compiles.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase/recording/audio app/src/test/java/com/capo/diarioclase/recording/audio app/src/androidTest/java/com/capo/diarioclase/recording/audio
git commit -m "feat: record PCM directly as OGG Opus"
```

### Task O4: Decode and normalize OGG windows for Whisper

**Files:**
- Create: `app/src/main/java/com/capo/diarioclase/processing/transcription/PcmNormalizer.kt`
- Create: `app/src/main/java/com/capo/diarioclase/processing/transcription/AndroidOpusWindowReader.kt`
- Create: `app/src/test/java/com/capo/diarioclase/processing/transcription/PcmNormalizerTest.kt`
- Create: `app/src/androidTest/java/com/capo/diarioclase/processing/transcription/AndroidOpusWindowReaderTest.kt`

**Interfaces:**
- Consumes: a validated OGG/Opus file and `AudioWindowPlan`.
- Produces: exactly bounded mono 16 kHz normalized `FloatArray` samples.

- [ ] **Step 1: Write failing pure PCM normalization tests**

```kotlin
@Test fun downmixes_stereo_without_clipping() {
    assertArrayEquals(shortArrayOf(0, 10_000), normalizer.toMono(shortArrayOf(10_000, -10_000, 20_000, 0), 2))
}

@Test fun resamples_48khz_to_16khz_with_exact_duration() {
    val input = ShortArray(48_000) { sine(it, 48_000, 440.0) }
    val output = normalizer.resampleMono(input, 48_000, 16_000)
    assertEquals(16_000, output.size)
}
```

- [ ] **Step 2: Verify RED**

```bash
./gradlew testDebugUnitTest --tests '*PcmNormalizerTest'
```

Expected: FAIL because `PcmNormalizer` does not exist.

- [ ] **Step 3: Implement deterministic downmix and resampling**

Use saturating integer downmix and a windowed-sinc or tested band-limited resampler. Do not use nearest-neighbor decimation. Convert to floats only after channel and sample-rate normalization.

- [ ] **Step 4: Implement the Android OGG reader**

Select the `audio/opus` track using `MediaExtractor`, seek to the closest previous sync point, configure a decoder from the track format and discard decoded frames before `plan.startMs`. Stop after `plan.endMs`; inspect the decoder output format for actual rate and channel count before normalization.

- [ ] **Step 5: Add window round-trip instrumentation**

Use the OGG fixture produced by Task O3. Assert correct sizes and no discontinuity beyond tolerance for windows 0-30 seconds, 28-58 seconds and the final partial window.

- [ ] **Step 6: Run unit tests and compile device tests**

```bash
./gradlew testDebugUnitTest --tests '*PcmNormalizerTest' --tests '*AudioWindowingTest'
./gradlew assembleDebugAndroidTest
```

Expected: PASS and instrumentation APK compiles.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase/processing/transcription app/src/test/java/com/capo/diarioclase/processing/transcription app/src/androidTest/java/com/capo/diarioclase/processing/transcription
git commit -m "feat: decode OGG Opus windows for Whisper"
```

### Task O5: Rotate one-minute segments and preserve recovery

**Files:**
- Modify: `app/src/main/java/com/capo/diarioclase/recording/service/RecordingCoordinator.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/recording/recovery/RecordingRecovery.kt`
- Modify: `app/src/test/java/com/capo/diarioclase/recording/service/RecordingCoordinatorTest.kt`
- Modify: `app/src/test/java/com/capo/diarioclase/recording/recovery/RecordingRecoveryTest.kt`
- Modify: `app/src/test/java/com/capo/diarioclase/FullJourneyTest.kt`

**Interfaces:**
- Consumes: `OpusSegmentStore.repairOpenSegments()`.
- Produces: exact 60-second rotation, recoverable ready segments, preserved unrecoverable sources.

- [ ] **Step 1: Write the failing rotation regression**

```kotlin
@Test fun rotates_at_one_minute_without_losing_remainder() = runTest {
    val store = MemorySegments()
    RecordingCoordinator(
        FakeSessions(), store, { FiniteSource(16_000 * 61) }, Clock { 0 }, this,
        maxSegmentSamples = 16_000 * 60,
    ).recordBlock(BlockId("b"), FiniteSource(16_000 * 61))

    assertEquals(listOf(60_000L, 1_000L), store.closedDurations)
}
```

- [ ] **Step 2: Verify RED**

```bash
./gradlew testDebugUnitTest --tests '*RecordingCoordinatorTest.rotates_at_one_minute_without_losing_remainder'
```

Expected: FAIL because segment size is fixed at 180 seconds.

- [ ] **Step 3: Inject and set the segment duration**

Add `maxSegmentSamples` with a default preserving source compatibility, validate that it is positive, and pass `16_000 * 60` from production wiring.

- [ ] **Step 4: Add recovery preservation tests**

Assert that valid `.open.ogg` becomes `.ready.ogg`, invalid `.open.ogg` remains byte-for-byte unchanged, and already ready segments are never reopened or rewritten.

- [ ] **Step 5: Run recording and journey tests**

```bash
./gradlew testDebugUnitTest --tests '*RecordingCoordinatorTest' --tests '*RecordingRecoveryTest' --tests '*FullJourneyTest'
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/capo/diarioclase/recording app/src/test/java/com/capo/diarioclase/recording app/src/test/java/com/capo/diarioclase/FullJourneyTest.kt
git commit -m "feat: rotate and recover one minute Opus segments"
```

### Task O6: Wire Opus production recording and legacy cleanup

**Coordination gate:** `app/build.gradle.kts` is reserved by Phase 5 integration tasks. Rebase onto the latest green `feature/phase5-2-quality` and obtain integration ownership before this task.

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/com/capo/diarioclase/DiarioClaseApp.kt`
- Modify: `app/src/main/java/com/capo/diarioclase/recording/service/RecordingService.kt`
- Create: `app/src/main/java/com/capo/diarioclase/recording/audio/TemporaryAudioFiles.kt`
- Modify: `app/src/test/java/com/capo/diarioclase/FullJourneyTest.kt`
- Create: `app/src/test/java/com/capo/diarioclase/recording/audio/TemporaryAudioFilesTest.kt`

**Interfaces:**
- Consumes: Tasks O1-O5.
- Produces: one production factory shared by app startup, foreground recording and cleanup.

- [ ] **Step 1: Write failing mixed-cleanup tests**

Create one `.ready.wav` and one `.ready.ogg` with different ids. Assert `exists` and `delete` locate only the requested id, and no open or unrelated file is removed.

- [ ] **Step 2: Verify RED**

```bash
./gradlew testDebugUnitTest --tests '*TemporaryAudioFilesTest'
```

Expected: FAIL because cleanup currently searches only `.ready.wav`.

- [ ] **Step 3: Implement shared file lookup and production factory**

`TemporaryAudioFiles` recognizes `.open.wav`, `.ready.wav`, `.open.ogg` and `.ready.ogg`. Both `DiarioClaseApp` and `RecordingService` receive the same `RecordingComponentFactory`; do not construct separate `FileSegmentStore` instances in two locations.

- [ ] **Step 4: Wire readers and writer**

Production uses:

```text
OpusSegmentStore(AndroidOpusEncoderFactory, AndroidOggInspector)
FormatAwareAudioWindowReader(PcmWindowReader, AndroidOpusWindowReader)
RecordingCoordinator(maxSegmentSamples = 16_000 * 60)
```

Set `minSdk = 29`. Run the capability probe before starting a recording and surface the typed error through the existing recording failure state.

- [ ] **Step 5: Run complete local verification**

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest --max-workers=2 -Dorg.gradle.jvmargs="-Xmx4g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8"
```

Expected: exit 0.

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts app/src/main app/src/test app/src/androidTest
git commit -m "feat: enable direct OGG Opus recording"
```

### Task O7: Validate on Moto g max and prepare integration

**Files:**
- Create: `OPUS_RECORDING_DEVICE_TEST.md`
- Modify: `PHASE5_HANDOFF.md` only under integrator ownership.

**Interfaces:**
- Consumes: debug APK from O6 and a WAV/Opus paired classroom fixture.
- Produces: measured device report and integration checkpoint.

- [ ] **Step 1: Build and inspect the APK**

```bash
./gradlew assembleDebug
unzip -l app/build/outputs/apk/debug/app-debug.apk | rg 'libdiarioclase_whisper.so|ggml-base.bin'
```

- [ ] **Step 2: Run the physical protocol**

On Moto g max record 90, 60 and 50 minute blocks with the screen locked. Record initial/final battery percentage, temperature, total OGG bytes, segment count, repair result after kill/restart, transcription duration and final extracted fields.

- [ ] **Step 3: Compare transcription quality**

Run the paired fixture through WAV and Opus 32/40/48 kbit/s. Record differences in words, numbers, page/exercise associations, homework and proper names. Keep 40 kbit/s unless 48 materially fixes an observed error.

- [ ] **Step 4: Verify cleanup and privacy**

Approve the diary, confirm all WAV/OGG temporary files and rows are gone, and scan the repository and APK inputs for credentials. Audio must remain local throughout.

- [ ] **Step 5: Update handoff and commit**

Record APK SHA-256, branch SHA, commands, measurements, failures and chosen bitrate. Do not declare the migration complete until device results are present.

```bash
git add OPUS_RECORDING_DEVICE_TEST.md PHASE5_HANDOFF.md
git commit -m "docs: record Moto g max Opus validation"
```

## Current execution note

On 2026-09-16 the baseline command could not start because Gradle 8.13 was not cached and the execution network could not reach `services.gradle.org`. This is an environment dependency failure, not a passing or failing project test. Resume TDD at Task O1 Step 2 as soon as the Gradle distribution is available; do not write production code before observing the intended RED result.
