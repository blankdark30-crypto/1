package com.yoran.screentranslate

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import android.graphics.Color
import android.graphics.Rect
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import kotlin.math.abs

/** Autostarts only after the user explicitly enables this AccessibilityService in Android settings. */
class AccessibilityTranslationService : AccessibilityService(),
    SharedPreferences.OnSharedPreferenceChangeListener {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var overlay: AccessibilityOverlay
    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var engine: AccessibilityTranslator? = null
    private var paused = false
    private var scheduled = false
    private var lastScan = 0L
    private var activeLanguage = ""
    private var status = "等待文字"
    private var currentPackage = ""
    private var privacyApproved = false
    private var visualOcrEnabled = false
    private var screenBusy = false
    private var screenshotGeneration = 0
    private var visualOcr: VisualScreenOcr? = null
    private var activeForegroundPackage = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(this)
        privacyApproved = prefs.getBoolean("accessibility_disclosure_accepted", false)
        visualOcrEnabled = Build.VERSION.SDK_INT >= 30 &&
            prefs.getBoolean("visual_screen_ocr_consent", false)
        if (!privacyApproved) {
            // Enabling the service directly in Android Settings is not in-app consent.
            disableSelf()
            return
        }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        overlay = AccessibilityOverlay(this, wm)
        try {
            overlay.attach()
            createBubble()
            visualOcr = if (Build.VERSION.SDK_INT >= 30) VisualScreenOcr() else null
            configureEngine()
            scheduleScan()
        } catch (_: Exception) {
            // Some vendor ROMs disallow overlays even to enabled services.
            try { overlay.detach() } catch (_: Exception) {}
            disableSelf()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || paused || !privacyApproved || !::overlay.isInitialized) return
        val pkg = event.packageName?.toString().orEmpty()
        if (blockedPackage(pkg)) {
            activeForegroundPackage = pkg
            overlay.clear()
            return
        }
        activeForegroundPackage = pkg
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            scheduleScan()
        }
    }

    private fun configureEngine() {
        if (!::prefs.isInitialized || !::overlay.isInitialized) return
        val language = prefs.getString("source", "auto") ?: "auto"
        if (language == activeLanguage && engine != null) return
        activeLanguage = language
        engine?.close()
        overlay.clear()
        engine = AccessibilityTranslator(
            onResult = { if (!paused && ::overlay.isInitialized) overlay.show(it) },
            onStatus = { message -> status = message; updateBubbleDescription() })
        scheduleScan()
    }

    private fun scheduleScan() {
        if (scheduled || paused || screenBusy || !::overlay.isInitialized) return
        scheduled = true
        val elapsed = SystemClock.elapsedRealtime() - lastScan
        handler.postDelayed({
            scheduled = false
            if (!paused) scan()
        }, maxOf(300L, 500L - elapsed))
    }

    private fun scan() {
        if (!::overlay.isInitialized) return
        lastScan = SystemClock.elapsedRealtime()
        val root = rootInActiveWindow
        if (root == null) {
            overlay.clear()
            return
        }
        val pkg = root.packageName?.toString().orEmpty()
        if (blockedPackage(pkg) || (activeForegroundPackage.isNotEmpty() &&
                blockedPackage(activeForegroundPackage))) {
            overlay.clear()
            return
        }
        currentPackage = pkg
        if (visualOcrEnabled && Build.VERSION.SDK_INT >= 30) {
            if (hasFocusedSensitiveInput(root)) {
                overlay.clear()
                scheduleVisualRefresh()
                return
            }
            captureVisualText(pkg)
            return
        }
        scanTextNodes(root)
    }

    private fun scanTextNodes(root: AccessibilityNodeInfo) {
        val nodes = ArrayDeque<AccessibilityNodeInfo>()
        val regions = ArrayList<SourceRegion>()
        val seen = HashSet<String>()
        nodes.add(root)
        var visited = 0
        try {
            while (nodes.isNotEmpty() && visited < 350 && regions.size < 18) {
                val node = nodes.removeFirst()
                visited++
                if (!node.isVisibleToUser || node.isPassword || node.isEditable) continue
                val rect = Rect()
                node.getBoundsInScreen(rect)
                val box = PixelBox(rect.left, rect.top, rect.right, rect.bottom)
                val source = AccessibleTextFilter.normalize(
                    node.text?.toString(), node.isPassword, node.isEditable, box)
                if (source != null) {
                    val detected = SourceLanguageDetector.select(source, activeLanguage)
                    if (detected != null) {
                        val key = detected + ":" + source + ":" + rect.left + ":" + rect.top
                        if (seen.add(key)) regions.add(SourceRegion(source, detected, box))
                    }
                }
                for (i in 0 until node.childCount) {
                    try { node.getChild(i)?.let(nodes::add) } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {
            // Accessibility hierarchies may disappear as windows change.
        }
        engine?.submit(regions)
    }


    private fun blockedPackage(value: String): Boolean =
        value.isBlank() || value == packageName || value == "com.android.systemui" ||
            value == "com.android.settings" || value.contains("permissioncontroller") ||
            value.startsWith("com.google.android.permissioncontroller")

    private fun hasFocusedSensitiveInput(root: AccessibilityNodeInfo): Boolean {
        val nodes = ArrayDeque<AccessibilityNodeInfo>()
        nodes.add(root)
        var visited = 0
        try {
            while (nodes.isNotEmpty() && visited < 120) {
                val node = nodes.removeFirst()
                visited++
                if (node.isPassword || (node.isEditable && node.isFocused)) return true
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { nodes.add(it) }
                }
            }
        } catch (_: Exception) { return true } // Fail closed on permission/transition errors.
        return false
    }

    private fun captureVisualText(packageAtStart: String) {
        if (Build.VERSION.SDK_INT < 30 || screenBusy || paused) return
        screenBusy = true
        val generation = ++screenshotGeneration
        overlay.hideForCapture(true)
        bubble?.visibility = View.INVISIBLE
        // Allow the system compositor to present a frame without our overlay.
        handler.postDelayed({
            if (!isCaptureValid(generation, packageAtStart)) {
                finishVisualCapture(generation)
                return@postDelayed
            }
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            if (!isCaptureValid(generation, packageAtStart)) {
                                result.hardwareBuffer.close()
                                finishVisualCapture(generation)
                                return
                            }
                            val bitmap: Bitmap? = try {
                                val buffer = result.hardwareBuffer
                                try {
                                    val wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                    wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                                } finally {
                                    buffer.close()
                                }
                            } catch (_: Exception) { null }
                            if (bitmap == null) {
                                updateStatus("無法讀取畫面像素")
                                finishVisualCapture(generation)
                                return
                            }
                            val recognizer = visualOcr
                            if (recognizer == null) {
                                bitmap.recycle()
                                finishVisualCapture(generation)
                                return
                            }
                            recognizer.recognize(bitmap, activeLanguage) { regions, w, h ->
                                if (isCaptureValid(generation, packageAtStart)) {
                                    val scaled = regions.mapNotNull { item ->
                                        val mapped = RegionCoordinates.map(
                                            item.box, w, h, overlay.viewWidth, overlay.viewHeight)
                                        mapped?.let { item.copy(box = it) }
                                    }
                                    engine?.submit(scaled)
                                    if (scaled.isEmpty()) updateStatus("畫面 OCR 未辨識到文字，請檢查遊戲字體或安全限制")
                                    else updateStatus("畫面辨識 " + scaled.size + " 行，翻譯中")
                                }
                                finishVisualCapture(generation)
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            if (isCaptureValid(generation, packageAtStart)) {
                                updateStatus("畫面截取失敗（代碼 " + errorCode + "）")
                                // If OEM blocks screenshots, retain node-based translation.
                                rootInActiveWindow?.let { scanTextNodes(it) }
                            }
                            finishVisualCapture(generation)
                        }
                    })
            } catch (error: Exception) {
                updateStatus("無障礙截圖不可用：" + (error.message ?: "請重新啟用服務"))
                finishVisualCapture(generation)
            }
        }, 180L)
    }

    private fun isCaptureValid(generation: Int, pkg: String): Boolean =
        generation == screenshotGeneration && !paused && visualOcrEnabled &&
            !blockedPackage(activeForegroundPackage) &&
            activeForegroundPackage == pkg && ::overlay.isInitialized

    private fun updateStatus(value: String) {
        status = value
        updateBubbleDescription()
    }

    private fun finishVisualCapture(generation: Int) {
        // If paused, don't resume hidden translation overlays.
        if (generation != screenshotGeneration) return
        screenBusy = false
        if (::overlay.isInitialized) overlay.hideForCapture(false)
        bubble?.visibility = View.VISIBLE
        if (!paused && visualOcrEnabled) scheduleVisualRefresh()
    }

    private fun scheduleVisualRefresh() {
        if (paused || !visualOcrEnabled) return
        handler.postDelayed({
            if (!paused && visualOcrEnabled && !screenBusy) scheduleScan()
        }, 2200L)
    }

    private fun createBubble() {
        if (bubble != null) return
        val view = TextView(this).apply {
            text = "譯"
            textSize = 21f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(0xFF635BCE.toInt())
                cornerRadius = dp(40).toFloat()
            }
            elevation = dp(7).toFloat()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val metrics = resources.displayMetrics
        val params = WindowManager.LayoutParams(
            dp(54), dp(54), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (metrics.widthPixels - dp(75)).coerceAtLeast(0)
            y = metrics.heightPixels / 3
        }
        wm.addView(view, params)
        bubble = view
        bubbleParams = params
        updateBubbleDescription()
        attachBubbleTouch(view, params)
    }

    private fun updateBubbleDescription() {
        bubble?.contentDescription = if (paused) "翻譯已暫停，點擊恢復"
            else "翻譯執行中；" + status + "。點擊暫停，長按關閉服務"
    }

    private fun attachBubbleTouch(view: TextView, params: WindowManager.LayoutParams) {
        var firstX = 0f
        var firstY = 0f
        var oldX = 0
        var oldY = 0
        var moved = false
        var hold = false
        val longPress = Runnable {
            hold = true
            disableSelf()
        }
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    firstX = event.rawX
                    firstY = event.rawY
                    oldX = params.x
                    oldY = params.y
                    moved = false
                    hold = false
                    handler.postDelayed(longPress, 650)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - firstX
                    val dy = event.rawY - firstY
                    if (abs(dx) > dp(9) || abs(dy) > dp(9)) {
                        moved = true
                        handler.removeCallbacks(longPress)
                    }
                    if (moved) {
                        params.x = (oldX + dx.toInt()).coerceIn(0,
                            (resources.displayMetrics.widthPixels - dp(54)).coerceAtLeast(0))
                        params.y = (oldY + dy.toInt()).coerceAtLeast(0)
                        try { wm.updateViewLayout(view, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    if (!moved && !hold) {
                        paused = !paused
                        view.text = if (paused) "▶" else "譯"
                        updateBubbleDescription()
                        if (paused) {
                            screenshotGeneration++
                            screenBusy = false
                            overlay.clear()
                            overlay.hideForCapture(false)
                            view.visibility = View.VISIBLE
                        } else scheduleScan()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    true
                }
                else -> false
            }
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == "source") configureEngine()
        if (key == "visual_screen_ocr_consent") {
            visualOcrEnabled = Build.VERSION.SDK_INT >= 30 &&
                prefs.getBoolean("visual_screen_ocr_consent", false)
            screenshotGeneration++
            screenBusy = false
            overlay.hideForCapture(false)
            bubble?.visibility = View.VISIBLE
            overlay.clear()
            scheduleScan()
        }
    }

    override fun onInterrupt() {
        if (::overlay.isInitialized) overlay.clear()
    }

    override fun onDestroy() {
        screenshotGeneration++
        handler.removeCallbacksAndMessages(null)
        visualOcr?.close()
        visualOcr = null
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(this)
        engine?.close()
        engine = null
        if (::overlay.isInitialized) overlay.detach()
        if (::wm.isInitialized) {
            try { bubble?.let(wm::removeView) } catch (_: Exception) {}
        }
        bubble = null
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
