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

/** No screenshot persistence. OCR emits line positions; per-language translation runs on-device. */
internal class RecognitionPipeline(
    sourceLanguage: String,
    private val onStatus: (String) -> Unit,
    private val onRegions: (List<TranslatedRegion>, Int, Int) -> Unit
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
    private val cache = object : LinkedHashMap<String, String>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean = size > 240
    }
    @Volatile private var ready = false
    @Volatile private var closed = false

    init {
        onStatus("正在準備本機模型；首次需下載")
        translator.downloadModelIfNeeded()
            .addOnSuccessListener { if (!closed) { ready = true; onStatus("模型就緒") } }
            .addOnFailureListener { if (!closed) onStatus("模型下載失敗：${it.message ?: "請檢查網路"}") }
    }

    /** Takes ownership of bitmap. done() must always release capture backpressure. */
    fun recognize(bitmap: Bitmap, done: () -> Unit) {
        if (closed || !ready) { bitmap.recycle(); done(); return }
        val w = bitmap.width
        val h = bitmap.height
        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    bitmap.recycle()
                    if (closed) { done(); return@addOnSuccessListener }
                    val raw = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                        val box = line.boundingBox ?: return@mapNotNull null
                        val source = line.text.trim().replace(Regex("\\s+"), " ")
                        if (source.length < 2 || source.length > 160 ||
                            source.none { it.isLetter() }) return@mapNotNull null
                        source to PixelBox(box.left, box.top, box.right, box.bottom)
                    }.sortedWith(compareBy({ it.second.top }, { it.second.left })).take(MAX_REGIONS)

                    if (raw.isEmpty()) { onRegions(emptyList(), w, h); done(); return@addOnSuccessListener }
                    val translations = mutableMapOf<String, String>()
                    fun publish() {
                        if (!closed) onRegions(raw.mapNotNull { (source, box) ->
                            translations[source]?.takeIf { it.isNotBlank() }?.let {
                                TranslatedRegion(source, it, box)
                            }
                        }, w, h)
                    }
                    raw.forEach { (source, _) -> cache[source]?.let { translations[source] = it } }
                    publish() // Even identical text moves in scrolling apps; re-map positions every frame.
                    val missing = raw.map { it.first }.distinct().filter { it !in translations }
                    fun translateAt(i: Int) {
                        if (closed || i >= missing.size) { done(); return }
                        val source = missing[i]
                        translator.translate(source)
                            .addOnSuccessListener { value ->
                                if (!closed) {
                                    val translated = toTraditional(value)
                                    cache[source] = translated
                                    translations[source] = translated
                                    publish()
                                }
                                translateAt(i + 1)
                            }
                            .addOnFailureListener { error ->
                                if (!closed) onStatus("部分翻譯失敗：${error.message ?: "未知原因"}")
                                translateAt(i + 1)
                            }
                    }
                    translateAt(0)
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
        cache.clear()
        recognizer.close()
        translator.close()
    }

    companion object { private const val MAX_REGIONS = 14 }
}
