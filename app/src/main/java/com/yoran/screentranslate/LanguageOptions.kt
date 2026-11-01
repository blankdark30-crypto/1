package com.yoran.screentranslate

/**
 * Source languages are explicitly chosen by the user. The target is fixed to Chinese.
 * No background language detection or accessibility scanning is involved.
 */
internal object LanguageOptions {
    val codes = listOf("en", "ko", "ja")
    val labels = listOf("英文 English", "韓文 한국어", "日文 日本語")
    fun at(position: Int): String = codes.getOrElse(position) { "en" }
}
