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
/** Physical screen-space bounds of a captured application window (API 34+). */
internal data class CaptureBounds(val left: Int, val top: Int, val width: Int, val height: Int)

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
    private var captureBounds: CaptureBounds? = null
    private var lastPublished: List<VisibleTranslation> = emptyList()
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
        lastPublished = emptyList()
        captureBounds = null
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

    fun onFrame(frame: OcrFrame, width: Int, height: Int, bounds: CaptureBounds? = null) {
        if (closed) return
        frameW = frame.width
        frameH = frame.height
        overlayW = width
        overlayH = height
        captureBounds = bounds
        // Keep the previous subtitle through occasional empty OCR results.
        // Clear on a second consecutive empty result to avoid indefinitely stale UI.
        if (frame.lines.isEmpty() && latest.isNotEmpty()) {
            consecutiveEmptyOcrFrames++
            if (consecutiveEmptyOcrFrames < 3) return
        } else {
            consecutiveEmptyOcrFrames = 0
        }
        latest = frame.lines
        publish()
        translateNext()
    }

    private fun publish() {
        if (closed) return
        if (frameW <= 0 || frameH <= 0 || overlayW <= 0 || overlayH <= 0) {
            return
        }
        val target = captureBounds
        val targetWidth = target?.width ?: overlayW
        val targetHeight = target?.height ?: overlayH
        if (targetWidth <= 0 || targetHeight <= 0) return
        val scaleX = targetWidth.toDouble() / frameW
        val scaleY = targetHeight.toDouble() / frameH
        // Do not invent coordinates when the screenshot and target geometry disagree.
        if (kotlin.math.abs(scaleX - scaleY) / kotlin.math.max(scaleX, scaleY) > 0.12) {
            return
        }
        val offsetX = target?.left ?: 0
        val offsetY = target?.top ?: 0
        val visible = latest.mapNotNull { line ->
            cache[line.original]?.takeIf { it.isNotBlank() }?.let { translated ->
                VisibleTranslation(
                    line.original, translated,
                    offsetX + (line.left * scaleX).roundToInt(),
                    offsetY + (line.top * scaleY).roundToInt(),
                    offsetX + (line.right * scaleX).roundToInt(),
                    offsetY + (line.bottom * scaleY).roundToInt()
                )
            }
        }
        // Don't briefly blank old subtitles while a new line is still translating.
        if (visible.isEmpty() && latest.isNotEmpty() && lastPublished.isNotEmpty()) return
        if (!SubtitleChangePolicy.hasChanged(lastPublished, visible)) return
        lastPublished = visible
        onOutput(visible)
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
        lastPublished = emptyList()
        consecutiveEmptyOcrFrames = 0
        captureBounds = null
        onOutput(emptyList())
    }
    fun close() {
        closed = true
        generation++
        translator?.close()
        translator = null
        cache.clear()
    }
}
