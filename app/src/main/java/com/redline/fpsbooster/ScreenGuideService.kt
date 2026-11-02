package com.redline.fpsbooster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import kotlin.math.max

/**
 * User-approved screen guide. The service only captures the screen for local OCR
 * and draws a non-touchable pointer overlay. It never injects touches or changes settings.
 */
class ScreenGuideService : Service() {
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var workerHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var overlay: GuideOverlayView? = null
    private var isProcessing = false
    private var lastProcessedAt = 0L
    private var cleaningUp = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    private lateinit var windowManager: WindowManager

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_STICKY

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    if (!cleaningUp) stopSelf()
                }
            }
            projectionCallback = callback
            projection?.registerCallback(callback, mainHandler)
            startScreenAnalysis()
            showOverlay()
        } catch (_: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startScreenAnalysis() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi
        worker = HandlerThread("RedlineScreenOCR").also { it.start() }
        workerHandler = Handler(worker!!.looper)
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val now = SystemClock.elapsedRealtime()
            if (isProcessing || now - lastProcessedAt < 1100L) {
                image.close()
                return@setOnImageAvailableListener
            }
            isProcessing = true
            lastProcessedAt = now
            val bitmap = imageToBitmap(image)
            image.close()
            if (bitmap == null) {
                isProcessing = false
                return@setOnImageAvailableListener
            }
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    val match = findGuideMatch(result)
                    mainHandler.post {
                        overlay?.updateGuide(
                            match?.bounds,
                            match?.message ?: "Keine passende Einstellung erkannt. Öffne Einstellungen oder das Roblox-Menü."
                        )
                    }
                }
                .addOnFailureListener {
                    mainHandler.post { overlay?.updateGuide(null, "Bildschirm wird geprüft. Bitte halte die Einstellungsseite kurz offen.") }
                }
                .addOnCompleteListener {
                    if (!bitmap.isRecycled) bitmap.recycle()
                    isProcessing = false
                }
        }, workerHandler)

        virtualDisplay = projection?.createVirtualDisplay(
            "REDLINE Screen Guide",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            workerHandler
        )
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val plane = image.planes.firstOrNull() ?: return null
            val pixelStride = plane.pixelStride
            if (pixelStride < 4) return null
            val rowPadding = plane.rowStride - pixelStride * image.width
            val paddedWidth = image.width + max(0, rowPadding / pixelStride)
            val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
            val buffer = plane.buffer
            buffer.rewind()
            padded.copyPixelsFromBuffer(buffer)
            val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            if (cropped !== padded) padded.recycle()
            cropped
        } catch (_: Exception) {
            null
        }
    }

    private data class GuideMatch(val bounds: Rect, val message: String)

    private fun findGuideMatch(result: Text): GuideMatch? {
        val lines = result.textBlocks.flatMap { it.lines }
        for (line in lines) {
            val bounds = line.boundingBox ?: continue
            val value = line.text.lowercase(Locale.GERMAN)
                .replace("ß", "ss")
                .replace("ä", "ae")
                .replace("ö", "oe")
                .replace("ü", "ue")
            val message = when {
                value.contains("bewegungsflussigkeit") ||
                    value.contains("bewegungsfluiditaet") ||
                    value.contains("motion smoothness") ||
                    value.contains("bildwiederholrate") ->
                    "Hier tippen: Bewegungsfluessigkeit oeffnen; danach Adaptiv oder die hoechste Option waehlen."

                value.contains("adaptiv") || value.contains("adaptive") ||
                    value.contains("120 hz") || value.contains("90 hz") || value.contains("144 hz") ->
                    "Hier waehlen: hoechste Bildwiederholrate, die dein Tablet tatsaechlich anbietet."

                value.contains("game booster") || value.contains("spiele booster") ||
                    value.contains("spieloptimierung") || value.contains("game optimizing") ->
                    "Game Booster oeffnen. Nur angebotene Leistungsoptionen nutzen; Temperatur- und Akkuschutz aktiv lassen."

                value.contains("grafikmodus") || value.contains("graphics mode") ->
                    "Roblox-Grafikmodus oeffnen. Manuell waehlen und die Qualitaet bei Rucklern schrittweise senken."

                value == "manuell" || value == "manual" ->
                    "Manuell auswaehlen. Danach kannst du die Roblox-Grafikqualitaet selbst passend einstellen."

                value.contains("energiesparmodus") || value.contains("power saving") ||
                    value.contains("battery saver") ->
                    "Energiesparen pruefen. Ausschalten kann Akkuverbrauch und Waerme erhoehen; entscheide bewusst."

                value == "anzeige" || value.startsWith("anzeige ") ||
                    value == "display" || value.startsWith("display ") ->
                    "Hier tippen: Anzeige oeffnen und nach Bewegungsfluessigkeit suchen."

                value == "akku" || value.startsWith("akku ") ||
                    value == "battery" || value.startsWith("battery ") ->
                    "Akku-Einstellungen pruefen. Schutzfunktionen nicht deaktivieren."

                else -> null
            }
            if (message != null) return GuideMatch(Rect(bounds), message)
        }
        return null
    }

    private fun showOverlay() {
        if (!android.provider.Settings.canDrawOverlays(this)) return
        val view = GuideOverlayView(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 0.76f
        }
        runCatching {
            windowManager.addView(view, params)
            overlay = view
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "REDLINE Screen Guide",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Zeigt an, wenn REDLINE den Bildschirm lokal analysiert." }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, ScreenGuideService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this,
            810,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("REDLINE Screen Guide aktiv")
            .setContentText("Bildschirm wird nur lokal nach Einstellungs-Texten durchsucht.")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stoppen", stopPending)
            .build()
    }

    override fun onDestroy() {
        cleaningUp = true
        projectionCallback?.let { callback -> runCatching { projection?.unregisterCallback(callback) } }
        runCatching { projection?.stop() }
        projection = null
        imageReader?.setOnImageAvailableListener(null, null)
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        overlay?.let { view -> runCatching { windowManager.removeView(view) } }
        overlay = null
        runCatching { recognizer.close() }
        worker?.quitSafely()
        worker = null
        workerHandler = null
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "redline_screen_guide"
        private const val NOTIFICATION_ID = 82
        private const val EXTRA_RESULT_CODE = "screen_result_code"
        private const val EXTRA_DATA = "screen_permission_data"
        private const val ACTION_STOP = "com.redline.fpsbooster.SCREEN_GUIDE_STOP"

        fun createIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, ScreenGuideService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }
    }
}

