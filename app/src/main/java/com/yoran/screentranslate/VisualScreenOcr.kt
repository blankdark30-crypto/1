package com.yoran.screentranslate

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlin.math.max
import kotlin.math.min

/**
 * Visual OCR for games which draw their text into pixels rather than accessibility nodes.
 * Works only on in-memory, explicitly permitted accessibility screenshots (Android 11+).
 */
internal class VisualScreenOcr {
    private val recognizers: Map<String, TextRecognizer> = mapOf(
        "en" to TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
        "ja" to TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()),
        "ko" to TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    )
    private var closed = false

    fun recognize(
        bitmap: Bitmap,
        selectedLanguage: String,
        done: (List<SourceRegion>, Int, Int) -> Unit
    ) {
        val width = bitmap.width
        val height = bitmap.height
        if (closed) {
            bitmap.recycle()
            done(emptyList(), width, height)
            return
        }
        val languages = if (selectedLanguage == "auto")
            listOf("en", "ja", "ko")
        else listOf(selectedLanguage).filter { it in recognizers }
        if (languages.isEmpty()) {
            bitmap.recycle()
            done(emptyList(), width, height)
            return
        }
        var remaining = languages.size
        val detected = mutableMapOf<String, List<SourceRegion>>()
        fun complete() {
            remaining--
            if (remaining > 0) return
            bitmap.recycle()
            if (closed) {
                done(emptyList(), width, height)
                return
            }
            val all = languages.flatMap { detected[it].orEmpty() }
                .sortedWith(compareBy({ it.box.top }, { it.box.left }))
            val result = mutableListOf<SourceRegion>()
            // OCR engines overlap when a line contains both Latin and CJK.
            for (item in all) {
                if (result.size >= 20) break
                if (result.none { overlaps(it.box, item.box) }) result.add(item)
            }
            done(result, width, height)
        }
        languages.forEach { language ->
            val recognizer = recognizers[language] ?: run {
                complete()
                return@forEach
            }
            try {
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { parsed ->
                        if (!closed) {
                            val candidates = parsed.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                                val rectangle = line.boundingBox ?: return@mapNotNull null
                                val source = line.text.replace(Regex("\\s+"), " ").trim()
                                val selected = SourceLanguageDetector.select(source, selectedLanguage)
                                    ?: return@mapNotNull null
                                if (selectedLanguage == "auto" && selected != language) return@mapNotNull null
                                val box = PixelBox(rectangle.left, rectangle.top,
                                    rectangle.right, rectangle.bottom)
                                if (source.length !in 2..160 || box.width < 10 ||
                                    box.height !in 8..280) return@mapNotNull null
                                SourceRegion(source, selected, box)
                            }
                            detected[language] = candidates
                        }
                        complete()
                    }
                    .addOnFailureListener { complete() }
            } catch (_: Exception) {
                complete()
            }
        }
    }

    private fun overlaps(a: PixelBox, b: PixelBox): Boolean {
        val w = min(a.right, b.right) - max(a.left, b.left)
        val h = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (w <= 0 || h <= 0) return false
        val smallerArea = min(a.width * a.height, b.width * b.height).coerceAtLeast(1)
        return w.toLong() * h > smallerArea.toLong() * 0.55
    }

    fun close() {
        closed = true
        recognizers.values.forEach { it.close() }
    }
}