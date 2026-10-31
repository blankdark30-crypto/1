package com.yoran.screentranslate

import org.junit.Assert.*
import org.junit.Test

class SourceLanguageDetectorTest {
    @Test fun englishIsDetected() {
        assertEquals("en", SourceLanguageDetector.select("Hello world", "auto"))
        assertEquals("en", SourceLanguageDetector.select("Settings", "en"))
    }

    @Test fun japaneseKanaIsDetected() {
        assertEquals("ja", SourceLanguageDetector.select("今日は天気がいいです", "auto"))
        assertEquals("ja", SourceLanguageDetector.select("コンピュータ", "auto"))
        assertEquals("ja", SourceLanguageDetector.select("今日", "ja")?.let { "ja" } ?: "skip")
    }

    @Test fun koreanHangulIsDetected() {
        assertEquals("ko", SourceLanguageDetector.select("안녕하세요", "auto"))
    }

    @Test fun chineseIsNotRoutedToTranslation() {
        assertNull(SourceLanguageDetector.select("你好世界", "auto"))
        assertNull(SourceLanguageDetector.select("繁體中文", "en"))
        assertNull(SourceLanguageDetector.select("简体中文", "ja"))
    }

    @Test fun manualSourceIsHonored() {
        assertNull(SourceLanguageDetector.select("Hello world", "ja"))
        assertNull(SourceLanguageDetector.select("안녕하세요", "en"))
        assertEquals("ja", SourceLanguageDetector.select("こんにちは", "ja"))
    }

    @Test fun hanOnlyJapaneseIsConservativelySkipped() {
        assertNull(SourceLanguageDetector.select("東京都", "auto"))
    }

    @Test fun symbolsAreNotTranslated() {
        assertNull(SourceLanguageDetector.select("123 👋 !", "auto"))
    }
}