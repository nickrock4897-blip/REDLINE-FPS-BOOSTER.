package com.redline.fpsbooster

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * REDLINE local screen assistant.
 *
 * Android screenshots are requested only while the active window belongs to
 * Android Settings. Frames are OCR-processed on-device and immediately thrown
 * away; raw screenshots and OCR text are never saved or uploaded.
 *
 * Click automation is also restricted to the Android Settings package and
 * only activates explicitly-labelled Device Care controls.
 */
class SettingsAutomationService : AccessibilityService() {
    private val prefs by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val screenshotBusy = AtomicBoolean(false)
    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private var phaseStartedAt = 0L
    private var lastActionAt = 0L
    private var lastPhase = PHASE_IDLE
    private var scanLoopStarted = false

    private val scanLoop = object : Runnable {
        override fun run() {
            if (!prefs.getBoolean(KEY_RUNNING, false)) {
                scanLoopStarted = false
                return
            }

            val root = rootInActiveWindow
            val activePackage = root?.packageName?.toString()

            // Stop screen observation as soon as the user leaves Android Settings.
            if (activePackage != SETTINGS_PACKAGE) {
                stopAutomation("Bildschirmfreigabe beendet: Android-Einstellungen verlassen.")
                scanLoopStarted = false
                return
            }

            captureLocalSettingsFrame()
            mainHandler.postDelayed(this, SCREEN_SCAN_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            packageNames = arrayOf(SETTINGS_PACKAGE)
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 120
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        setStatus(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                "Bedienung bereit. Screen-Analyse läuft nur in Android-Einstellungen."
            else
                "Bedienung bereit. Bildschirm-Analyse benötigt Android 11 oder neuer."
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!prefs.getBoolean(KEY_RUNNING, false)) return
        if (event?.packageName?.toString() != SETTINGS_PACKAGE) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !scanLoopStarted) {
            scanLoopStarted = true
            mainHandler.removeCallbacks(scanLoop)
            mainHandler.post(scanLoop)
        }

        val root = rootInActiveWindow ?: return
        val phase = prefs.getInt(KEY_PHASE, PHASE_IDLE)

        if (phase != lastPhase) {
            lastPhase = phase
            phaseStartedAt = SystemClock.elapsedRealtime()
            lastActionAt = 0L
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastActionAt < ACTION_COOLDOWN_MS) return

        when (phase) {
            PHASE_FIND_DEVICE_CARE -> {
                setStatus("Live-Analyse: Suche „Akku und Gerätewartung“ …")
                val clicked = clickFirstMatching(root) { label ->
                    containsAny(
                        label,
                        "akku und geraetewartung",
                        "geraetewartung",
                        "device care",
                        "battery and device care"
                    )
                }
                if (clicked) {
                    lastActionAt = now
                    prefs.edit().putInt(KEY_PHASE, PHASE_RUN_OPTIMIZE).apply()
                    setStatus("Gerätewartung gefunden und geöffnet. Prüfe den nächsten Bildschirm …")
                } else if (now - phaseStartedAt > PHASE_TIMEOUT_MS) {
                    finish("Gerätewartung nicht gefunden. Es wurde nichts verändert.")
                }
            }

            PHASE_RUN_OPTIMIZE -> {
                setStatus("Live-Analyse: Suche den eindeutigen Knopf „Jetzt optimieren“ …")
                val clicked = clickFirstMatching(root) { label ->
                    val value = normalize(label)
                    value == "jetzt optimieren" ||
                        value == "optimize now" ||
                        value == "optimieren jetzt"
                }
                if (clicked) {
                    lastActionAt = now
                    finish("Der eindeutig beschriftete Knopf „Jetzt optimieren“ wurde ausgewählt.")
                } else if (now - phaseStartedAt > PHASE_TIMEOUT_MS) {
                    finish("„Jetzt optimieren“ wurde nicht gefunden. Es wurde nichts angetippt.")
                }
            }

            else -> stopAutomation("Automatik gestoppt.")
        }
    }

    private fun captureLocalSettingsFrame() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            setStatus("Live-Bildanalyse ist auf dieser Android-Version nicht verfügbar.")
            return
        }
        if (!screenshotBusy.compareAndSet(false, true)) return

        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        var softwareBitmap: Bitmap? = null
                        try {
                            val hardwareBuffer = screenshot.hardwareBuffer
                            val hardwareBitmap = Bitmap.wrapHardwareBuffer(
                                hardwareBuffer,
                                screenshot.colorSpace
                            )
                            hardwareBuffer.close()

                            softwareBitmap = hardwareBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                            hardwareBitmap?.recycle()

                            val frame = softwareBitmap
                            if (frame == null) {
                                setStatus("Bild konnte nicht gelesen werden. Es wurde nichts angetippt.")
                                screenshotBusy.set(false)
                                return
                            }

                            recognizer.process(InputImage.fromBitmap(frame, 0))
                                .addOnSuccessListener { result ->
                                    // Store only a short category/status, never the raw screen text.
                                    val observed = normalize(result.text)
                                    val summary = when {
                                        containsAny(observed, "akku und geraetewartung", "geraetewartung", "device care") ->
                                            "Gerätewartung auf dem Bildschirm erkannt"
                                        containsAny(observed, "jetzt optimieren", "optimize now") ->
                                            "„Jetzt optimieren“ auf dem Bildschirm erkannt"
                                        containsAny(observed, "anzeige", "display", "bildschirm") ->
                                            "Anzeige-Einstellungen erkannt"
                                        else -> "Bildschirm wird lokal geprüft"
                                    }
                                    setStatus("Live-Screen aktiv • $summary")
                                }
                                .addOnFailureListener {
                                    setStatus("Live-Screen aktiv • Texterkennung versucht es erneut.")
                                }
                                .addOnCompleteListener {
                                    frame.recycle()
                                    screenshotBusy.set(false)
                                }
                        } catch (error: Exception) {
                            softwareBitmap?.let { if (!it.isRecycled) it.recycle() }
                            screenshotBusy.set(false)
                            setStatus("Bildanalyse fehlgeschlagen. Es wurde nichts angetippt.")
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        screenshotBusy.set(false)
                        val reason = when (errorCode) {
                            ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
                                "Bildanalyse wartet auf Android."
                            ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
                                "Android hat die Screenshot-Berechtigung abgelehnt."
                            else -> "Android konnte gerade kein Bildschirmbild liefern."
                        }
                        setStatus(reason)
                    }
                }
            )
        } catch (error: Exception) {
            screenshotBusy.set(false)
            setStatus("Screen-Analyse konnte nicht gestartet werden.")
        }
    }

    private fun clickFirstMatching(
        root: AccessibilityNodeInfo,
        predicate: (String) -> Boolean
    ): Boolean {
        val label = normalize(
            root.text?.toString().orEmpty() + " " +
                root.contentDescription?.toString().orEmpty()
        )

        if (label.isNotBlank() && predicate(label)) {
            var candidate: AccessibilityNodeInfo? = root
            repeat(MAX_CLICK_ANCESTORS) {
                val current = candidate ?: return@repeat
                if (current.isClickable && current.isEnabled &&
                    current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ) return true
                candidate = current.parent
            }
        }

        for (index in 0 until root.childCount) {
            val child = root.getChild(index) ?: continue
            if (clickFirstMatching(child, predicate)) return true
        }
        return false
    }

    private fun containsAny(value: String, vararg terms: String): Boolean =
        terms.any { value.contains(it) }

    private fun normalize(value: String): String =
        value.lowercase(Locale.GERMAN)
            .replace("ß", "ss")
            .replace("ä", "ae")
            .replace("ö", "oe")
            .replace("ü", "ue")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun setStatus(message: String) {
        prefs.edit().putString(KEY_STATUS, message).apply()
    }

    private fun finish(message: String) {
        stopAutomation(message)
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }

    override fun onInterrupt() {
        stopAutomation("Android hat die Automatik unterbrochen.")
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(scanLoop)
        runCatching { recognizer.close() }
        screenshotBusy.set(false)
        super.onDestroy()
    }

    private fun stopAutomation(message: String = "Automatik gestoppt.") {
        prefs.edit()
            .putBoolean(KEY_RUNNING, false)
            .putInt(KEY_PHASE, PHASE_IDLE)
            .putString(KEY_STATUS, message)
            .apply()
        lastPhase = PHASE_IDLE
        scanLoopStarted = false
        mainHandler.removeCallbacks(scanLoop)
    }

    companion object {
        private const val PREFS_NAME = "redline_settings_automation"
        private const val KEY_RUNNING = "automation_running"
        private const val KEY_PHASE = "automation_phase"
        const val KEY_STATUS = "automation_status"

        private const val PHASE_IDLE = 0
        private const val PHASE_FIND_DEVICE_CARE = 1
        private const val PHASE_RUN_OPTIMIZE = 2

        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val PHASE_TIMEOUT_MS = 20_000L
        private const val ACTION_COOLDOWN_MS = 900L
        private const val SCREEN_SCAN_INTERVAL_MS = 1_800L
        private const val MAX_CLICK_ANCESTORS = 6

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val expected = ComponentName(context, SettingsAutomationService::class.java)
            return enabled.split(':').any { entry ->
                val component = ComponentName.unflattenFromString(entry)
                component?.packageName == expected.packageName &&
                    component.className == expected.className
            }
        }

        fun start(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_RUNNING, true)
                .putInt(KEY_PHASE, PHASE_FIND_DEVICE_CARE)
                .putString(KEY_STATUS, "Starte Live-Screen-Analyse …")
                .apply()
        }

        fun stop(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_RUNNING, false)
                .putInt(KEY_PHASE, PHASE_IDLE)
                .putString(KEY_STATUS, "Bildschirmfreigabe manuell gestoppt.")
                .apply()
        }
    }
}
