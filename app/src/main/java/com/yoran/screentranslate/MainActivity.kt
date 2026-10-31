package com.yoran.screentranslate

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val codes = listOf("auto", "en", "ja", "ko")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("config", MODE_PRIVATE)
        if (!prefs.getBoolean("source_auto_migrated_031", false)) {
            // v0.3 silently defaulted to English. Reset once so updates start in auto mode.
            prefs.edit().putString("source", "auto")
                .putBoolean("source_auto_migrated_031", true).apply()
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(42), dp(24), dp(24))
            setBackgroundColor(0xFFF7F7FB.toInt())
        }
        fun label(t: String, size: Float) {
            root.addView(TextView(this).apply {
                text = t
                textSize = size
                setTextColor(0xFF172033.toInt())
                setPadding(0, dp(6), 0, dp(12))
            })
        }
        label("螢幕原位翻譯 v0.3.1", 26f)
        label("無需螢幕分享。第一次必須手動開啟 Android 無障礙服務，啟用後自動辨識其他 App 提供的介面文字並翻譯。圖片、遊戲畫面與影片字幕不保證可辨識。", 15f)
        label("來源語言（預設自動辨識；譯為繁體中文）", 16f)
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("自動偵測英／日／韓", "英文 English", "日文 日本語", "韓文 한국어"))
            setSelection(codes.indexOf(prefs.getString("source", "auto")).coerceAtLeast(0))
        }
        root.addView(spinner)
        root.addView(Button(this).apply {
            text = "儲存語言"
            setOnClickListener {
                prefs.edit().putString("source", codes[spinner.selectedItemPosition]).apply()
                Toast.makeText(this@MainActivity, "已更新語言", Toast.LENGTH_SHORT).show()
            }
        })
        label("權限與隱私說明", 17f)
        label("本功能將使用無障礙服務讀取其他 App 可存取的畫面文字、介面結構與位置，以便就地翻譯。會排除可辨識的密碼欄及可編輯輸入欄。文字在本機辨識與翻譯，首次需下載語言模型；不儲存截圖、不將文字送到本程式伺服器。請勿在顯示病患、銀行或其他敏感資料時使用。", 14f)
        root.addView(Button(this).apply {
            text = "我了解上述存取用途，前往無障礙設定啟用"
            setOnClickListener {
                prefs.edit().putBoolean("accessibility_disclosure_accepted", true)
                    .putString("source", codes[spinner.selectedItemPosition]).apply()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })
        label("已經是中文的文字不會重複翻譯。純漢字日文可能因無法和中文區分而被略過。請先用英文網頁測試。", 14f)
        label("啟用後：開啟其他 App 即自動翻譯；點懸浮球暫停／恢復；長按懸浮球關閉服務。若服務沒有出現，請回到無障礙設定確認已啟用。", 14f)
        setContentView(root)
    }
    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
}
