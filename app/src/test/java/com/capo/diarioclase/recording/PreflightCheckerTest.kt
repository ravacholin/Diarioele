package com.capo.diarioclase.recording
import org.junit.Assert.assertEquals
import org.junit.Test
class PreflightCheckerTest {
 private val checker=PreflightChecker()
 @Test fun `less than one decimal gigabyte blocks start`() {assertEquals(PreflightResult.Blocked(PreflightReason.INSUFFICIENT_SPACE),checker.check(999_999_999,80,true))}
 @Test fun `missing microphone permission blocks start`() {assertEquals(PreflightResult.Blocked(PreflightReason.MICROPHONE_PERMISSION),checker.check(2_000_000_000,80,false))}
 @Test fun `valid conditions are ready`() {assertEquals(PreflightResult.Ready,checker.check(2_000_000_000,80,true))}
}

