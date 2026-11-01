package com.yoran.screentranslate

import android.icu.text.Transliterator
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

/**
 * Routes each visible source line to the matching local translator, downloading models
 * on demand. UI-only Chinese strings never enter this class. No network text upload.
 */
internal class AccessibilityTranslator(
    private val onResult: (List<TranslatedRegion>) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val models = mutableMapOf<String, Translator>()
    private val readyLanguages = mutableSetOf<String>()
    private val failedLanguages = mutableSetOf<String>()
    private val cache = object : LinkedHashMap<String, String>(192, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
            size > 250
    }
    private var latest: List<SourceRegion> = emptyList()
    private var busy = false
    private var closed = false

    fun submit(regions: List<SourceRegion>) {
        if (closed) return
        latest = regions.take(18)
        publish()
        processNext()
    }

    private fun cacheKey(item: SourceRegion): String = item.language + ":" + item.source

    private fun publish() {
        if (closed) return
        onResult(latest.mapNotNull { item ->
            cache[cacheKey(item)]?.takeIf { it.isNotBlank() }?.let {
                TranslatedRegion(item.source, it, item.box)
            }
        })
    }

    private fun processNext() {
        if (closed || busy) return
        val item = latest.firstOrNull {
            it.language !in failedLanguages && !cache.containsKey(cacheKey(it))
        } ?: return
        val language = item.language
        val translator = models.getOrPut(language) {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(
                        when (language) {
                            "ja" -> TranslateLanguage.JAPANESE
                            "ko" -> TranslateLanguage.KOREAN
                            else -> TranslateLanguage.ENGLISH
                        }
                    )
                    .setTargetLanguage(TranslateLanguage.CHINESE)
                    .build()
            )
        }
        busy = true
        if (language !in readyLanguages) {
            onStatus("準備 " + language + " 翻譯模型；首次需下載")
            translator.downloadModelIfNeeded()
                .addOnSuccessListener {
                    if (!closed) {
                        readyLanguages.add(language)
                        busy = false
                        onStatus(language + " 模型就緒")
                        processNext()
                    }
                }
                .addOnFailureListener { e ->
                    if (!closed) {
                        failedLanguages.add(language)
                        busy = false
                        onStatus(language + " 模型下載失敗：" + (e.message ?: "請檢查網路"))
                        processNext()
                    }
                }
            return
        }
        translator.translate(item.source)
            .addOnSuccessListener { translated ->
                if (!closed) {
                    cache[cacheKey(item)] = toTraditional(translated)
                    busy = false
                    publish()
                    processNext()
                }
            }
            .addOnFailureListener { e ->
                if (!closed) {
                    cache[cacheKey(item)] = ""
                    busy = false
                    onStatus("部分文字翻譯失敗：" + (e.message ?: "未知原因"))
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
        busy = false
        latest = emptyList()
        cache.clear()
        readyLanguages.clear()
        failedLanguages.clear()
        models.values.forEach { it.close() }
        models.clear()
    }
}