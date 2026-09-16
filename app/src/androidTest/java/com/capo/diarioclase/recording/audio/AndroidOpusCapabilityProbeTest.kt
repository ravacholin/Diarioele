package com.capo.diarioclase.recording.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidOpusCapabilityProbeTest {
    @Test
    fun targetDeviceExposesUsableOpusPath() {
        assertEquals(
            AudioCapability.Supported,
            AndroidOpusCapabilityProbe().opusOggSupport(),
        )
    }
}
