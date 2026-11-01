package com.yoran.screentranslate

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import kotlin.math.abs

/**
 * User-consented Accessibility screenshot -> OCR -> translation overlay.
 * It does not use MediaProjection, no background restart intents, no recordings and
 * never processes more than one screenshot/OCR task at a time.
 */
class OneSecondTranslationService : AccessibilityService(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var wm: WindowManager
    private var layer: OverlayCanvas? = null
    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var recognizer: ScreenshotOcr? = null
    private var translator: FrameTranslator? = null
    private var connected = false
    private var stopped = false
    private var enabled = false
    private var paused = false
    private var inFlight = false
    private var tickScheduled = false
    private var failures = 0
    private var generation = 0
    private var startedAt = 0L
    private var activePackage = ""
    private var status = "就緒"
    private var watchdog: Runnable? = null
    private var ticker: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (connected) return
        connected = true
        stopped = false
        try {
            prefs = getSharedPreferences("manual_translate_v05", Context.MODE_PRIVATE)
            prefs.registerOnSharedPreferenceChangeListener(this)
            wm = getSystemService(WINDOW_SERVICE) as WindowManager
            recognizer = ScreenshotOcr()
            translator = FrameTranslator(
                onOutput = { items ->
                    if (connected && enabled && !paused) layer?.update(items)
                },
                onStatus = { message -> setStatus(message) }
            )
            applyPreferences()
        } catch (_: Exception) {
            // Never force-restart a broken accessibility service.
            enabled = false
            paused = true
            stopScanning()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!enabled || paused || event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || forbidden(pkg)) {
            activePackage = ""
            layer?.clear()
            return
        }
        activePackage = pkg
        if (!inFlight && !tickScheduled) scheduleTick(150L)
    }

    private fun forbidden(pkg: String): Boolean =
        pkg.isBlank() || pkg == packageName ||
            pkg == "com.android.systemui" || pkg == "com.android.settings" ||
            pkg.contains("permissioncontroller", true) ||
            pkg.contains("inputmethod", true) || pkg.contains("keyguard", true)

    private fun applyPreferences() {
        if (!connected || !::prefs.isInitialized) return
        val wanted = prefs.getBoolean("ocr_active", false) &&
            prefs.getBoolean("ocr_disclosure_accepted", false)
        val language = prefs.getString("source", "en") ?: "en"
        if (!wanted) {
            enabled = false
            paused = false
            stopScanning()
            removeWindows()
            return
        }
        if (Build.VERSION.SDK_INT < 30) {
            enabled = false
            setStatus("遊戲畫面 OCR 需要 Android 11 或更新版本")
            return
        }
        if (!enabled) {
            enabled = true
            paused = false
            try {
                attachWindows()
            } catch (_: Exception) {
                enabled = false
                setStatus("無法建立翻譯覆蓋層，請關閉再重新開啟服務")
                removeWindows()
                return
            }
        }
        translator?.updateLanguage(language)
        if (activePackage.isBlank()) {
            activePackage = rootInActiveWindow?.packageName?.toString().orEmpty()
            if (forbidden(activePackage)) activePackage = ""
        }
        scheduleTick(180L)
    }

    private fun attachWindows() {
        if (layer == null) {
            layer = OverlayCanvas(this, wm).also { it.attach() }
        }
        if (bubble == null) {
            val view = TextView(this).apply {
                text = "譯"
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(0xFF6156C4.toInt())
                    cornerRadius = dp(50).toFloat()
                }
                elevation = dp(5).toFloat()
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val display = resources.displayMetrics
            val params = WindowManager.LayoutParams(
                dp(54), dp(54), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = (display.widthPixels - dp(70)).coerceAtLeast(0)
                y = display.heightPixels / 3
            }
            wm.addView(view, params)
            bubble = view
            bubbleParams = params
            installBubbleTouch(view, params)
        }
        setStatus(status)
    }

    private fun installBubbleTouch(view: TextView, params: WindowManager.LayoutParams) {
        var ox = 0
        var oy = 0
        var rawX = 0f
        var rawY = 0f
        var dragging = false
        var longPressed = false
        val hold = Runnable {
            longPressed = true
            // Do not disableSelf(): that can lead to repeated service reconnects on OEM ROMs.
            if (::prefs.isInitialized) prefs.edit().putBoolean("ocr_active", false).apply()
        }
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    rawX = event.rawX
                    rawY = event.rawY
                    ox = params.x
                    oy = params.y
                    dragging = false
                    longPressed = false
                    main.postDelayed(hold, 850L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - rawX
                    val dy = event.rawY - rawY
                    if (abs(dx) > dp(10) || abs(dy) > dp(10)) {
                        dragging = true
                        main.removeCallbacks(hold)
                    }
                    if (dragging) {
                        params.x = (ox + dx.toInt()).coerceIn(0,
                            (resources.displayMetrics.widthPixels - dp(54)).coerceAtLeast(0))
                        params.y = (oy + dy.toInt()).coerceAtLeast(0)
                        try { wm.updateViewLayout(view, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(hold)
                    if (!dragging && !longPressed) {
                        paused = !paused
                        view.text = if (paused) "▶" else "譯"
                        setStatus(if (paused) "已暫停" else "繼續翻譯")
                        if (paused) {
                            generation++
                            stopScanning()
                            layer?.clear()
                        } else scheduleTick(100L)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(hold)
                    true
                }
                else -> false
            }
        }
    }

    private fun scheduleTick(delay: Long) {
        if (stopped || !enabled || paused || inFlight || tickScheduled) return
        tickScheduled = true
        val runnable = Runnable {
            tickScheduled = false
            ticker = null
            if (!stopped && enabled && !paused) scanOnce()
        }
        ticker = runnable
        main.postDelayed(runnable, delay)
    }

    private fun scanOnce() {
        if (!enabled || paused || inFlight || !connected) return
        val foreground = rootInActiveWindow?.packageName?.toString().orEmpty()
        if (foreground.isNotBlank()) {
            if (forbidden(foreground)) {
                activePackage = ""
                layer?.clear()
                scheduleTick(ScanIntervalPolicy.TARGET_INTERVAL_MS)
                return
            }
            activePackage = foreground
        }
        val packageAtCapture = activePackage
        if (forbidden(packageAtCapture)) {
            layer?.clear()
            scheduleTick(ScanIntervalPolicy.TARGET_INTERVAL_MS)
            return
        }
        if (hasFocusedSensitiveInput()) {
            layer?.clear()
            scheduleTick(ScanIntervalPolicy.TARGET_INTERVAL_MS)
            return
        }
        inFlight = true
        val token = ++generation
        startedAt = SystemClock.elapsedRealtime()
        // Only the translated text needs exclusion from OCR. Keep the control
        // bubble visible to avoid a second conspicuous 1 Hz blink.
        layer?.hide()

        // If Android never delivers an OCR callback, return to a safe state.
        val timeout = Runnable {
            if (token == generation && inFlight) {
                finishCapture(false, token)
                generation++
                setStatus("辨識逾時，延後重試")
            }
        }
        watchdog = timeout
        main.postDelayed(timeout, 12000L)
        main.postDelayed({
            if (!isActive(token, packageAtCapture)) {
                finishCapture(false, token)
                return@postDelayed
            }
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(result: ScreenshotResult) {
                            if (!isActive(token, packageAtCapture)) {
                                result.hardwareBuffer.close()
                                return
                            }
                            val image = try {
                                val buffer = result.hardwareBuffer
                                try {
                                    Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                        ?.copy(Bitmap.Config.ARGB_8888, false)
                                } finally { buffer.close() }
                            } catch (_: Exception) { null }
                            layer?.show()
                            bubble?.visibility = View.VISIBLE
                            if (image == null) {
                                setStatus("畫面無法讀取")
                                finishCapture(false, token)
                                return
                            }
                            val language = prefs.getString("source", "en") ?: "en"
                            try {
                                recognizer?.process(image, language) { frame ->
                                    if (isActive(token, packageAtCapture)) {
                                        val o = layer
                                        if (o != null) {
                                            translator?.onFrame(frame, o.width, o.height)
                                            setStatus("每秒掃描 · " + frame.lines.size + " 行文字")
                                        }
                                    }
                                    finishCapture(true, token)
                                } ?: run { image.recycle(); finishCapture(false, token) }
                            } catch (_: Exception) {
                                image.recycle()
                                finishCapture(false, token)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            if (!isActive(token, packageAtCapture)) return
                            setStatus(if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT)
                                "系統限制截圖頻率，已延後" else "畫面擷取受限（$errorCode）")
                            finishCapture(false, token)
                        }
                    })
            } catch (_: Exception) {
                setStatus("遊戲畫面無法擷取")
                finishCapture(false, token)
            }
        // Shorten the compositor settling interval; the layer remains hidden
        // until the screenshot callback, rather than through the OCR work.
        }, 32L)
    }

    private fun hasFocusedSensitiveInput(): Boolean {
        val root = rootInActiveWindow ?: return false // Game engine may not expose a text tree.
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        var visited = 0
        return try {
            while (pending.isNotEmpty() && visited < 140) {
                val node = pending.removeFirst()
                visited++
                if (node.isPassword || (node.isEditable && node.isFocused)) return true
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { pending.add(it) }
                }
            }
            false
        } catch (_: Exception) {
            true // Fail closed if UI tree cannot be checked safely.
        }
    }

    private fun isActive(token: Int, packageAtCapture: String): Boolean =
        token == generation && connected && !stopped && enabled && !paused &&
            packageAtCapture.isNotBlank() && packageAtCapture == activePackage

    private fun finishCapture(success: Boolean, token: Int) {
        if (token != generation) return
        watchdog?.let(main::removeCallbacks)
        watchdog = null
        layer?.show()
        bubble?.visibility = View.VISIBLE
        if (!inFlight) return
        inFlight = false
        failures = if (success) 0 else (failures + 1).coerceAtMost(8)
        if (failures >= 6) {
            paused = true
            layer?.clear()
            bubble?.text = "▶"
            setStatus("連續失敗，已暫停；點懸浮球重試")
            return
        }
        val delay = ScanIntervalPolicy.nextDelay(
            startedAt, SystemClock.elapsedRealtime(), failures)
        scheduleTick(delay)
    }

    private fun stopScanning() {
        generation++
        tickScheduled = false
        ticker?.let(main::removeCallbacks)
        ticker = null
        watchdog?.let(main::removeCallbacks)
        watchdog = null
        inFlight = false
        failures = 0
        layer?.clear()
        layer?.show()
        bubble?.visibility = View.VISIBLE
    }

    private fun removeWindows() {
        layer?.detach()
        layer = null
        try { bubble?.let(wm::removeView) } catch (_: Exception) {}
        bubble = null
        bubbleParams = null
    }

    private fun setStatus(message: String) {
        status = message
        bubble?.contentDescription = if (paused) "翻譯已暫停" else
            "掃描翻譯中；" + message + "。點擊暫停，長按關閉"
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (key == "source" || key == "ocr_active" || key == "ocr_disclosure_accepted") {
            applyPreferences()
        }
    }

    override fun onInterrupt() { layer?.clear() }

    override fun onDestroy() {
        stopped = true
        connected = false
        stopScanning()
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(this)
        translator?.close()
        recognizer?.close()
        translator = null
        recognizer = null
        if (::wm.isInitialized) removeWindows()
        super.onDestroy()
    }

    private fun dp(p: Int) = (p * resources.displayMetrics.density).toInt()
}
