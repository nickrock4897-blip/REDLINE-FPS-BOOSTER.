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
import android.graphics.PixelFormat
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
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

/**
 * User-consented, full-display MediaProjection session.
 *
 * Captured frames are processed locally on-device using OCR and a lightweight
 * visual-difference signature. No raw OCR text or screenshot is uploaded or persisted.
 */
class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var workerHandler: Handler? = null
    private val busy = AtomicBoolean(false)
    private var lastFrameAt = 0L
    private var stopping = false
    private var watchdogReported = false
    private var previousSignature: IntArray? = null

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (stopping || projection == null) return
            val now = SystemClock.elapsedRealtime()
            val last = lastFrameAt
            if (last > 0L && now - last > FRAME_TIMEOUT_MS) {
                if (!watchdogReported) {
                    watchdogReported = true
                    val message = "Keine neuen Bildschirmbilder seit ${(now - last) / 1000}s. Prüfe die Android-Bildschirmfreigabe."
                    Log.e(TAG, message)
                    LiveScreenState.setError(message)
                }
            } else if (last == 0L && now - captureStartedAt > FIRST_FRAME_TIMEOUT_MS) {
                if (!watchdogReported) {
                    watchdogReported = true
                    val message = "Die Bildschirmfreigabe ist gestartet, aber Android liefert noch keine Bilder."
                    Log.e(TAG, message)
                    LiveScreenState.setError(message)
                }
            }
            workerHandler?.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    private var captureStartedAt = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopping = true
            SettingsAutomationService.stop(this)
            LiveScreenState.stopSession("Vom Benutzer gestoppt.")
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) {
            Log.i(TAG, "Duplicate start ignored; capture is already active.")
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }

        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            LiveScreenState.setError("Bildschirmfreigabe wurde nicht bestätigt.")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            stopping = false
            captureStartedAt = SystemClock.elapsedRealtime()
            lastFrameAt = 0L
            watchdogReported = false
            previousSignature = null
            val notification = buildNotification("Bildschirmfreigabe aktiv – lokale Analyse auf diesem Tablet.")
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    if (!stopping) {
                        Log.w(TAG, "MediaProjection stopped by Android/system UI.")
                        LiveScreenState.setError("Android hat die Bildschirmfreigabe beendet.")
                        stopping = true
                        stopSelf()
                    }
                }
            }
            projectionCallback = callback
            projection?.registerCallback(callback, Handler(mainLooper))
            startCapture()
            LiveScreenState.captureReady()
            LiveScreenState.setStatus("AI Active • warte auf erstes Bildschirmbild")
            LiveScreenState.setAction("Bildschirmquelle verbunden; erste Frames werden erwartet")
            workerHandler?.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
            Log.i(TAG, "MediaProjection session started.")
        } catch (error: Exception) {
            Log.e(TAG, "Could not start MediaProjection", error)
            LiveScreenState.setError("Bildschirmfreigabe konnte nicht gestartet werden: ${error.localizedMessage ?: error.javaClass.simpleName}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun startCapture() {
        val metrics = DisplayMetrics()
        (getSystemService(WINDOW_SERVICE) as android.view.WindowManager)
            .defaultDisplay.getRealMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi
        require(width > 0 && height > 0 && density > 0) { "Ungültige Bildschirmgröße" }

        worker = HandlerThread("RedlineLiveCapture").also { it.start() }
        workerHandler = Handler(worker!!.looper)
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        imageReader?.setOnImageAvailableListener(frameListener, workerHandler)

        virtualDisplay = projection?.createVirtualDisplay(
            "REDLINE Live Screen",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            workerHandler
        )
        require(virtualDisplay != null) { "VirtualDisplay konnte nicht erstellt werden" }
    }

    private val frameListener = ImageReader.OnImageAvailableListener { reader ->
        val now = SystemClock.elapsedRealtime()
        val image = runCatching { reader.acquireLatestImage() }.getOrNull()
        if (image == null) {
            Log.w(TAG, "ImageReader callback arrived without an image.")
            return@OnImageAvailableListener
        }
        if (now - lastFrameAt < FRAME_INTERVAL_MS || !busy.compareAndSet(false, true)) {
            image.close()
            return@OnImageAvailableListener
        }
        lastFrameAt = now
        watchdogReported = false
        handleImage(image)
    }

    private fun handleImage(image: Image) {
        val source = try {
            imageToBitmap(image)
        } catch (error: Exception) {
            Log.e(TAG, "Could not convert screen frame.", error)
            null
        } finally {
            image.close()
        }

        if (source == null) {
            busy.set(false)
            LiveScreenState.analysisFailed("Bild konnte nicht in ein Bitmap umgewandelt werden.")
            return
        }

        try {
            val maxEdge = 640
            val scale = minOf(1f, maxEdge.toFloat() / max(source.width, source.height).toFloat())
            val preview = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    source,
                    (source.width * scale).toInt().coerceAtLeast(1),
                    (source.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else source
            if (preview !== source && !source.isRecycled) source.recycle()

            val signature = buildSignature(preview)
            val changed = detectSceneChange(signature)
            LiveScreenState.publishPreview(preview)
            LiveScreenState.frameReceived(changed)

            // OCR is now processed for every user-approved captured app, not only
            // Android Settings. Results are summarized locally; raw text is discarded.
            recognizer.process(InputImage.fromBitmap(preview, 0))
                .addOnSuccessListener { result ->
                    val packageName = LiveScreenState.activePackage.value
                    val summary = summarizeOnDevice(result.text, packageName)
                    LiveScreenState.analysisCompleted(summary, changed)
                    Log.d(TAG, "Frame analyzed. changed=$changed package=$packageName chars=${result.text.length}")
                    if (changed) {
                        workerHandler?.postDelayed({
                            if (!stopping && projection != null) LiveScreenState.setWatching()
                        }, REACTION_STATE_MS)
                    }
                }
                .addOnFailureListener { error ->
                    val message = "ML Kit Texterkennung: ${error.localizedMessage ?: error.javaClass.simpleName}"
                    Log.e(TAG, message, error)
                    LiveScreenState.analysisFailed(message)
                }
                .addOnCompleteListener {
                    busy.set(false)
                }
        } catch (error: Exception) {
            Log.e(TAG, "Screen frame analysis failed.", error)
            LiveScreenState.analysisFailed("Bildanalyse fehlgeschlagen: ${error.localizedMessage ?: error.javaClass.simpleName}")
            busy.set(false)
        }
    }

    private fun buildSignature(bitmap: Bitmap): IntArray {
        val result = IntArray(SIGNATURE_SIZE * SIGNATURE_SIZE)
        for (y in 0 until SIGNATURE_SIZE) {
            for (x in 0 until SIGNATURE_SIZE) {
                val px = (((x + 0.5f) / SIGNATURE_SIZE) * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
                val py = (((y + 0.5f) / SIGNATURE_SIZE) * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
                val color = bitmap.getPixel(px, py)
                val r = (color shr 16) and 0xff
                val g = (color shr 8) and 0xff
                val b = color and 0xff
                result[y * SIGNATURE_SIZE + x] = (r * 299 + g * 587 + b * 114) / 1000
            }
        }
        return result
    }

    private fun detectSceneChange(signature: IntArray): Boolean {
        val previous = previousSignature
        previousSignature = signature
        if (previous == null || previous.size != signature.size) return false
        var changedSamples = 0
        var totalDifference = 0L
        for (index in signature.indices) {
            val difference = abs(signature[index] - previous[index])
            totalDifference += difference
            if (difference >= SAMPLE_CHANGE_THRESHOLD) changedSamples++
        }
        val averageDifference = totalDifference / signature.size
        return changedSamples >= MIN_CHANGED_SAMPLES && averageDifference >= AVG_CHANGE_THRESHOLD
    }

    private fun summarizeOnDevice(rawText: String, packageName: String): String {
        val normalized = normalize(rawText)
        val app = when {
            packageName == ROBLOX_PACKAGE -> "Roblox"
            packageName == SETTINGS_PACKAGE -> "Android-Einstellungen"
            packageName == DEVICE_CARE_PACKAGE -> "Samsung Gerätewartung"
            packageName.isBlank() || packageName == "Unbekannt" -> "Bildschirm"
            else -> "geöffnete App"
        }
        if (normalized.isBlank()) return "$app erkannt • kein lesbarer Text im aktuellen Frame"

        return when {
            packageName == ROBLOX_PACKAGE &&
                (normalized.contains("graphics quality") || normalized.contains("grafikqualitaet")) ->
                "Roblox-Grafikeinstellungen erkannt"
            packageName == ROBLOX_PACKAGE &&
                (normalized.contains("graphics mode") || normalized.contains("grafikmodus")) ->
                "Roblox-Grafikmodus erkannt"
            normalized.contains("jetzt optimieren") || normalized.contains("optimize now") ->
                "Schaltfläche „Jetzt optimieren“ sichtbar"
            normalized.contains("akku und geraetewartung") || normalized.contains("device care") ->
                "Gerätewartung-Menü sichtbar"
            normalized.contains("bildwiederholrate") || normalized.contains("refresh rate") ->
                "Bildwiederholraten-Einstellung erkannt"
            normalized.contains("settings") || normalized.contains("einstellungen") ->
                "$app • Einstellungsbereich sichtbar"
            else ->
                "$app • Bildschirmtext lokal analysiert (${rawText.trim().split(Regex("\\s+")).size} Wörter)"
        }
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.GERMAN)
            .replace("ß", "ss")
            .replace("ä", "ae")
            .replace("ö", "oe")
            .replace("ü", "ue")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun imageToBitmap(image: Image): Bitmap? {
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
        return cropped
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "REDLINE Live Screen",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Zeigt an, wenn REDLINE den Bildschirm nach Zustimmung erfasst und lokal analysiert."
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 990, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("REDLINE AI Live Screen")
            .setContentText(status)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "STOP", stopPending)
            .build()
    }

    override fun onDestroy() {
        stopping = true
        workerHandler?.removeCallbacks(watchdog)
        SettingsAutomationService.stop(this)
        imageReader?.setOnImageAvailableListener(null, null)
        runCatching { projectionCallback?.let { projection?.unregisterCallback(it) } }
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
        runCatching { projection?.stop() }
        projection = null
        worker?.quitSafely()
        worker = null
        workerHandler = null
        runCatching { recognizer.close() }
        busy.set(false)
        LiveScreenState.stopSession("Bildschirmübertragung beendet.")
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "RedlineLiveScreen"
        private const val CHANNEL_ID = "redline_live_screen"
        private const val NOTIFICATION_ID = 88
        private const val ACTION_STOP = "com.redline.fpsbooster.LIVE_SCREEN_STOP"
        private const val EXTRA_RESULT_CODE = "projection_result_code"
        private const val EXTRA_DATA = "projection_permission_data"
        private const val FRAME_INTERVAL_MS = 1_200L
        private const val FRAME_TIMEOUT_MS = 9_000L
        private const val FIRST_FRAME_TIMEOUT_MS = 12_000L
        private const val WATCHDOG_INTERVAL_MS = 3_000L
        private const val REACTION_STATE_MS = 850L
        private const val SIGNATURE_SIZE = 8
        private const val SAMPLE_CHANGE_THRESHOLD = 24
        private const val MIN_CHANGED_SAMPLES = 5
        private const val AVG_CHANGE_THRESHOLD = 10L
        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val DEVICE_CARE_PACKAGE = "com.samsung.android.lool"
        private const val ROBLOX_PACKAGE = "com.roblox.client"

        fun createIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP)
    }
}
