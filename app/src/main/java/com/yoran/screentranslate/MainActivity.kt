package com.yoran.screentranslate

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.icu.text.Transliterator
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.AdapterView
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions

/**
 * Manual offline-first translator. There are no foreground services, overlays,
 * accessibility permissions, screen capture APIs, or automatic screen reading.
 */
class MainActivity : Activity() {
    private lateinit var sourceSelector: Spinner
    private lateinit var sourceInput: EditText
    private lateinit var resultLabel: TextView
    private lateinit var statusLabel: TextView
    private lateinit var translateButton: Button
    private lateinit var imageButton: Button
    private lateinit var pasteButton: Button
    private var translator: Translator? = null
    private var recognizer: TextRecognizer? = null
    private var busy = false
    private var destroyed = false
    private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("manual_translate_v05", MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(24))
            setBackgroundColor(0xFFF7F8FC.toInt())
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        }
        fun header(value: String, size: Float, top: Int = 8): TextView =
            TextView(this).apply {
                text = value
                textSize = size
                setTextColor(0xFF182136.toInt())
                setPadding(0, dp(top), 0, dp(8))
            }

        root.addView(header("遊戲每秒翻譯 v0.6", 25f))
        root.addView(header("已授權的無障礙服務可以每秒嘗試截取目前畫面，以本機 OCR 辨識並原位翻譯；請先手動在系統設定啟用無障礙權限。", 14f))
        root.addView(header("畫面擷取與隱私授權", 17f, 14))
        root.addView(header("開啟後，無障礙服務可以讀取目前顯示的整個螢幕畫面，包括遊戲、通知或對話。畫面只在手機記憶體中用於文字辨識，不儲存、不傳送至自設伺服器。禁止在密碼、金融、醫療資料等敏感畫面使用；部分 App 可能阻擋擷取。停止掃描可隨時關閉此功能。", 13f))
        val autoStatus = header("自動翻譯尚未啟用", 14f)
        val autoEnableButton = Button(this).apply {
            text = "同意上述用途，開啟每秒自動翻譯"
            isAllCaps = false
            setOnClickListener {
                prefs.edit().putBoolean("ocr_disclosure_accepted", true)
                    .putBoolean("ocr_active", true)
                    .putString("source", LanguageOptions.at(sourceSelector.selectedItemPosition))
                    .apply()
                autoStatus.text = "已要求啟用，請至 Android 無障礙設定開啟「三語遊戲翻譯」"
                try { startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                catch (_: Exception) { autoStatus.text = "無法開啟無障礙設定" }
            }
        }
        val autoStopButton = Button(this).apply {
            text = "停止背景翻譯與關閉懸浮球"
            isAllCaps = false
            setOnClickListener {
                prefs.edit().putBoolean("ocr_active", false).apply()
                autoStatus.text = "自動翻譯已停止；無障礙權限可在系統設定中移除"
            }
        }
        root.addView(autoEnableButton)
        root.addView(autoStopButton)
        root.addView(autoStatus)
        root.addView(header("僅在 Android 11 以上支援畫面 OCR。每秒是目標間隔，OCR / 翻譯可能較慢，遇系統限速會自動退避。懸浮球點擊暫停／恢復、長按停止。", 13f))
        root.addView(header("手動文字／截圖翻譯（備用）", 20f, 12))
        root.addView(header("僅使用你貼上的文字或手動選擇的圖片。無需無障礙、懸浮窗或螢幕分享權限。", 14f))
        root.addView(header("選擇原文語言", 16f, 16))
        sourceSelector = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                LanguageOptions.labels
            )
            setSelection(LanguageOptions.codes.indexOf(
                prefs.getString("source", "en")).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?, view: View?, position: Int, id: Long
                ) {
                    prefs.edit().putString("source", LanguageOptions.at(position)).apply()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        root.addView(sourceSelector)
        root.addView(header("原文", 16f, 16))
        sourceInput = EditText(this).apply {
            hint = "貼上英文、韓文或日文；也可以選擇遊戲截圖"
            textSize = 16f
            setTextColor(0xFF172033.toInt())
            setHintTextColor(0xFF707B91.toInt())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 5
            maxLines = 12
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        root.addView(sourceInput, LinearLayout.LayoutParams(-1, -2))
        pasteButton = Button(this).apply {
            text = "貼上剪貼簿並翻譯"
            isAllCaps = false
            setOnClickListener { pasteThenTranslate() }
        }
        root.addView(pasteButton)
        imageButton = Button(this).apply {
            text = "選擇遊戲截圖辨識並翻譯"
            isAllCaps = false
            setOnClickListener { openImagePicker() }
        }
        root.addView(imageButton)
        translateButton = Button(this).apply {
            text = "翻譯為繁體中文"
            isAllCaps = false
            setOnClickListener { translateCurrentInput() }
        }
        root.addView(translateButton)
        root.addView(header("翻譯結果", 16f, 16))
        resultLabel = TextView(this).apply {
            text = "翻譯結果將顯示在這裡"
            textSize = 18f
            setTextColor(0xFF1B2640.toInt())
            setPadding(dp(12), dp(16), dp(12), dp(16))
            setTextIsSelectable(true)
            setBackgroundColor(0xFFE9EDF5.toInt())
            minHeight = dp(110)
        }
        root.addView(resultLabel, LinearLayout.LayoutParams(-1, -2))
        root.addView(Button(this).apply {
            text = "複製翻譯結果"
            isAllCaps = false
            setOnClickListener {
                val out = resultLabel.text.toString()
                if (out.isNotBlank() && out != "翻譯結果將顯示在這裡") {
                    val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("繁體中文翻譯", out))
                    Toast.makeText(this@MainActivity, "已複製", Toast.LENGTH_SHORT).show()
                }
            }
        })
        statusLabel = header("就緒", 13f, 14)
        root.addView(statusLabel)
        root.addView(header(
            "隱私：不讀取其他 App 畫面、不收集背景文字、不儲存截圖。首次下載翻譯模型需要網路；下載後可使用本機翻譯。遊戲中的文字必須先手動截圖後選取。",
            12f, 12
        ))
        setContentView(scroll)
        intent?.let(::handleSharedContent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedContent(intent)
    }

    private fun handleSharedContent(intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        val mime = intent.type ?: return
        if (mime.startsWith("text/")) {
            val value = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            if (!value.isNullOrBlank()) {
                sourceInput.setText(value.take(MAX_CHARS))
                sourceInput.setSelection(sourceInput.text.length)
                sourceInput.post { translateCurrentInput() }
            }
        } else if (mime.startsWith("image/")) {
            val uri: Uri? = if (Build.VERSION.SDK_INT >= 33)
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
            if (uri != null) sourceInput.post { recognizeSelectedImage(uri) }
        }
    }

    private fun pasteThenTranslate() {
        if (busy) return
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val value = try {
            clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        } catch (_: Exception) { null }
        if (value.isNullOrBlank()) {
            statusLabel.text = "剪貼簿沒有可用文字"
            return
        }
        sourceInput.setText(value.take(MAX_CHARS))
        sourceInput.setSelection(sourceInput.text.length)
        translateCurrentInput()
    }

    private fun openImagePicker() {
        if (busy) return
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        try {
            startActivityForResult(Intent.createChooser(intent, "選擇截圖"), REQUEST_IMAGE)
        } catch (_: Exception) {
            statusLabel.text = "無法開啟圖片選取器"
        }
    }

    @Deprecated("Used for lightweight Android 8+ image selection without third-party dependencies")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMAGE && resultCode == RESULT_OK) {
            data?.data?.let(::recognizeSelectedImage)
        }
    }

    private fun recognizeSelectedImage(uri: Uri) {
        if (busy || destroyed) return
        setBusy(true)
        statusLabel.text = "正在辨識圖片文字…"
        val token = ++generation
        val client = try {
            when (LanguageOptions.at(sourceSelector.selectedItemPosition)) {
                "ko" -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
                "ja" -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
                else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            }
        } catch (e: Exception) {
            fail(token, "OCR 初始化失敗：" + (e.message ?: "未知原因"))
            return
        }
        recognizer = client
        try {
            val image = InputImage.fromFilePath(this, uri)
            client.process(image)
                .addOnSuccessListener { parsed ->
                    client.close()
                    if (recognizer === client) recognizer = null
                    if (!isValid(token)) return@addOnSuccessListener
                    val extracted = parsed.text.trim().take(MAX_CHARS)
                    if (extracted.isBlank()) {
                        fail(token, "沒有辨識到文字，可能是特殊字體或圖片過暗")
                        return@addOnSuccessListener
                    }
                    sourceInput.setText(extracted)
                    statusLabel.text = "已辨識圖片文字，正在翻譯…"
                    translateText(extracted, token)
                }
                .addOnFailureListener { e ->
                    client.close()
                    if (recognizer === client) recognizer = null
                    fail(token, "OCR 失敗：" + (e.message ?: "未知原因"))
                }
        } catch (e: Exception) {
            client.close()
            if (recognizer === client) recognizer = null
            fail(token, "無法讀取圖片：" + (e.message ?: "未知原因"))
        }
    }

    private fun translateCurrentInput() {
        if (busy || destroyed) return
        val input = sourceInput.text.toString().trim()
        if (input.isBlank()) {
            statusLabel.text = "請先輸入文字或選擇截圖"
            return
        }
        if (input.length > MAX_CHARS) {
            statusLabel.text = "文字太長，請縮短至 $MAX_CHARS 字以內"
            return
        }
        setBusy(true)
        translateText(input, ++generation)
    }

    private fun translateText(text: String, token: Int) {
        if (!isValid(token)) return
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(
                when (LanguageOptions.at(sourceSelector.selectedItemPosition)) {
                    "ko" -> TranslateLanguage.KOREAN
                    "ja" -> TranslateLanguage.JAPANESE
                    else -> TranslateLanguage.ENGLISH
                }
            )
            .setTargetLanguage(TranslateLanguage.CHINESE)
            .build()
        translator?.close()
        val client = try { Translation.getClient(options) }
        catch (e: Exception) {
            fail(token, "無法建立翻譯模型：" + (e.message ?: "未知原因"))
            return
        }
        translator = client
        statusLabel.text = "正在準備翻譯模型（首次可能需要下載）…"
        client.downloadModelIfNeeded()
            .addOnSuccessListener {
                if (!isValid(token)) return@addOnSuccessListener
                statusLabel.text = "翻譯中…"
                client.translate(text)
                    .addOnSuccessListener { value ->
                        if (isValid(token)) {
                            val traditional = try {
                                Transliterator.getInstance("Simplified-Traditional")
                                    .transliterate(value)
                            } catch (_: Exception) { value }
                            resultLabel.text = traditional
                            statusLabel.text = "翻譯完成"
                            setBusy(false)
                        }
                        if (translator === client) translator = null
                        client.close()
                    }
                    .addOnFailureListener { error ->
                        if (translator === client) translator = null
                        client.close()
                        fail(token, "翻譯失敗：" + (error.message ?: "未知原因"))
                    }
            }
            .addOnFailureListener { error ->
                if (translator === client) translator = null
                client.close()
                fail(token, "模型下載失敗，請確認網路：" + (error.message ?: "未知原因"))
            }
    }

    private fun setBusy(value: Boolean) {
        busy = value
        translateButton.isEnabled = !value
        pasteButton.isEnabled = !value
        imageButton.isEnabled = !value
        sourceSelector.isEnabled = !value
    }

    private fun isValid(token: Int): Boolean = !destroyed && token == generation

    private fun fail(token: Int, message: String) {
        if (!isValid(token)) return
        statusLabel.text = message
        setBusy(false)
    }

    override fun onDestroy() {
        destroyed = true
        generation++
        recognizer?.close()
        recognizer = null
        translator?.close()
        translator = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_IMAGE = 508
        private const val MAX_CHARS = 3000
    }
}