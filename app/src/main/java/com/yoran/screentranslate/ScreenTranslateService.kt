package com.yoran.screentranslate

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class ScreenTranslateService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager
    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var translationPanel: LinearLayout? = null
    private var panelBody: TextView? = null
    private var panelHeader: TextView? = null

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var processor: RecognitionPipeline? = null
    private val processing = AtomicBoolean(false)
    private var lastReadAt = 0L
    private var captureGeneration = 0
    private var isCapturing = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "螢幕翻譯", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_BUBBLE -> {
                createBubble()
                showBubbleForeground()
            }
            ACTION_START_CAPTURE -> {
                createBubble()
                val resultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT, Activity.RESULT_CANCELED)
                val resultData = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_PROJECTION_DATA)
                }
                if (resultCode == Activity.RESULT_OK && resultData != null) {
                    startCapture(resultCode, resultData,
                        intent.getStringExtra(EXTRA_SOURCE_LANGUAGE) ?: "en")
                } else {
                    displayStatus("螢幕擷取授權資料無效")
                    stopSelf()
                }
            }
            ACTION_STOP_CAPTURE -> stopCapture()
            ACTION_SHUTDOWN -> {
                stopCapture(removeForeground = false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> { }
        }
        return START_NOT_STICKY // No silent restart or background capture.
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createBubble() {
        if (bubble != null || !Settings.canDrawOverlays(this)) return
        val label = TextView(this).apply {
            text = "譯"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(0xFF635BCE.toInt(), 99f)
            elevation = dp(8).toFloat()
            contentDescription = "螢幕翻譯控制球，點擊切換掃描，長按關閉"
        }
        val params = overlayParams(dp(58), dp(58), Gravity.TOP or Gravity.START).apply {
            x = resources.displayMetrics.widthPixels - dp(82)
            y = resources.displayMetrics.heightPixels / 3
        }
        windowManager.addView(label, params)
        bubble = label
        bubbleParams = params
        setupBubbleTouch(label, params)
    }

    private fun setupBubbleTouch(view: TextView, params: WindowManager.LayoutParams) {
        val threshold = dp(10)
        var touchX = 0f
        var touchY = 0f
        var windowX = 0
        var windowY = 0
        var moved = false
        var longPressed = false
        val longPress = Runnable {
            longPressed = true
            stopCapture()
            stopSelf()
        }
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchX = event.rawX
                    touchY = event.rawY
                    windowX = params.x
                    windowY = params.y
                    moved = false
                    longPressed = false
                    mainHandler.postDelayed(longPress, 650L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (abs(dx) > threshold || abs(dy) > threshold) {
                        moved = true
                        mainHandler.removeCallbacks(longPress)
                    }
                    if (moved) {
                        params.x = (windowX + dx.toInt()).coerceIn(0,
                            (resources.displayMetrics.widthPixels - dp(58)).coerceAtLeast(0))
                        params.y = (windowY + dy.toInt()).coerceAtLeast(0)
                        try { windowManager.updateViewLayout(view, params) } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPress)
                    if (!moved && !longPressed) toggleCapture()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPress)
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleCapture() {
        if (isCapturing) {
            stopCapture()
        } else {
            // A new capture session requires Android's user consent prompt.
            val activityIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(MainActivity.EXTRA_REQUEST_CAPTURE, true)
            }
            startActivity(activityIntent)
        }
    }

    private fun createPanel() {
        if (translationPanel != null || !Settings.canDrawOverlays(this)) return
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            background = rounded(0xF5111827.toInt(), 16f)
            elevation = dp(8).toFloat()
        }
        val title = TextView(this).apply {
            text = "螢幕翻譯 · 辨識中"
            textSize = 14f
            setTextColor(0xFFB5A9FF.toInt())
        }
        holder.addView(title)
        val body = TextView(this).apply {
            text = "等待畫面中的文字…"
            textSize = 16f
            setTextColor(Color.WHITE)
            setTextIsSelectable(false)
            setPadding(0, dp(8), 0, 0)
        }
        val scroll = ScrollView(this).apply {
            addView(body)
        }
        holder.addView(scroll, LinearLayout.LayoutParams(-1, dp(175)))
        val close = TextView(this).apply {
            text = "收起翻譯面板（點懸浮球可重新掃描）"
            textSize = 12f
            setTextColor(0xFFD1D5DB.toInt())
            setPadding(0, dp(8), 0, 0)
            setOnClickListener { holder.visibility = View.GONE }
        }
        holder.addView(close)
        val width = (resources.displayMetrics.widthPixels - dp(32)).coerceAtMost(dp(370))
        val params = overlayParams(width, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            y = dp(70)
        }
        windowManager.addView(holder, params)
        translationPanel = holder
        panelBody = body
        panelHeader = title
    }

    private fun startCapture(resultCode: Int, resultData: Intent, sourceLanguage: String) {
        // Foreground service must be started before getMediaProjection() on modern Android.
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, captureNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, captureNotification())
            }
        } catch (e: Exception) {
            displayStatus("前景服務啟動失敗：${e.message ?: "未知原因"}")
            stopSelf()
            return
        }
        stopCapture(removeForeground = false)
        captureGeneration++
        val currentGeneration = captureGeneration
        isCapturing = true
        bubble?.text = "停"
        createPanel()
        translationPanel?.visibility = View.VISIBLE
        displayStatus("準備擷取畫面…")
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        try {
            val proj = manager.getMediaProjection(resultCode, resultData)
                ?: throw IllegalStateException("MediaProjection unavailable")
            projection = proj
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    mainHandler.post {
                        if (captureGeneration == currentGeneration) {
                            displayStatus("系統已停止分享畫面")
                            stopCapture()
                        }
                    }
                }
            }
            projectionCallback = callback
            proj.registerCallback(callback, mainHandler)
            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi
            val thread = HandlerThread("screen-capture-reader").also { it.start() }
            captureThread = thread
            val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            imageReader = reader
            processor = RecognitionPipeline(sourceLanguage,
                onStatus = { if (captureGeneration == currentGeneration) displayStatus(it) },
                onTranslation = {
                    if (captureGeneration == currentGeneration) displayTranslation(it)
                })
            reader.setOnImageAvailableListener({ source ->
                onImageAvailable(source, currentGeneration)
            }, Handler(thread.looper))
            virtualDisplay = proj.createVirtualDisplay(
                "ScreenTranslate-UserApproved", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, Handler(thread.looper)
            )
        } catch (e: Exception) {
            displayStatus("螢幕擷取失敗：${e.message ?: "未知原因"}")
            stopCapture()
        }
    }

    private fun onImageAvailable(reader: ImageReader, generation: Int) {
        val image = try { reader.acquireLatestImage() } catch (_: Exception) { null } ?: return
        try {
            val now = SystemClock.elapsedRealtime()
            if (!isCapturing || generation != captureGeneration ||
                now - lastReadAt < FRAME_INTERVAL_MS || !processing.compareAndSet(false, true)
            ) return
            lastReadAt = now
            val plane = image.planes[0]
            val width = image.width
            val height = image.height
            val stridePixels = plane.rowStride / plane.pixelStride
            val large = Bitmap.createBitmap(stridePixels, height, Bitmap.Config.ARGB_8888)
            large.copyPixelsFromBuffer(plane.buffer)
            val bitmap = Bitmap.createBitmap(large, 0, 0, width, height)
            large.recycle()
            val p = processor
            if (p == null) {
                bitmap.recycle()
                processing.set(false)
            } else {
                mainHandler.post {
                    if (generation != captureGeneration) {
                        bitmap.recycle()
                        processing.set(false)
                    } else {
                        p.recognize(bitmap) { processing.set(false) }
                    }
                }
            }
        } catch (e: Exception) {
            processing.set(false)
            if (generation == captureGeneration) displayStatus("畫面處理失敗：${e.message ?: "未知原因"}")
        } finally {
            image.close()
        }
    }

    private fun stopCapture(removeForeground: Boolean = true) {
        captureGeneration++
        isCapturing = false
        bubble?.text = "譯"
        val oldReader = imageReader
        imageReader = null
        try { oldReader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        virtualDisplay = null
        try { oldReader?.close() } catch (_: Exception) {}
        try { captureThread?.quitSafely() } catch (_: Exception) {}
        captureThread = null
        processor?.close()
        processor = null
        val currentProjection = projection
        projection = null
        try { currentProjection?.stop() } catch (_: Exception) {}
        projectionCallback = null
        processing.set(false)
        translationPanel?.visibility = View.GONE
        if (removeForeground) {
            if (bubble != null) showBubbleForeground()
            else stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun displayStatus(message: String) = mainHandler.post {
        panelHeader?.text = "螢幕翻譯 · 狀態"
        panelBody?.text = message
    }.let { Unit }

    private fun displayTranslation(message: String) = mainHandler.post {
        panelHeader?.text = "螢幕翻譯 · 繁體中文"
        panelBody?.text = message.ifBlank { "目前畫面未辨識到文字" }
    }.let { Unit }

    private fun overlayParams(width: Int, height: Int, gravityValue: Int) =
        WindowManager.LayoutParams(width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT).apply { gravity = gravityValue }

    private fun rounded(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * resources.displayMetrics.density
        }

    private fun showBubbleForeground() {
        if (bubble == null) return
        try {
            val close = Intent(this, ScreenTranslateService::class.java).apply {
                action = ACTION_SHUTDOWN
            }
            val pending = PendingIntent.getService(this, 1, close,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val idle = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("螢幕翻譯球待命")
                .setContentText("懸浮球已開啟；點擊可授權螢幕翻譯")
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setOngoing(true)
                .addAction(Notification.Action.Builder(null, "關閉懸浮球", pending).build())
                .build()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, idle, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, idle)
            }
        } catch (_: Exception) {
            // Some device ROMs may restrict this FGS type; the bubble can be evicted.
        }
    }

    private fun captureNotification(): Notification {
        val stop = Intent(this, ScreenTranslateService::class.java).apply { action = ACTION_STOP_CAPTURE }
        val pending = PendingIntent.getService(this, 0, stop,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("螢幕翻譯正在執行")
            .setContentText("擷取畫面並在本機辨識翻譯；按暫停可停止")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止擷取", pending).build())
            .build()
    }

    override fun onDestroy() {
        stopCapture(removeForeground = false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        try { bubble?.let(windowManager::removeView) } catch (_: Exception) {}
        try { translationPanel?.let(windowManager::removeView) } catch (_: Exception) {}
        bubble = null
        translationPanel = null
        panelBody = null
        panelHeader = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val ACTION_SHOW_BUBBLE = "com.yoran.screentranslate.SHOW_BUBBLE"
        const val ACTION_START_CAPTURE = "com.yoran.screentranslate.START_CAPTURE"
        const val ACTION_STOP_CAPTURE = "com.yoran.screentranslate.STOP_CAPTURE"
        const val ACTION_SHUTDOWN = "com.yoran.screentranslate.SHUTDOWN"
        const val EXTRA_PROJECTION_RESULT = "projection_result"
        const val EXTRA_PROJECTION_DATA = "projection_data"
        const val EXTRA_SOURCE_LANGUAGE = "source_language"
        private const val CHANNEL_ID = "screen_translate"
        private const val NOTIFICATION_ID = 739
        private const val FRAME_INTERVAL_MS = 1500L
    }
}
