package com.yoran.screentranslate

/** A best-effort cadence, never a promise that image processing takes only one second. */
internal object ScanIntervalPolicy {
    const val TARGET_INTERVAL_MS = 1000L
    const val MAX_BACKOFF_MS = 12000L

    fun nextDelay(startedAtMs: Long, nowMs: Long, failures: Int): Long {
        val base = if (failures <= 0) TARGET_INTERVAL_MS
        else (TARGET_INTERVAL_MS * (1L shl failures.coerceIn(0, 3))).coerceAtMost(MAX_BACKOFF_MS)
        return (base - (nowMs - startedAtMs)).coerceAtLeast(120L)
    }
}
