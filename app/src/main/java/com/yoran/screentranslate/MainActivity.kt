package com.yoran.screentranslate

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val requestCaptureCode = 102
    private val languages = listOf("en", "ja", "ko")
    private var sourceSpinner: Spinner? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF7F7FB.toInt())
            setPadding(dp(24), dp(28), dp(24), dp(24))
        }
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val sys = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(dp(24), sys.top + dp(20), dp(24), sys.bottom + dp(20))
                insets
            }
        }
        root.addView(TextView(this).apply {
            text = "螢幕即時翻譯球"
            textSize = 27f
            setTextColor(0xFF111827.toInt())
        })
        root.addView(TextView(this).apply {
            text = "跨 App 擷取文字 → 本機辨識 → 翻譯浮窗。每次重新開始擷取都需經 Android 授權。"
            textSize = 15f
            setTextColor(0xFF4B5563.toInt())
            setPadding(0, dp(14), 0, dp(28))
        })
        root.addView(TextView(this).apply {
            text = "來源文字語言（翻譯為繁體中文）"
            textSize = 16f
            setTextColor(0xFF111827.toInt())
        })
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("英文 English", "日文 日本語", "韓文 한국어")
            )
            val current = getSharedPreferences("config", MODE_PRIVATE)
                .getString("source", "en") ?: "en"
            setSelection(languages.indexOf(current).coerceAtLeast(0))
        }
        sourceSpinner = spinner
        root.addView(spinner)
        root.addView(actionButton("① 開啟懸浮球") { showBubble() })
        root.addView(actionButton("② 開始即時翻譯") { requestProjection() })
        root.addView(actionButton("暫停翻譯（保留懸浮球）") {
            controlService(ScreenTranslateService.ACTION_STOP_CAPTURE)
        })
        root.addView(actionButton("關閉懸浮球及掃描") {
            controlService(ScreenTranslateService.ACTION_SHUTDOWN)
        })
        root.addView(TextView(this).apply {
            text = "操作：點擊懸浮球切換掃描；拖曳移動；長按完全關閉。首次翻譯需要下載語言模型。\n\n隱私：不保存畫面、不錄音、不將截圖送至自設伺服器；金融、密碼等保護畫面可能無法擷取。"
            textSize = 13f
            setTextColor(0xFF6B7280.toInt())
            setPadding(0, dp(28), 0, 0)
        })
        setContentView(root)
        if (intent.getBooleanExtra(EXTRA_REQUEST_CAPTURE, false)) {
            root.post { requestProjection() }
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_REQUEST_CAPTURE, false)) {
            window.decorView.post { requestProjection() }
        }
    }

    private fun actionButton(label: String, click: () -> Unit): View =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { click() }
        }

    private fun showBubble(): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "請先授權顯示在其他 App 上層", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")))
            return false
        }
        startForegroundService(Intent(this, ScreenTranslateService::class.java).apply {
            action = ScreenTranslateService.ACTION_SHOW_BUBBLE
        })
        return true
    }

    private fun requestProjection() {
        if (!showBubble()) return
        val source = languages[sourceSpinner?.selectedItemPosition?.coerceIn(0, 2) ?: 0]
        getSharedPreferences("config", MODE_PRIVATE).edit().putString("source", source).apply()
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), requestCaptureCode)
    }

    @Deprecated("Platform callback used for a dependency-light MVP")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != requestCaptureCode) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "未授權螢幕擷取，翻譯未啟動", Toast.LENGTH_SHORT).show()
            return
        }
        val source = getSharedPreferences("config", MODE_PRIVATE).getString("source", "en") ?: "en"
        val launch = Intent(this, ScreenTranslateService::class.java).apply {
            action = ScreenTranslateService.ACTION_START_CAPTURE
            putExtra(ScreenTranslateService.EXTRA_PROJECTION_RESULT, resultCode)
            putExtra(ScreenTranslateService.EXTRA_PROJECTION_DATA, data)
            putExtra(ScreenTranslateService.EXTRA_SOURCE_LANGUAGE, source)
        }
        startForegroundService(launch)
        Toast.makeText(this, "翻譯啟動中，可切換至其他 App", Toast.LENGTH_SHORT).show()
    }

    private fun controlService(action: String) {
        startService(Intent(this, ScreenTranslateService::class.java).apply { this.action = action })
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object { const val EXTRA_REQUEST_CAPTURE = "request_capture" }
}
