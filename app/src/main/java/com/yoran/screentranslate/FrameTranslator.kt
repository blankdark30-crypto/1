package com.yoran.screentranslate

import android.icu.text.Transliterator
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlin.math.roundToInt

/**
 * Translate and cache OCR lines on-device. Translation and OCR are independent: frames
 * may arrive while text translation is in flight, but translated updates use the latest boxes.
 */
internal class FrameTranslator(
    private val onOutput: (List<VisibleTranslation>) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private var language: String? = null
    private var translator: Translator? = null
    private var ready = false
    private var busy = false
    private var closed = false
    private var generation = 0
    private val cache = object : LinkedHashMap<String, String>(160, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean = size > 180
    }
    private var latest: List<OcrLine> = emptyList()
    private var frameW = 1
    private var frameH = 1
    private var overlayW = 1
    private var overlayH = 1
    private var consecutiveEmptyOcrFrames = 0

    fun updateLanguage(selected: String) {
        if (language == selected) return
        generation++
        translator?.close()
        translator = null
        language = selected
        cache.clear()
        ready = false
        busy = false
        latest = emptyList()
        consecutiveEmptyOcrFrames = 0
        onOutput(emptyList())
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(when(selected) {
                "ja" -> TranslateLanguage.JAPANESE
                "ko" -> TranslateLanguage.KOREAN
                else -> TranslateLanguage.ENGLISH
            })
            .setTargetLanguage(TranslateLanguage.CHINESE)
            .build()
        val token = generation
        val t = Translation.getClient(options)
        translator = t
        onStatus("準備翻譯模型；首次使用需下載")
        t.downloadModelIfNeeded()
            .addOnSuccessListener {
                if (!closed && token == generation) {
                    ready = true
                    onStatus("翻譯模型已就緒")
                    translateNext()
                }
            }
            .addOnFailureListener { e ->
                if (!closed && token == generation) onStatus("模型下載失敗：" + (e.message ?: "網路不可用"))
            }
    }

    fun onFrame(frame: OcrFrame, width: Int, height: Int) {
        if (closed) return
        frameW = frame.width
        frameH = frame.height
        overlayW = width
        overlayH = height
        // A one-off OCR miss shouldn't erase all translation labels for a frame.
        // Clear on a second consecutive empty result to avoid indefinitely stale UI.
        if (frame.lines.isEmpty() && latest.isNotEmpty()) {
            consecutiveEmptyOcrFrames++
            if (consecutiveEmptyOcrFrames < 2) return
        } else {
            consecutiveEmptyOcrFrames = 0
        }
        latest = frame.lines
        publish()
        translateNext()
    }

    private fun publish() {
        if (closed) return
        val scaleX = overlayW.toDouble() / frameW
        val scaleY = overlayH.toDouble() / frameH
        // Reject rotated/window-clipped aspect ratios instead of guessing coordinates.
        if (frameW <= 0 || frameH <= 0 || overlayW <= 0 || overlayH <= 0 ||
            kotlin.math.abs(scaleX-scaleY) / kotlin.math.max(scaleX,scaleY) > 0.12) {
            onOutput(emptyList())
            return
        }
        onOutput(latest.mapNotNull { line ->
            cache[line.original]?.takeIf { it.isNotBlank() }?.let { translated ->
                VisibleTranslation(
                    line.original,translated,
                    (line.left * scaleX).roundToInt(),
                    (line.top * scaleY).roundToInt(),
                    (line.right * scaleX).roundToInt(),
                    (line.bottom * scaleY).roundToInt()
                )
            }
        })
    }

    private fun translateNext() {
        if (!ready || busy || closed) return
        val t = translator ?: return
        val src = latest.firstOrNull { it.original !in cache }?.original ?: return
        busy = true
        val token = generation
        t.translate(src)
            .addOnSuccessListener { value ->
                if (closed || token != generation) return@addOnSuccessListener
                cache[src] = try {
                    Transliterator.getInstance("Simplified-Traditional").transliterate(value)
                } catch (_: Exception) { value }
                busy = false
                publish()
                translateNext()
            }
            .addOnFailureListener { error ->
                if (closed || token != generation) return@addOnFailureListener
                cache[src] = "" // Don't retry the same failing item every second.
                busy = false
                onStatus("翻譯失敗：" + (error.message ?: "未知"))
                translateNext()
            }
    }

    fun clear() {
        latest = emptyList()
        publish()
    }
    fun close() {
        closed = true
        generation++
        translator?.close()
        translator = null
        cache.clear()
    }
}
