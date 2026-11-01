package com.yoran.screentranslate

import org.junit.Assert.*
import org.junit.Test

class LanguageOptionsTest {
    @Test fun threeSupportedSources() {
        assertEquals(listOf("en", "ko", "ja"), LanguageOptions.codes)
        assertEquals(3, LanguageOptions.labels.size)
    }
    @Test fun selectionMapsToExactSource() {
        assertEquals("en", LanguageOptions.at(0))
        assertEquals("ko", LanguageOptions.at(1))
        assertEquals("ja", LanguageOptions.at(2))
    }
    @Test fun outOfBoundsFallsBackToEnglish() {
        assertEquals("en", LanguageOptions.at(-1))
        assertEquals("en", LanguageOptions.at(999))
    }
}
