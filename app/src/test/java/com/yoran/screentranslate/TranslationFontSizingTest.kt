package com.yoran.screentranslate

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationFontSizingTest {
    @Test fun exactlyTwoSpSmallerThanPreviousSizing() {
        for (height in listOf(8, 20, 28, 45, 120)) {
            for (scaled in listOf(1f, 1.25f, 1.75f)) {
                val before = (height * 0.85f).coerceIn(13f * scaled, 22f * scaled)
                assertEquals(before - 2f * scaled,
                    TranslationFontSizing.sizePx(height, scaled), 0.0001f)
            }
        }
    }

    @Test fun respectsBothNewBounds() {
        assertEquals(11f, TranslationFontSizing.sizePx(2, 1f), 0f)
        assertEquals(20f, TranslationFontSizing.sizePx(999, 1f), 0f)
    }
}
