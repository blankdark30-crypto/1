package com.yoran.screentranslate

import kotlin.math.abs

/** Ignore tiny OCR bounding-box jitter. Compare content before scheduling a redraw. */
internal object SubtitleChangePolicy {
    fun hasChanged(
        old: List<VisibleTranslation>,
        next: List<VisibleTranslation>,
        tolerancePx: Int = 6
    ): Boolean {
        if (old.size != next.size) return true
        for (i in old.indices) {
            val a = old[i]
            val b = next[i]
            if (a.original != b.original || a.translated != b.translated ||
                abs(a.left - b.left) > tolerancePx ||
                abs(a.top - b.top) > tolerancePx ||
                abs(a.right - b.right) > tolerancePx ||
                abs(a.bottom - b.bottom) > tolerancePx) return true
        }
        return false
    }
}
