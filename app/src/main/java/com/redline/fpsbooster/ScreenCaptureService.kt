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
import kotlin.math.max

/**
 * User-consented MediaProjection session.
 *
 * Frames stay in memory, are reduced to a preview, and OCR is performed
 * locally only while Android Settings is the active app. No upload or recording.
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
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }

        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            LiveScreenState.setStatus("Bildschirmfreigabe wurde nicht bestätigt.")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val notification = buildNotification("Bildschirmfreigabe aktiv – lokal auf diesem Tablet.")
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
                        LiveScreenState.setStatus("Android hat die Bildschirmfreigabe beendet.")
                        stopSelf()
                    }
                }
            }
            projectionCallback = callback
            projection?.registerCallback(callback, Handler(mainLooper))
            startCapture()
            LiveScreenState.setStatus("LIVE SCREEN AKTIV • nur im Arbeitsspeicher")
            LiveScreenState.setAction("Bildschirm wird erfasst")
        } catch (error: Exception) {
            Log.e(TAG, "Could not start MediaProjection", error)
            LiveScreenState.setStatus("Bildschirmfreigabe konnte nicht gestartet werden.")
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
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val now = SystemClock.elapsedRealtime()
            val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
            if (now - lastFrameAt < FRAME_INTERVAL_MS || !busy.compareAndSet(false, true)) {
                image.close()
                return@setOnImageAvailableListener
            }
            lastFrameAt = now
            handleImage(image)
        }, workerHandler)

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

    private fun handleImage(image: Image) {
        var raw: Bitmap? = null
        try {
            raw = imageToBitmap(image)
        } catch (error: Exception) {
            Log.w(TAG, "Could not convert screen frame", error)
        } finally {
            image.close()
        }

        val source = raw
        if (source == null) {
            busy.set(false)
            return
        }

        try {
            val maxEdge = 720
            val scale = minOf(1f, maxEdge.toFloat() / max(source.width, source.height).toFloat())
            val preview = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    source,
                    (source.width * scale).toInt().coerceAtLeast(1),
                    (source.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else source
            if (preview !== source) source.recycle()

            LiveScreenState.publishPreview(preview)

            // The complete preview remains on-device. OCR is performed only in
            // Android Settings; on other apps the image is merely previewed locally.
            if (LiveScreenState.activePackage.value == SETTINGS_PACKAGE) {
                recognizer.process(InputImage.fromBitmap(preview, 0))
                    .addOnSuccessListener { result ->
                        val normalized = normalize(result.text)
                        val summary = when {
                            normalized.contains("akku und geraetewartung") || normalized.contains("geraetewartung") ->
                                "Gerätewartung sichtbar"
                            normalized.contains("jetzt optimieren") || normalized.contains("optimize now") ->
                                "„Jetzt optimieren“ sichtbar"
                            normalized.contains("bildwiederholrate") || normalized.contains("bewegungsflussigkeit") ->
                                "Bildwiederholrate sichtbar"
                            normalized.contains("anzeige") || normalized.contains("display") ->
                                "Anzeige-Einstellungen sichtbar"
                            else -> "Android-Einstellungen werden geprüft"
                        }
                        LiveScreenState.setSummary(summary)
                        LiveScreenState.setAction(summary)
                    }
                    .addOnFailureListener {
                        LiveScreenState.setSummary("Textanalyse nicht verfügbar")
                    }
                    .addOnCompleteListener {
                        busy.set(false)
                    }
            } else {
                LiveScreenState.setSummary("Bildvorschau aktiv • Texterkennung außerhalb der Einstellungen pausiert")
                busy.set(false)
            }
        } catch (error: Exception) {
            Log.e(TAG, "Screen analysis failed", error)
            busy.set(false)
        }
    }

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

    private fun normalize(value: String): String =
        value.lowercase(Locale.GERMAN)
            .replace("ß", "ss")
            .replace("ä", "ae")
            .replace("ö", "oe")
            .replace("ü", "ue")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "REDLINE Live Screen",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Zeigt an, wenn REDLINE den Bildschirm nach Zustimmung erfasst."
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
        LiveScreenState.clear()
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
        private const val FRAME_INTERVAL_MS = 1_500L
        private const val SETTINGS_PACKAGE = "com.android.settings"

        fun createIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, ScreenCaptureService::class.java).setAction(ACTION_STOP)
    }
}
