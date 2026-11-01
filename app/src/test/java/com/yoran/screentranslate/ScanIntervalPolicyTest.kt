package com.yoran.screentranslate

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanIntervalPolicyTest {
    @Test fun targetsOneSecondFromPriorStart() {
        assertEquals(1000L, ScanIntervalPolicy.nextDelay(100L,100L,0))
        assertEquals(120L, ScanIntervalPolicy.nextDelay(100L,5000L,0))
    }
    @Test fun graduallyBacksOffOnScreenshotFailures() {
        assertEquals(2000L, ScanIntervalPolicy.nextDelay(0L,0L,1))
        assertEquals(4000L, ScanIntervalPolicy.nextDelay(0L,0L,2))
        assertEquals(8000L, ScanIntervalPolicy.nextDelay(0L,0L,3))
    }
}