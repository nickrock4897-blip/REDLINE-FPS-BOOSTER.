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
import android.graphics.Typeface
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
import android.util.DisplayMetrics
import android.util.Log
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
import kotlin.math.min

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
    private lateinit var windowManager: WindowManager

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

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

            // Put the guide window on the screen before the first OCR frame arrives.
            showOverlay()
            startScreenAnalysis()
        } catch (error: Exception) {
            Log.e(TAG, "Could not start screen guide", error)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startScreenAnalysis() {
        // Use the full physical display size. resources.displayMetrics can describe
        // only the app area on some Samsung/Android versions.
        val realMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(realMetrics)
        val width = realMetrics.widthPixels.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val height = realMetrics.heightPixels.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val density = realMetrics.densityDpi.takeIf { it > 0 } ?: resources.displayMetrics.densityDpi

        worker = HandlerThread("RedlineScreenOCR").also { it.start() }
        workerHandler = Handler(worker!!.looper)
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            val now = SystemClock.elapsedRealtime()
            if (isProcessing || now - lastProcessedAt < 850L) {
                image.close()
                return@setOnImageAvailableListener
            }

            isProcessing = true
            lastProcessedAt = now
            val bitmap = imageToBitmap(image)
            image.close()
            if (bitmap == null) {
                isProcessing = false
                mainHandler.post {
                    overlay?.showStatus("Bild konnte nicht gelesen werden. Bitte Screen Control neu starten.")
                }
                return@setOnImageAvailableListener
            }

            val frameWidth = bitmap.width
            val frameHeight = bitmap.height
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result ->
                    val match = findGuideMatch(result)
                    mainHandler.post {
                        overlay?.updateGuide(
                            match?.bounds,
                            match?.message ?: "Suche passende Einstellung … Öffne eine Einstellungsseite.",
                            frameWidth,
                            frameHeight
                        )
                    }
                }
                .addOnFailureListener { error ->
                    Log.w(TAG, "On-device text recognition failed", error)
                    mainHandler.post {
                        overlay?.showStatus("Texterkennung läuft erneut. Halte die Einstellungsseite kurz offen.")
                    }
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
        } catch (error: Exception) {
            Log.e(TAG, "Could not convert captured frame", error)
            null
        }
    }

    private data class GuideMatch(val bounds: Rect, val message: String)

    private fun normalize(value: String): String {
        return value.lowercase(Locale.GERMAN)
            .replace("ß", "ss")
            .replace("ä", "ae")
            .replace("ö", "oe")
            .replace("ü", "ue")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun findGuideMatch(result: Text): GuideMatch? {
        val lines = result.textBlocks.flatMap { it.lines }
        // Prefer specific labels. This also accepts the wording used by Samsung,
        // Android and Roblox in German and English.
        val candidates = lines.mapNotNull { line ->
            val bounds = line.boundingBox ?: return@mapNotNull null
            val value = normalize(line.text)
            val message = when {
                listOf(
                    "bewegungsflussigkeit", "bewegungsfluiditaet", "motion smoothness",
                    "bildwiederholrate", "bildwiederholfrequenz", "bildschirmaktualisierungsrate",
                    "bildfrequenz", "bildschirmfrequenz", "refresh rate", "screen refresh"
                ).any { value.contains(it) } ->
                    "Hier tippen: Bildwiederholrate öffnen und die höchste verfügbare Option wählen."

                listOf("120 hz", "90 hz", "144 hz", "adaptive", "adaptiv").any { value.contains(it) } ->
                    "Hier wählen: höchste Bildwiederholrate, die dein Tablet tatsächlich anbietet."

                listOf(
                    "game booster", "spiele booster", "spieloptimierung", "spieleoptimierung",
                    "game optimizing", "gaming hub"
                ).any { value.contains(it) } ->
                    "Game Booster öffnen. Temperatur- und Akkuschutz eingeschaltet lassen."

                listOf("grafikmodus", "grafikqualitaet", "graphics mode", "graphics quality").any { value.contains(it) } ->
                    "Roblox-Grafik öffnen. Bei Rucklern die Qualität schrittweise senken."

                value == "manuell" || value == "manual" ->
                    "Manuell wählen. Danach kannst du die Roblox-Grafik selbst einstellen."

                listOf("energiesparmodus", "power saving", "battery saver", "energie sparen").any { value.contains(it) } ->
                    "Energiesparen prüfen. Ausschalten kann Akkuverbrauch und Wärme erhöhen."

                value == "anzeige" || value.startsWith("anzeige ") ||
                    value == "display" || value.startsWith("display ") ->
                    "Hier tippen: Anzeige öffnen und nach Bewegungsflüssigkeit suchen."

                value == "bildschirm" || value.startsWith("bildschirm ") ||
                    value == "screen" || value.startsWith("screen ") ->
                    "Bildschirm öffnen und nach Bildwiederholrate oder Bewegungsflüssigkeit suchen."

                value == "akku" || value.startsWith("akku ") ||
                    value == "battery" || value.startsWith("battery ") ->
                    "Akku-Einstellungen prüfen. Schutzfunktionen eingeschaltet lassen."

                else -> null
            }
            if (message == null) null else GuideMatch(Rect(bounds), message)
        }

        // A specific match later in the list should win over a generic menu heading.
        return candidates.firstOrNull { match ->
            val line = lines.firstOrNull { it.boundingBox == match.bounds }?.text.orEmpty()
            val normalized = normalize(line)
            normalized.contains("bildwiederhol") ||
                normalized.contains("bewegungs") ||
                normalized.contains("refresh") ||
                normalized.contains("game booster") ||
                normalized.contains("grafik") ||
                normalized.contains("graphics") ||
                normalized.contains("120 hz") ||
                normalized.contains("90 hz") ||
                normalized.contains("144 hz")
        } ?: candidates.firstOrNull()
    }

    private fun showOverlay() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission is missing; cannot draw the guide ring")
            return
        }

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
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 1f
        }

        try {
            windowManager.addView(view, params)
            overlay = view
        } catch (error: Exception) {
            Log.e(TAG, "Could not attach guide overlay", error)
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
        private const val TAG = "RedlineScreenGuide"
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
    private var frameWidth = 0
    private var frameHeight = 0
    private var lastTargetAt = 0L
    private var message = "Öffne Anzeige oder Roblox. REDLINE sucht passende Einstellungen."

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 35, 65)
        style = Paint.Style.STROKE
        strokeWidth = 6f * density
        setShadowLayer(8f * density, 0f, 0f, Color.rgb(255, 25, 50))
    }
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(48, 255, 35, 65)
        style = Paint.Style.FILL
    }
    private val bannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(242, 18, 12, 28)
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

    fun updateGuide(bounds: Rect?, text: String, sourceWidth: Int, sourceHeight: Int) {
        message = text
        if (bounds != null) {
            target = Rect(bounds)
            frameWidth = sourceWidth
            frameHeight = sourceHeight
            lastTargetAt = SystemClock.elapsedRealtime()
        } else if (SystemClock.elapsedRealtime() - lastTargetAt > 4500L) {
            target = null
        }
        postInvalidate()
    }

    fun showStatus(text: String) {
        message = text
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = density
        val bannerLeft = 12f * d
        val bannerTop = 42f * d
        val banner = RectF(bannerLeft, bannerTop, width - 12f * d, bannerTop + 58f * d)
        canvas.drawRoundRect(banner, 14f * d, 14f * d, bannerPaint)
        canvas.drawText("REDLINE  •  SCREEN GUIDE", 24f * d, bannerTop + 21f * d, titlePaint)

        val text = messagePaint.let { paint ->
            val available = (width - 48f * d).coerceAtLeast(80f * d)
            val chars = paint.breakText(message, true, available, null)
            if (chars < message.length && chars > 3) message.take(chars - 1) + "…" else message
        }
        canvas.drawText(text, 24f * d, bannerTop + 43f * d, messagePaint)

        val rect = target
        if (rect != null && frameWidth > 0 && frameHeight > 0) {
            // ML Kit coordinates are in captured-image pixels. The overlay view can
            // have different dimensions (especially on tablets and edge-to-edge UI).
            val scaleX = width.toFloat() / frameWidth.toFloat()
            val scaleY = height.toFloat() / frameHeight.toFloat()
            val mapped = RectF(
                rect.left * scaleX,
                rect.top * scaleY,
                rect.right * scaleX,
                rect.bottom * scaleY
            )
            mapped.inset(-18f * d, -13f * d)
            mapped.left = mapped.left.coerceIn(4f * d, width - 4f * d)
            mapped.right = mapped.right.coerceIn(4f * d, width - 4f * d)
            mapped.top = mapped.top.coerceIn(4f * d, height - 4f * d)
            mapped.bottom = mapped.bottom.coerceIn(4f * d, height - 4f * d)

            // Oval follows the whole text line so the red marking stays visible
            // even when the setting label is much wider than it is tall.
            canvas.drawRoundRect(mapped, 20f * d, 20f * d, boxPaint)
            canvas.drawOval(mapped, ringPaint)
        }
    }
}
