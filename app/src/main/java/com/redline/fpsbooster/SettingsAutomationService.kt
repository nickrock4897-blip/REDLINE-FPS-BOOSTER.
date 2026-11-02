package com.redline.fpsbooster

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.util.Locale

/**
 * Accessibility side of the user-started assistant.
 * It observes app identity to pause safely, but reads/clicks UI nodes only
 * inside Android Settings. It never enters text or clicks password/chat fields.
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
        serviceInfo = serviceInfo.apply {
            packageNames = null // app identity only; UI nodes are read only in Settings
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 120
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        LiveScreenState.setStatus("Bedienungshilfe bereit. Warte auf deine Freigabe.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventPackage = event?.packageName?.toString() ?: return
        LiveScreenState.setActivePackage(eventPackage)

        // Do not inspect or control other apps. The MediaProjection preview is
        // local, but OCR and accessibility actions are limited to Settings.
        if (eventPackage != SETTINGS_PACKAGE) {
            if (prefs.getBoolean(KEY_RUNNING, false)) {
                stopAutomation("Automatik pausiert: Android-Einstellungen verlassen.")
            }
            return
        }

        if (!prefs.getBoolean(KEY_RUNNING, false)) return

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
            PHASE_FIND_DEVICE_CARE -> {
                setStatus("CURRENT ACTION: Suche „Akku und Gerätewartung“ …")
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
                    setStatus("CURRENT ACTION: Gerätewartung geöffnet; nächste Seite wird geprüft.")
                } else if (now - phaseStartedAt > PHASE_TIMEOUT_MS) {
                    finish("Gerätewartung nicht gefunden. Keine Einstellung verändert.")
                }
            }

            PHASE_RUN_OPTIMIZE -> {
                setStatus("CURRENT ACTION: Suche den klar beschrifteten Knopf „Jetzt optimieren“ …")
                val clicked = clickFirstMatching(root) { label ->
                    val value = normalize(label)
                    value == "jetzt optimieren" ||
                        value == "optimize now" ||
                        value == "optimieren jetzt"
                }
                if (clicked) {
                    lastActionAt = now
                    finish("Samsung-Gerätewartung: „Jetzt optimieren“ wurde gestartet.")
                } else if (now - phaseStartedAt > PHASE_TIMEOUT_MS) {
                    finish("„Jetzt optimieren“ nicht gefunden. Es wurde nichts angetippt.")
                }
            }

            else -> stopAutomation("Automatik gestoppt.")
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
        LiveScreenState.setAction(message)
    }

    private fun finish(message: String) {
        stopAutomation(message)
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }

    override fun onInterrupt() {
        stopAutomation("Android hat die Bedienungshilfe unterbrochen.")
    }

    private fun stopAutomation(message: String = "Automatik gestoppt.") {
        prefs.edit()
            .putBoolean(KEY_RUNNING, false)
            .putInt(KEY_PHASE, PHASE_IDLE)
            .putString(KEY_STATUS, message)
            .apply()
        lastPhase = PHASE_IDLE
        LiveScreenState.setAction(message)
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
                .putString(KEY_STATUS, "Starte AI-Assistent …")
                .apply()
            LiveScreenState.setAction("Starte Android-Einstellungsanalyse")
        }

        fun stop(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_RUNNING, false)
                .putInt(KEY_PHASE, PHASE_IDLE)
                .putString(KEY_STATUS, "Automatik manuell gestoppt.")
                .apply()
            LiveScreenState.setAction("Automatik manuell gestoppt.")
        }
    }
}
