package com.yoran.screentranslate

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.SharedPreferences
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(this)
        privacyApproved = prefs.getBoolean("accessibility_disclosure_accepted", false)
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
        if (pkg == packageName || pkg == "com.android.systemui" ||
            pkg.contains("permissioncontroller") || pkg == "com.android.settings") {
            overlay.clear()
            return
        }
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
        if (scheduled || paused || !::overlay.isInitialized) return
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
        if (pkg == packageName || pkg == "com.android.systemui" ||
            pkg.contains("permissioncontroller") || pkg == "com.android.settings") {
            overlay.clear()
            return
        }
        currentPackage = pkg
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
                        if (paused) overlay.clear() else scheduleScan()
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
    }

    override fun onInterrupt() {
        if (::overlay.isInitialized) overlay.clear()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
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
