# 螢幕翻譯球 / ScreenTranslate (Android MVP)

Kotlin 原生 Android App：經使用者授權擷取畫面，以 ML Kit 本機 OCR 辨識英／日／韓文，翻譯為中文，再透過系統級懸浮面板顯示。優先支持 Android 8.0+，目標 API 36。

## 已實作

- 可拖曳懸浮球：點一下（啟動授權/停止掃描）、拖曳移動、長按關閉。
- 可覆蓋在其他 App 的浮動翻譯面板；可單獨收起。
- Android MediaProjection 前景服務，約每 1.5 秒最多讀取一張新畫面；OCR 與翻譯採背壓控制，不讓任務重疊。
- 使用者選擇英文、日文或韓文來源，輸出中文，盡力進行繁體轉換。
- 翻譯中與懸浮球待命均採用前景通知，方便清楚識別與停止操作。
- 不保存截圖、不錄音、不經自建後端傳送截圖。翻譯模型首次使用需下載。

## 使用步驟

1. 安裝 **Android Studio**（JDK 17、Android SDK 36），以專案根目錄開啟。
2. 若沒有 Gradle Wrapper JAR，可以使用 Android Studio 已安裝的 Gradle，或在電腦安裝 Gradle 8.14 後執行 `gradle :app:assembleDebug`。GitHub Actions 已設定自動編譯測試 APK。
3. 在 Android 8.0+ 真機上啟動 App。
4. 點「開啟懸浮球」，在 Android 設定授權「顯示在其他應用程式上層」。
5. 選擇來源語言，再點「開始即時翻譯」，同意系統的畫面分享提示。
6. 切換其他 App，翻譯顯示在底部懸浮面板。點懸浮球停止擷取；再點一次需重新同意螢幕分享。

## 技術組成

- `MainActivity.kt` — 使用者入口、權限請求、來源語言選擇。
- `ScreenTranslateService.kt` — 浮窗、控制球、MediaProjection 擷取、通知、節流。
- `RecognitionPipeline.kt` — ML Kit 文字辨識及離線翻譯，簡體轉繁體後呈現。
- GitHub Actions — `Android Debug APK`，由 `app/build/outputs/apk/debug/app-debug.apk` 上傳產物。

## 限制與風險（必須真機驗證）

1. **Android 14+ 的每次新擷取工作階段都需重新取得使用者同意**，不能偷偷或永久擷取。開始掃描時可能顯示系統的全螢幕或單一 App 共享選擇。
2. `FLAG_SECURE` 的 App、系統敏感畫面或 DRM 影片可能無法擷取/辨識；系統和廠商的浮窗限制不保證所有 App 都能覆蓋。
3. 這是**翻譯面板**，不是在每行原文字位置上逐行替換的 OCR overlay；也不是 60 FPS 影片字幕翻譯。準即時速度受 OCR、模型及手機效能影響。
4. 來源語言由使用者選擇，目前只提供英、日、韓 → 中文；中文語言模型與繁體轉換不保證所有專有名詞正確。
5. 畫面尺寸與旋轉在工作階段建立時決定；旋轉後如畫面裁切，停止、重新授權擷取。
6. 第一次下載 ML Kit 翻譯模型需要網路及裝置儲存空間。模型就緒後 OCR/翻譯可在裝置端執行。
7. 不應在顯示病患資料、密碼或金融資訊時啟動螢幕擷取；本程式不保存截圖，但執行中記憶體會處理完整畫面。
8. 懸浮球待命使用 `specialUse` 前景服務，提交 Google Play 時需申報用途與經過政策審查；本版本尚未取得政策核准。
9. 此儲存庫是 **MVP 原始碼**，尚未完成實體 Android 裝置相容性與 Play 商店政策審核。不要將 CI 通過誤認成已經能在所有 Android 裝置正常工作。

## 下一版候選項目

- 選取辨識區域、浮窗拖曳與縮放。
- 文字框座標比對，逐段顯示翻譯（目前僅單一底部面板）。
- 更多來源／目標語言；語言偵測，翻譯模型預先管理。
- 旋轉自適應、耗電測量、畫面差異擷取、排除個人敏感 App。
- 懸浮球無障礙操作與跨機型測試。

## 開發管理原則

功能要求會逐一分類為：已證實可行、技術可行但有條件、暫時不可行；不以迎合方式承諾系統不允許的功能。開發中將優先反問隱私、效能、平台限制、失敗恢復與需求驗收標準。

## 官方資料

- [Android MediaProjection](https://developer.android.com/media/grow/media-projection)
- [Android 14 擷取限制](https://developer.android.com/about/versions/14/behavior-changes-14)
- [ML Kit OCR](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
- [ML Kit Translation](https://developers.google.com/ml-kit/language/translation/android)
