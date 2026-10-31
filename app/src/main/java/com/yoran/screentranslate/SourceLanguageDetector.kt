package com.yoran.screentranslate

/**
 * A conservative on-device script detector, not statistical language identification.
 * Han-only text is deliberately skipped: Japanese kanji and Chinese are indistinguishable
 * without surrounding context. This prevents translating already-Chinese UI labels.
 */
internal object SourceLanguageDetector {
    fun identify(text: String): String? {
        var latin = 0
        var han = 0
        var kana = 0
        var hangul = 0
        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            when (Character.UnicodeScript.of(codePoint)) {
                Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA -> kana++
                Character.UnicodeScript.HANGUL -> hangul++
                Character.UnicodeScript.HAN -> han++
                Character.UnicodeScript.LATIN -> {
                    if (Character.isLetter(codePoint)) latin++
                }
                else -> {}
            }
            index += Character.charCount(codePoint)
        }
        return when {
            kana > 0 && kana >= hangul -> "ja"
            hangul > 0 -> "ko"
            han > 0 -> null // Avoid Chinese-to-Chinese retranslations.
            latin >= 2 -> "en"
            else -> null
        }
    }

    fun select(text: String, preferred: String): String? {
        val detected = identify(text) ?: return null
        return if (preferred == "auto" || preferred == detected) detected else null
    }
}

internal data class SourceRegion(
    val source: String,
    val language: String,
    val box: PixelBox
)