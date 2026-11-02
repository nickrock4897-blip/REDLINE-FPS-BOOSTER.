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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * User-approved screen guide. It captures the screen for local OCR and draws
 * a non-touchable highlight overlay. It never injects taps or changes settings.
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
    private val processing = AtomicBoolean(false)
    private var lastProcessedAt = 0L
    private var cleaningUp = false
    private lateinit var windowManager: WindowManager
    private var captureWidth = 0
    private var captureHeight = 0
    private var captureDensity = 0

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
        if (projection != null) return START_NOT_STICKY

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }

        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            Log.e(TAG, "Screen capture permission data missing")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification("Bildschirm wird lokal analysiert.")
                        ,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification("Bildschirm wird lokal analysiert."))
            }

            if (!android.provider.Settings.canDrawOverlays(this)) {
                Log.e(TAG, "Overlay permission is missing; the guide cannot be displayed")
                updateNotification("Berechtigung „Über anderen Apps anzeigen“ fehlt.")
                stopSelf()
                return START_NOT_STICKY
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

            readDisplayMetrics()
            showOverlay()
            startScreenAnalysis()
            if (overlay == null || virtualDisplay == null) {
                Log.e(TAG, "Guide startup incomplete: overlay=${overlay != null}, capture=${virtualDisplay != null}")
                updateNotification("Start fehlgeschlagen. Bitte Berechtigungen prüfen und erneut starten.")
                stopSelf()
                return START_NOT_STICKY
            }
        } catch (error: Exception) {
            Log.e(TAG, "Could not start screen guide", error)
            updateNotification("Screen Guide konnte nicht gestartet werden.")
            stopSelf()
            return START_NOT_STICKY
        }

        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun readDisplayMetrics() {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        captureWidth = metrics.widthPixels.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        captureHeight = metrics.heightPixels.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        captureDensity = metrics.densityDpi.takeIf { it > 0 } ?: resources.displayMetrics.densityDpi
        Log.i(TAG, "Display metrics: ${captureWidth}x${captureHeight} @ ${captureDensity}dpi")
    }

    private fun startScreenAnalysis() {
        val width = captureWidth
        val height = captureHeight
        val density = captureDensity
        if (width <= 0 || height <= 0 || density <= 0) {
            throw IllegalStateException("Invalid display metrics: ${width}x${height}, density=$density")
        }

        worker = HandlerThread("RedlineScreenOCR").also { it.start() }
        workerHandler = Handler(worker!!.looper)
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)

        imageReader?.setOnImageAvailableListener({ reader ->
            val image = try {
                reader.acquireLatestImage()
            } catch (error: Exception) {
                Log.w(TAG, "Could not acquire captured frame", error)
                null
            } ?: return@setOnImageAvailableListener

            val now = SystemClock.elapsedRealtime()
            if (now - lastProcessedAt < 650L || !processing.compareAndSet(false, true)) {
                image.close()
                return@setOnImageAvailableListener
            }
            lastProcessedAt = now

            val bitmap = imageToBitmap(image)
            image.close()
            if (bitmap == null) {
                processing.set(false)
                mainHandler.post { overlay?.showStatus("Bild konnte nicht gelesen werden. Screen Control neu starten.") }
                return@setOnImageAvailableListener
            }

            val frameWidth = bitmap.width
            val frameHeight = bitmap.height
            try {
                recognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { result ->
                        val match = findGuideMatch(result)
                        mainHandler.post {
                            overlay?.updateGuide(
                                match?.bounds,
                                match?.message ?: "Suche nach der passenden Einstellung …",
                                frameWidth,
                                frameHeight
                            )
                        }
                    }
                    .addOnFailureListener { error ->
                        Log.w(TAG, "On-device text recognition failed", error)
                        mainHandler.post {
                            overlay?.showStatus("Texterkennung läuft erneut. Bitte Einstellungsseite offen halten.")
                        }
                    }
                    .addOnCompleteListener {
                        if (!bitmap.isRecycled) bitmap.recycle()
                        processing.set(false)
                    }
            } catch (error: Exception) {
                Log.e(TAG, "Could not process captured frame", error)
                if (!bitmap.isRecycled) bitmap.recycle()
                processing.set(false)
                mainHandler.post { overlay?.showStatus("Texterkennung konnte nicht gestartet werden.") }
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

    private data class MatchRule(
        val terms: List<String>,
        val message: String
    )

    private val matchRules = listOf(
        MatchRule(
            listOf(
                "bewegungsflussigkeit", "bewegungsfluiditaet", "motion smoothness",
                "bildwiederholrate", "bildwiederholfrequenz", "bildschirmaktualisierungsrate",
                "bildfrequenz", "bildschirmfrequenz", "refresh rate", "screen refresh"
            ),
            "HIER TIPpen: Bewegungsflüssigkeit öffnen und die höchste verfügbare Bildwiederholrate wählen."
        ),
        MatchRule(
            listOf("120 hz", "90 hz", "144 hz", "adaptive", "adaptiv", "standard", "hoch"),
            "HIER wählen: die höchste Bildwiederholrate, die dein Tablet tatsächlich anbietet."
        ),
        MatchRule(
            listOf(
                "game booster", "spiele booster", "spieloptimierung", "spieleoptimierung",
                "game optimizing", "gaming hub", "game plugins"
            ),
            "Game Booster öffnen. Temperatur- und Akkuschutz eingeschaltet lassen."
        ),
        MatchRule(
            listOf("grafikmodus", "grafikqualitaet", "graphics mode", "graphics quality", "grafik"),
            "Roblox-Grafik öffnen. Bei Rucklern die Qualität schrittweise senken."
        ),
        MatchRule(
            listOf("akku und geraetewartung", "battery and device care", "geraetewartung", "device care"),
            "Akku und Gerätewartung öffnen. Unnötige Hintergrund-Apps schließen, Schutzfunktionen aktiv lassen."
        ),
        MatchRule(
            listOf("energiesparmodus", "power saving", "battery saver", "energie sparen"),
            "Energiesparen prüfen. Ausschalten kann Akkuverbrauch und Wärme erhöhen."
        ),
        MatchRule(
            listOf("anzeige", "display", "bildschirm"),
            "HIER tippen: Anzeige öffnen. Danach nach Bewegungsflüssigkeit oder Bildwiederholrate suchen."
        ),
        MatchRule(
            listOf("manuell", "manual"),
            "Manuell auswählen. Danach kannst du die Roblox-Grafikqualität selbst einstellen."
        )
    )

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
        val blocks = result.textBlocks
        // Inspect both individual OCR lines and complete text blocks. Samsung can
        // split a setting label across lines, so block-level matching is important.
        for (rule in matchRules) {
            for (block in blocks) {
                val blockText = normalize(block.text)
                if (!rule.terms.any { blockText.contains(it) }) continue

                val matchingLine = block.lines.firstOrNull { line ->
                    val lineText = normalize(line.text)
                    rule.terms.any { lineText.contains(it) }
                }
                val bounds = matchingLine?.boundingBox ?: block.boundingBox
                if (bounds != null) return GuideMatch(Rect(bounds), rule.message)
            }
        }
        return null
    }

    private fun showOverlay() {
        if (overlay != null) return
        val view = GuideOverlayView(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // Use the exact capture dimensions and include the status/navigation-bar area.
        // This keeps screenshot coordinates and overlay coordinates in the same space.
        val params = WindowManager.LayoutParams(
            captureWidth,
            captureHeight,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 1f
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        try {
            windowManager.addView(view, params)
            overlay = view
            Log.i(TAG, "Guide overlay attached: ${captureWidth}x${captureHeight}")
        } catch (error: Exception) {
            Log.e(TAG, "Could not attach guide overlay", error)
            overlay = null
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

    private fun buildNotification(status: String): Notification {
        val stopIntent = Intent(this, ScreenGuideService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this,
            810,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("REDLINE Screen Guide")
            .setContentText(status)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stoppen", stopPending)
            .build()
    }

    private fun updateNotification(status: String) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(status))
        }.onFailure { Log.w(TAG, "Could not update notification", it) }
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
    private var message = "REDLINE aktiv – öffne Android-Einstellungen und suche „Anzeige“."

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 35, 65)
        style = Paint.Style.STROKE
        strokeWidth = 6f * density
        setShadowLayer(9f * density, 0f, 0f, Color.rgb(255, 25, 50))
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(52, 255, 35, 65)
        style = Paint.Style.FILL
    }
    private val bannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(245, 18, 12, 28)
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
        } else if (SystemClock.elapsedRealtime() - lastTargetAt > 5500L) {
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
        val bannerTop = 42f * d
        val banner = RectF(12f * d, bannerTop, width - 12f * d, bannerTop + 62f * d)
        canvas.drawRoundRect(banner, 14f * d, 14f * d, bannerPaint)
        canvas.drawText("REDLINE  •  SCREEN GUIDE AKTIV", 24f * d, bannerTop + 22f * d, titlePaint)

        val availableWidth = (width - 48f * d).coerceAtLeast(80f * d)
        val messageChars = messagePaint.breakText(message, true, availableWidth, null)
        val shownMessage = if (messageChars < message.length && messageChars > 3) {
            message.take(messageChars - 1) + "…"
        } else message
        canvas.drawText(shownMessage, 24f * d, bannerTop + 46f * d, messagePaint)

        val rect = target ?: return
        if (frameWidth <= 0 || frameHeight <= 0 || width <= 0 || height <= 0) return

        // Translate capture-image coordinates into this overlay window's coordinates.
        val scaleX = width.toFloat() / frameWidth.toFloat()
        val scaleY = height.toFloat() / frameHeight.toFloat()
        val screenPosition = IntArray(2)
        getLocationOnScreen(screenPosition)
        val mapped = RectF(
            rect.left * scaleX - screenPosition[0],
            rect.top * scaleY - screenPosition[1],
            rect.right * scaleX - screenPosition[0],
            rect.bottom * scaleY - screenPosition[1]
        )

        // Surround the setting label with a larger oval so the target is obvious
        // and still easy to tap manually. The overlay itself remains non-touchable.
        val minWidth = 150f * d
        if (mapped.width() < minWidth) {
            val extra = (minWidth - mapped.width()) / 2f
            mapped.left -= extra
            mapped.right += extra
        }
        mapped.inset(-18f * d, -14f * d)
        mapped.left = mapped.left.coerceIn(5f * d, width - 5f * d)
        mapped.right = mapped.right.coerceIn(5f * d, width - 5f * d)
        mapped.top = mapped.top.coerceIn(5f * d, height - 5f * d)
        mapped.bottom = mapped.bottom.coerceIn(5f * d, height - 5f * d)

        canvas.drawRoundRect(mapped, 22f * d, 22f * d, fillPaint)
        canvas.drawOval(mapped, ringPaint)
    }
}