private class GuideOverlayView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private var target: Rect? = null
    private var message = "Öffne Einstellungen oder Roblox; REDLINE sucht passende Optionen."

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 48, 70)
        style = Paint.Style.STROKE
        strokeWidth = 5f * density
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(38, 255, 48, 70)
        style = Paint.Style.FILL
    }
    private val bannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(238, 18, 12, 28)
        style = Paint.Style.FILL
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 80, 105)
        textSize = 13f * density
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val messagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f * density
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun updateGuide(bounds: Rect?, text: String) {
        target = bounds
        message = text
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = density
        val banner = RectF(12f * d, 48f * d, width - 12f * d, 112f * d)
        canvas.drawRoundRect(banner, 14f * d, 14f * d, bannerPaint)
        canvas.drawText("REDLINE  •  AI SCREEN GUIDE", 24f * d, 72f * d, titlePaint)
        val shortMessage = if (message.length > 78) message.take(75) + "..." else message
        canvas.drawText(shortMessage, 24f * d, 94f * d, messagePaint)

        target?.let { rect ->
            val centerX = rect.exactCenterX()
            val centerY = rect.exactCenterY()
            val radius = max(rect.width(), rect.height()).coerceAtLeast((44f * d).toInt()) / 2f + 24f * d
            val highlight = RectF(rect).apply { inset(-13f * d, -10f * d) }
            canvas.drawRoundRect(highlight, 16f * d, 16f * d, boxPaint)
            canvas.drawCircle(centerX, centerY, radius, ringPaint)
            canvas.drawCircle(centerX, centerY, 5f * d, ringPaint)
        }
    }
}
