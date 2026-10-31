package com.yoran.screentranslate

import android.icu.text.Transliterator
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

/** Per-session, in-memory translations. No screenshots, no disk persistence, no text uploads. */
internal class AccessibilityTranslator(
    language: String,
    private val onResult: (List<TranslatedRegion>) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val translator: Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(when (language) {
                "ja" -> TranslateLanguage.JAPANESE
                "ko" -> TranslateLanguage.KOREAN
                else -> TranslateLanguage.ENGLISH
            })
            .setTargetLanguage(TranslateLanguage.CHINESE)
            .build()
    )
    private val cache = object : LinkedHashMap<String, String>(160, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
            size > 250
    }
    private var latest: List<Pair<String, PixelBox>> = emptyList()
    private var ready = false
    private var busy = false
    private var closed = false

    init {
        onStatus("正在下載或確認本機翻譯模型")
        translator.downloadModelIfNeeded()
            .addOnSuccessListener {
                if (!closed) {
                    ready = true
                    onStatus("本機翻譯已就緒")
                    publish()
                    processNext()
                }
            }
            .addOnFailureListener { error ->
                if (!closed) onStatus("翻譯模型未就緒：" + (error.message ?: "請檢查網路"))
            }
    }

    fun submit(regions: List<Pair<String, PixelBox>>) {
        if (closed) return
        latest = regions.take(18)
        publish()
        if (ready) processNext()
    }

    private fun publish() {
        if (closed) return
        val output = latest.mapNotNull { (source, bounds) ->
            cache[source]?.takeIf { it.isNotBlank() }?.let {
                TranslatedRegion(source, it, bounds)
            }
        }
        onResult(output)
    }

    private fun processNext() {
        if (closed || busy || !ready) return
        val source = latest.firstOrNull { !cache.containsKey(it.first) }?.first ?: return
        busy = true
        translator.translate(source)
            .addOnSuccessListener { result ->
                if (!closed) {
                    cache[source] = toTraditional(result)
                    busy = false
                    publish()
                    processNext()
                }
            }
            .addOnFailureListener { error ->
                if (!closed) {
                    // Remember failure for this session, avoiding an infinite retry loop.
                    cache[source] = ""
                    busy = false
                    onStatus("部分文字翻譯失敗：" + (error.message ?: "未知原因"))
                    publish()
                    processNext()
                }
            }
    }

    private fun toTraditional(value: String): String = try {
        Transliterator.getInstance("Simplified-Traditional").transliterate(value)
    } catch (_: Exception) { value }

    fun close() {
        closed = true
        ready = false
        latest = emptyList()
        cache.clear()
        translator.close()
    }
}
