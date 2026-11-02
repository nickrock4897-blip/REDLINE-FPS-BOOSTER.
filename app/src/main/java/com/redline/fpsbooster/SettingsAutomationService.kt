package com.redline.fpsbooster

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import android.os.SystemClock
import java.util.Locale

/**
 * Explicitly enabled, settings-only automation.
 *
 * This service can inspect and click UI nodes only in Android Settings.
 * It never records the screen, performs global gestures, or interacts with
 * other applications. It only looks for a refresh-rate menu and a clearly
 * labelled high/adaptive refresh-rate option.
 */
class SettingsAutomationService : AccessibilityService() {

    private val prefs by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private var phaseStartedAt = 0L
    private var lastActionAt = 0L
    private var lastPhase = PHASE_IDLE

    override fun onServiceConnected() {
        super.onServiceConnected()
        // The XML configuration also restricts events to Android Settings.
        serviceInfo = serviceInfo.apply {
            packageNames = arrayOf("com.android.settings")
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 120
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!prefs.getBoolean(KEY_RUNNING, false)) return
        if (event?.packageName?.toString() != SETTINGS_PACKAGE) return

        val phase = prefs.getInt(KEY_PHASE, PHASE_IDLE)
        if (phase != lastPhase) {
            lastPhase = phase
            phaseStartedAt = SystemClock.elapsedRealtime()
            lastActionAt = 0L
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastActionAt < ACTION_COOLDOWN_MS) return

        val root = rootInActiveWindow ?: return
        when (phase) {
            PHASE_FIND_REFRESH_MENU -> {
                val clicked = clickFirstMatching(root) { text ->
                    containsAny(
                        text,
                        "bewegungsflussigkeit",
                        "bewegungsfluiditaet",
                        "motion smoothness",
                        "bildwiederholrate",
                        "bildwiederholfrequenz",
                        "bildschirmaktualisierungsrate",
                        "refresh rate",
                        "screen refresh rate"
                    )
                }

                if (clicked) {
                    lastActionAt = now
                    prefs.edit().putInt(KEY_PHASE, PHASE_SELECT_HIGH).apply()
                    Toast.makeText(applicationContext, "Menü gefunden. REDLINE sucht jetzt den höchsten klar erkennbaren Modus.", Toast.LENGTH_SHORT).show()
                } else if (now - phaseStartedAt > PHASE_TIMEOUT_MS) {
                    finish("Keine Bildwiederholraten-Einstellung gefunden. Es wurde nichts geändert.")
                }
            }

            PHASE_SELECT_HIGH -> {
                val clicked = clickFirstMatching(root) { text -> isHighRefreshOption(text) }
                if (clicked) {
                    lastActionAt = now
                    finish("Fertig: Der höhere/adaptive Bildwiederholraten-Modus wurde ausgewählt.")
                } else if (now - phaseStartedAt > PHASE_TIMEOUT_MS) {
                    finish("Kein höherer Modus vorhanden oder eindeutig erkennbar. Es wurde nichts geändert.")
                }
            }

            else -> stopAutomation()
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
                ) {
                    return true
                }
                candidate = current.parent
            }
        }

        for (index in 0 until root.childCount) {
            val child = root.getChild(index) ?: continue
            if (clickFirstMatching(child, predicate)) return true
        }
        return false
    }

    private fun isHighRefreshOption(text: String): Boolean {
        val value = normalize(text)
        if (value == "hoch" || value == "high" ||
            value == "adaptiv" || value == "adaptive"
        ) return true

        // Only accept an adaptive/high label when the same UI node also states
        // a refresh-rate value. This avoids matching "adaptive brightness".
        val hasRefreshValue = listOf("90 hz", "120 hz", "144 hz", "165 hz").any {
            value.contains(it)
        }
        val explicitHigh = value.startsWith("hoch ") || value.startsWith("high ")
        val explicitAdaptive = value.startsWith("adaptiv ") || value.startsWith("adaptive ")
        return hasRefreshValue || (explicitHigh && value.contains("hz")) ||
            (explicitAdaptive && value.contains("hz"))
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

    private fun finish(message: String) {
        stopAutomation()
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }

    override fun onInterrupt() {
        stopAutomation()
    }

    private fun stopAutomation() {
        prefs.edit()
            .putBoolean(KEY_RUNNING, false)
            .putInt(KEY_PHASE, PHASE_IDLE)
            .apply()
        lastPhase = PHASE_IDLE
    }

    companion object {
        private const val PREFS_NAME = "redline_settings_automation"
        private const val KEY_RUNNING = "automation_running"
        private const val KEY_PHASE = "automation_phase"

        private const val PHASE_IDLE = 0
        private const val PHASE_FIND_REFRESH_MENU = 1
        private const val PHASE_SELECT_HIGH = 2

        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val PHASE_TIMEOUT_MS = 10_000L
        private const val ACTION_COOLDOWN_MS = 700L
        private const val MAX_CLICK_ANCESTORS = 5

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val expected = ComponentName(context, SettingsAutomationService::class.java)
                .flattenToString()
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }

        fun start(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_RUNNING, true)
                .putInt(KEY_PHASE, PHASE_FIND_REFRESH_MENU)
                .apply()
        }

        fun stop(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_RUNNING, false)
                .putInt(KEY_PHASE, PHASE_IDLE)
                .apply()
        }
    }
}
