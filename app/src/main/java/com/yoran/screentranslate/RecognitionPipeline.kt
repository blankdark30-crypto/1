package com.yoran.screentranslate

import android.graphics.Bitmap
import android.icu.text.Transliterator
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions

/** No screen image is persisted. OCR and translation inference execute on-device. */
internal class RecognitionPipeline(
    sourceLanguage: String,
    private val onStatus: (String) -> Unit,
    private val onTranslation: (String) -> Unit
) {
    private val recognizer: TextRecognizer = when (sourceLanguage) {
        "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val translator: Translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(when (sourceLanguage) {
                "ja" -> TranslateLanguage.JAPANESE
                "ko" -> TranslateLanguage.KOREAN
                else -> TranslateLanguage.ENGLISH
            })
            .setTargetLanguage(TranslateLanguage.CHINESE)
            .build()
    )
    @Volatile private var ready = false
    @Volatile private var closed = false
    private var previousSource = ""

    init {
        onStatus("正在準備本機翻譯模型；首次使用需連線下載…")
        translator.downloadModelIfNeeded()
            .addOnSuccessListener {
                if (!closed) {
                    ready = true
                    onStatus("掃描中：切換到想翻譯的畫面")
                }
            }
            .addOnFailureListener { error ->
                if (!closed) onStatus("模型下載失敗：${error.message ?: "請檢查網路"}")
            }
    }

    /** Ownership of bitmap is transferred here; done always releases capture backpressure. */
    fun recognize(bitmap: Bitmap, done: () -> Unit) {
        if (closed || !ready) {
            bitmap.recycle()
            done()
            return
        }
        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    bitmap.recycle()
                    if (closed) { done(); return@addOnSuccessListener }
                    val original = result.text.trim().take(MAX_TEXT_LENGTH)
                    if (original.isEmpty() || original == previousSource) {
                        done()
                        return@addOnSuccessListener
                    }
                    previousSource = original
                    translator.translate(original)
                        .addOnSuccessListener { translated ->
                            if (!closed) onTranslation(toTraditional(translated))
                            done()
                        }
                        .addOnFailureListener { error ->
                            if (!closed) onStatus("翻譯失敗：${error.message ?: "未知原因"}")
                            done()
                        }
                }
                .addOnFailureListener { error ->
                    bitmap.recycle()
                    if (!closed) onStatus("OCR 失敗：${error.message ?: "未知原因"}")
                    done()
                }
        } catch (error: Exception) {
            bitmap.recycle()
            if (!closed) onStatus("辨識異常：${error.message ?: "未知原因"}")
            done()
        }
    }

    private fun toTraditional(value: String): String = try {
        Transliterator.getInstance("Simplified-Traditional").transliterate(value)
    } catch (_: Exception) { value }

    fun close() {
        closed = true
        ready = false
        recognizer.close()
        translator.close()
    }

    companion object { private const val MAX_TEXT_LENGTH = 1500 }
}
