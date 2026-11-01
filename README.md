# 三語遊戲 OCR v0.6.1 — Reduced font size, lower flicker

## User-specified function
- User chooses source: English / Korean / Japanese. Output always Chinese converted to Traditional using on-device Transliterator.
- **AccessibilityService.takeScreenshot()** captures screen pixels (Android 11 / API 30+), *not* MediaProjection.
- Target interval is 1000 ms between starts; a new request waits for the previous screenshot and OCR. Android screenshot rate limits and model latency may increase actual time. No guaranteed 1 FPS translation completion.
- ML Kit OCR uses the selected source recognizer. ML Kit Translate downloads the selected source-to-Chinese model if needed.
- Single non-touchable TYPE_ACCESSIBILITY_OVERLAY draws translations near detected positions; draggable control bubble pauses/resumes, long press turns off translation.
- No hidden autostarts: first-time in-app disclosure, user opt-in, Android system accessibility enable. An accessibility service is managed by Android, not a continuously restarted background foreground service.
- Existing v0.5 manual text/paste/screenshot selection remains available.

## v0.6.1 visual polish
- Translation text uses exactly **2sp less** than v0.6 (old 13–22sp -> new 11–20sp where the screen scaling is 1x).
- The floating control ball remains visible during screen capture, reducing visible blinking.
- Reduced pre-capture overlay-hidden settle time from 90 ms to 32 ms. The overlay is still excluded while screenshot is in flight to avoid OCR feedback; **a short flicker may remain** depending on OEM screenshot latency.
- Skip redundant canvas redraws for identical translations and tolerate a single empty OCR frame before clearing stale labels.
- These are visual mitigations, not a promise of flicker-free rendering. Physical-device validation is required.

## Crash mitigation
- Only one screenshot/OCR request at a time. Main thread callbacks, 12-second watchdog, and lifecycle generation invalidation.
- Back off from Android screenshot errors, including ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT.
- After six consecutive failures, pause until the user explicitly taps the bubble.
- HardwareBuffer.close(), Bitmap.recycle(), TextRecognizer.close(), Translator.close() on appropriate lifecycles.
- Avoid OCR feedback: translation canvas and bubble are hidden briefly before each capture.
- Pause on OS settings/security dialogs or focused input/password nodes if the tree provides the information.

## Limits
- Requires Android 11+ for accessibility screenshot API. Older devices retain manual translation.
- Accessibility must be enabled by the user, and screenshot recognition explicitly opted into separately.
- Game DRM / FLAG_SECURE screens may refuse screenshots.
- While enabled, screen pixels can include user-private content; no screenshots are saved and no screenshots are sent to an application server.
- Some overlays can be shifted by navigation/status bars, and highly stylized text may not OCR correctly.
- Google Play requires an accessibility disclosure and policy review before public distribution.
- CI passing does not imply real-device stability.

## Upgrade
- This v0.6 app uses com.yoran.manualtranslator (updates v0.5).
- If the old v0.4 app is still installed and restarting, disable its accessibility service and uninstall it separately.
