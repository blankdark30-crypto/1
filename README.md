# ScreenTranslate v0.4 — Game OCR via Accessibility Screenshots
- Android 11+ uses AccessibilityService.takeScreenshot with canTakeScreenshot=true. No MediaProjection permission or repeated screen-sharing prompt.
- User must explicitly enable Accessibility Service, then separately opt in to game OCR in the app; OFF by default.
- ML Kit recognizes English, Japanese and Korean pixels on device; localized regions are translated to Traditional Chinese. Han-only Chinese text is ignored conservatively.
- Screenshot bitmaps stay in memory, are recycled after OCR, and are not saved or sent to an application server.
- Window overlay is hidden during sampling to avoid OCR feedback. Sample loop is throttled to around one per 2.2 seconds plus processing time.
- When capture fails, fall back to Accessibility UI text-node reading. Android 8–10 supports node-only mode.
- Some secure games or screens cannot be captured. Android screenshot APIs are rate-limited. OCR may fail on tiny, highly stylized or animated fonts.
- Visual OCR can encounter sensitive text. Do not enable it while viewing private medical, banking or credential screens.
- Build/tests pass on CI is NOT proof of functionality on the user's phone. Run physical device tests, including overlay coordinates, rotation, refresh, games and apps.
