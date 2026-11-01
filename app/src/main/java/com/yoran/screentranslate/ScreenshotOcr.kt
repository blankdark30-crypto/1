package com.yoran.screentranslate

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions

internal data class OcrLine(val original: String, val left: Int, val top: Int, val right: Int, val bottom: Int)
internal data class OcrFrame(val width: Int, val height: Int, val lines: List<OcrLine>)

/** One recognizer per explicitly selected source language; no concurrent OCR requests. */
internal class ScreenshotOcr {
    private var language = ""
    private var client: TextRecognizer? = null
    private var closed = false

    fun process(bitmap: Bitmap, requestedLanguage: String, done: (OcrFrame) -> Unit) {
        val w = bitmap.width
        val h = bitmap.height
        if (closed) { bitmap.recycle(); done(OcrFrame(w,h,emptyList())); return }
        try {
            if (language != requestedLanguage || client == null) {
                client?.close()
                language = requestedLanguage
                client = when (language) {
                    "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                    "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                    else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                }
            }
            client!!.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { text ->
                    val lines = if (closed) emptyList() else text.textBlocks.flatMap { it.lines }
                        .mapNotNull { line ->
                            val rect = line.boundingBox ?: return@mapNotNull null
                            val content = line.text.replace(Regex("\\s+"), " ").trim()
                            val hasRelevantScript = when (requestedLanguage) {
                                "ko" -> content.any { it in '\uAC00'..'\uD7A3' }
                                "ja" -> content.any { it in '\u3040'..'\u30FF' } ||
                                    content.any { it in '\u4E00'..'\u9FFF' }
                                else -> content.count { it.isLetter() && it.code < 128 } >= 2
                            }
                            if (!hasRelevantScript || content.length !in 2..160 ||
                                rect.width() < 8 || rect.height() !in 8..250) null
                            else OcrLine(content,rect.left,rect.top,rect.right,rect.bottom)
                        }.sortedWith(compareBy({ it.top }, { it.left })).take(12)
                    bitmap.recycle()
                    done(OcrFrame(w,h,lines))
                }
                .addOnFailureListener {
                    bitmap.recycle()
                    done(OcrFrame(w,h,emptyList()))
                }
        } catch (_: Exception) {
            bitmap.recycle()
            done(OcrFrame(w,h,emptyList()))
        }
    }

    fun close() {
        closed = true
        client?.close()
        client = null
    }
}
