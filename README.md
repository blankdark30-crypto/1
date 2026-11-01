# 三語翻譯器 v0.5.0 — No accessibility, overlays, or screen capture

Simplified native Android application. The source languages are **English, Korean, and Japanese**, chosen manually; the target is Chinese with on-device simplified-to-traditional conversion.

## Features
- Type or paste text to translate.
- Explicit **Paste and Translate** button reads the clipboard only when tapped.
- Select a saved screenshot/photo via Android document/image picker to recognize it using on-device ML Kit OCR; recognized text is inserted into the editor and translated automatically.
- Share selected text or images from another app using the Android share sheet.
- Copy translated Chinese.
- Offline translation after ML Kit language models have been downloaded. First usage requires internet and storage for model download.
- State/status feedback when models, OCR or translation fail.
- One foreground activity; no service, no periodic task, no auto-restarts, no accessibility/overlay/screen-sharing permission.

## Important tradeoff
This application **cannot** automatically read or overlay a running game's screen. To translate game text, capture a screenshot manually and select it in the app, or share the screenshot into the app. Automatic cross-app capture would require a different Android privileged or user-consented screen capture mechanism.

## Privacy
Only manually entered, pasted, selected or explicitly shared content is processed.
OCR and translation run through the device ML Kit SDK; model download needs network. No screenshots are stored by the app. No user content is sent to an application-owned server. Screenshots you create manually remain in your phone's photo storage until you delete them.

## Installing
- Supports Android 8.0+.
- **Uninstall the older v0.4 "螢幕翻譯球" and disable its accessibility service** to stop its crash/restart loop. The new app has a different application ID (\`com.yoran.manualtranslator\`) and is independently installed.
- Pick the source language before translating.
- Text: enter or tap Paste and Translate.
- Game: take screenshot using the phone's normal screenshot function, open this app, choose image, and translate.

## Verification
GitHub Actions runs \`:app:testDebugUnitTest\` and \`:app:assembleDebug\`; compiling does not establish real-device crash-free operation.
