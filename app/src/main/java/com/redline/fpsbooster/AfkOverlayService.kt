package com.redline.fpsbooster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlin.math.max

class AfkOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var overlay: LinearLayout? = null
    private var statusText: TextView? = null
    private var timerText: TextView? = null
    private var pauseButton: Button? = null
    private var minimized = false
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (!SessionStore.isActive(this@AfkOverlayService)) {
                stopSelf()
                return
            }
            refresh()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(71, notification())
        if (Settings.canDrawOverlays(this)) showOverlay()
        handler.post(tick)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                SessionStore.finish(this)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> SessionStore.pause(this)
            ACTION_RESUME -> SessionStore.resume(this)
            ACTION_MINIMIZE -> {
                minimized = !minimized
                showOverlay()
            }
        }
        refresh()
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("afk_grind", "AFK Grind", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        )
        return NotificationCompat.Builder(this, "afk_grind")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("AFK Grind ist aktiv")
            .setContentText("Display-Schutz und Sitzungs-Timer laufen.")
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        overlay?.let { runCatching { windowManager.removeView(it) } }
        overlay = null
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 14, 18, 14)
            setBackgroundColor(Color.rgb(27, 20, 45))
        }
        val header = TextView(this).apply {
            text = if (minimized) "REDLINE • AFK  ●" else "REDLINE • AFK GRIND  ●"
            setTextColor(Color.rgb(220, 90, 255))
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 10)
        }
        panel.addView(header)
        if (!minimized) {
            statusText = TextView(this).apply { setTextColor(Color.WHITE); textSize = 12f }
            timerText = TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding(0, 8, 0, 8)
            }
            panel.addView(statusText)
            panel.addView(timerText)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            pauseButton = Button(this).apply {
                text = if (SessionStore.isRunning(this@AfkOverlayService)) "Pause" else "Weiter"
                setOnClickListener {
                    if (SessionStore.isRunning(this@AfkOverlayService)) SessionStore.pause(this@AfkOverlayService)
                    else SessionStore.resume(this@AfkOverlayService)
                    refresh()
                }
            }
            row.addView(pauseButton, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Button(this).apply {
                text = "Minimieren"
                setOnClickListener { minimized = true; showOverlay() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            panel.addView(row)
            panel.addView(Button(this).apply {
                text = "Sitzung beenden"
                setOnClickListener { SessionStore.finish(this@AfkOverlayService); stopSelf() }
            })
        } else {
            panel.addView(Button(this).apply {
                text = "Öffnen"
                setOnClickListener { minimized = false; showOverlay() }
            })
            panel.addView(Button(this).apply {
                text = "Stop"
                setOnClickListener { SessionStore.finish(this@AfkOverlayService); stopSelf() }
            })
        }
        panel.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchX = 0f
            private var touchY = 0f
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val params = v.tag as? WindowManager.LayoutParams ?: return false
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x
                        startY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = startX + (event.rawX - touchX).toInt()
                        params.y = startY + (event.rawY - touchY).toInt()
                        runCatching { windowManager.updateViewLayout(v, params) }
                        return true
                    }
                }
                return false
            }
        })
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 110
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
        }
        panel.tag = params
        overlay = panel
        runCatching { windowManager.addView(panel, params) }.onFailure { stopSelf() }
    }

    private fun refresh() {
        statusText?.text = if (SessionStore.isRunning(this)) "AFK GRIND: ON • DISPLAY: WACH" else "AFK GRIND: PAUSIERT"
        timerText?.text = SessionStore.format(SessionStore.durationMs(this))
        pauseButton?.text = if (SessionStore.isRunning(this)) "Pause" else "Weiter"
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        overlay?.let { runCatching { windowManager.removeView(it) } }
        overlay = null
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.redline.fpsbooster.AFK_STOP"
        const val ACTION_PAUSE = "com.redline.fpsbooster.AFK_PAUSE"
        const val ACTION_RESUME = "com.redline.fpsbooster.AFK_RESUME"
        const val ACTION_MINIMIZE = "com.redline.fpsbooster.AFK_MINIMIZE"
        fun intent(context: Context, action: String? = null) = Intent(context, AfkOverlayService::class.java).apply { if (action != null) this.action = action }
    }
}
