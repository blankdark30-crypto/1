# ScreenTranslate v0.3.1 — Auto-detected UI text translation

## What changed
- **No screen sharing** or MediaProjection: relies on Android's AccessibilityService to obtain exposed UI text nodes and their bounding rectangles.
- Translations render using TYPE_ACCESSIBILITY_OVERLAY, not SYSTEM_ALERT_WINDOW.
- Automatically starts reading after the user manually enables accessibility service. User must first acknowledge an in-app, prominent consent disclosure.
- Floating translation bubble lets you pause/resume; long-press disables the service.
- Automatically routes English Latin-script, Japanese kana and Korean Hangul to their matching local ML Kit model. Chinese (Han-only) text is ignored rather than retranslating Chinese.
- Chinese output is converted toward Traditional Chinese. Per-language models are downloaded only when needed.
- Manual source language selection remains available; v0.3 installs migrate once to auto detection.
- No screenshot storage or server text uploads. Password and editable nodes are intentionally skipped.
- Coordinates are from getBoundsInScreen. Background translated labels are non-touchable and drawn in one overlay.

## Language-detection tradeoff
- Han-only Japanese (such as 東京都) is deliberately skipped because it cannot reliably be distinguished from Chinese using Unicode script alone. Mixed English and Han text is also skipped to avoid modifying Chinese UI.
- Script detection is not a statistical language classifier; Latin text in other languages may be mistaken for English.

## Critical limitations
- Cannot read pixels, videos, rasterized games, image text or secure content. Text nodes not exposed to accessibility APIs will not be translated.
- Does not bypass Android's consent or restricted settings. Sideloaded apps may require explicit permission for restricted accessibility settings on some Android releases.
- OS, banking, payment, passwords and other sensitive flows should be excluded from use; no guarantee all sensitive text is automatically identified.
- The system decides when accessibility events occur; this is not a continuous frame-by-frame translator.
- Device testing required; Google Play accessibility API disclosure and policy review required.

## Install and use
1. Install the debug APK.
2. Launch and select automatic language detection (default) or explicitly choose one source language.
3. Read the prominent disclosure and press the consent button to navigate to Accessibility settings.
4. Manually enable **螢幕文字原位翻譯** in Android Accessibility settings.
5. Switch to another app exposing text nodes; translated text appears in place.
6. Tap the bubble to pause/resume or long-press to disable.

## Privacy and validation
Do not use while medical records, patient information, bank details or passwords are visible.
Needs physical Android device validation for OEM ROMs, window geometry, overlap, and app compatibility.
CI compiles and tests; it does not prove device compatibility.
