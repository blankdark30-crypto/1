package com.yoran.screentranslate

import org.junit.Assert.*
import org.junit.Test

class SubtitleChangePolicyTest {
    private val sample = VisibleTranslation("Start", "開始", 40, 80, 210, 115)

    @Test fun identicalSubtitleRequiresNoRedraw() {
        assertFalse(SubtitleChangePolicy.hasChanged(listOf(sample), listOf(sample)))
    }

    @Test fun smallOcrPositionJitterDoesNotRedraw() {
        val shifted = sample.copy(left = 44, top = 85)
        assertFalse(SubtitleChangePolicy.hasChanged(listOf(sample), listOf(shifted)))
    }

    @Test fun changedTextImmediatelyUpdates() {
        assertTrue(SubtitleChangePolicy.hasChanged(
            listOf(sample), listOf(sample.copy(translated = "開始冒險"))))
    }

    @Test fun significantMovementAndRemovalUpdates() {
        assertTrue(SubtitleChangePolicy.hasChanged(
            listOf(sample), listOf(sample.copy(top = 118))))
        assertTrue(SubtitleChangePolicy.hasChanged(listOf(sample), emptyList()))
    }
}
