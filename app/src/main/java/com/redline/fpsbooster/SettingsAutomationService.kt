package com.redline.fpsbooster

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import android.util.Log
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * User-started Device Care helper.
 *
 * It only reads/clicks allowlisted nodes in Android Settings and Samsung Device Care.
 * It scrolls Settings lists when the target preference is below the visible area.
 * It never taps coordinates, types text, or controls other apps.
 */
class SettingsAutomationService : AccessibilityService() {
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private var phaseStartedAt = 0L
    private var lastActionAt = 0L
    private var lastPhase = PHASE_IDLE
    private var wasOutsideAllowedApp = false
    private var scrollAttempts = 0
    private var lastRobloxPhase = PHASE_IDLE
    private var robloxPhaseStartedAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo.apply {
            packageNames = null // app identity events only outside the allowlist
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 150
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        LiveScreenState.setStatus("Bedienungshilfe bereit. Warte auf deine Freigabe.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val eventPackage = event?.packageName?.toString() ?: return
        LiveScreenState.setActivePackage(eventPackage)

        // UI nodes inside Roblox are inspected only after the user confirms the
        // specific FPS optimization or restore operation in REDLINE.
        if (eventPackage == ROBLOX_PACKAGE) {
            if (prefs.getBoolean(KEY_ROBLOX_RUNNING, false)) {
                rootInActiveWindow?.let { processRobloxGraphics(it) }
            }
            return
        }

        if (eventPackage !in CONTROL_PACKAGES) {
            if (prefs.getBoolean(KEY_RUNNING, false)) {
                wasOutsideAllowedApp = true
                setStatus("PAUSIERT: Außerhalb Android-Einstellungen/Device Care wird nichts gelesen oder angeklickt.")
            }
            return
        }

        if (!prefs.getBoolean(KEY_RUNNING, false)) return
        if (wasOutsideAllowedApp) {
            wasOutsideAllowedApp = false
            resetPhaseClock()
        }

        val phase = prefs.getInt(KEY_PHASE, PHASE_IDLE)
        if (phase != lastPhase) {
            lastPhase = phase
            resetPhaseClock()
        }

        val now = SystemClock.elapsedRealtime()
        if (now - lastActionAt < ACTION_COOLDOWN_MS) return
        val root = rootInActiveWindow ?: return

        when (phase) {
            PHASE_FIND_DEVICE_CARE -> findDeviceCare(root, now)
            PHASE_RUN_OPTIMIZE -> runOptimization(root, now)
            else -> stopAutomation("Automatik gestoppt.")
        }
    }

    private fun findDeviceCare(root: AccessibilityNodeInfo, now: Long) {
        setStatus("Suche Akku und Gerätewartung in den sichtbaren Einstellungen …")

        val clicked = clickFirstMatching(root) { label ->
            label.contains("akku und geraetewartung") ||
                label == "geraetewartung" ||
                label.contains("geraetewartung") ||
                label == "geraetepflege" ||
                label.contains("device care") ||
                label.contains("battery and device care") ||
                label.contains("device maintenance")
        }

        if (clicked) {
            lastActionAt = now
            scrollAttempts = 0
            prefs.edit().putInt(KEY_PHASE, PHASE_RUN_OPTIMIZE).apply()
            setStatus("Gerätewartung geöffnet. Suche jetzt die Schnelloptimierung …")
            return
        }

        if (scrollAttempts < MAX_SCROLL_ATTEMPTS && scrollFirstAvailableContainer(root)) {
            scrollAttempts++
            lastActionAt = now
            setStatus("Einstellungsseite wird durchsucht … ($scrollAttempts/$MAX_SCROLL_ATTEMPTS)")
            return
        }

        if (now - phaseStartedAt > PHASE_TIMEOUT_MS) {
            finish("Akku und Gerätewartung wurde in den sichtbaren Einstellungen nicht gefunden. Es wurde nichts verändert. Bitte prüfe, ob die Bedienungshilfe aktiv ist und die Android-Einstellungen geöffnet sind.")
        }
    }

    private fun runOptimization(root: AccessibilityNodeInfo, now: Long) {
        setStatus("Suche den eindeutig beschrifteten Knopf „Jetzt optimieren“ …")

        val clicked = clickFirstMatching(root) { label ->
            label == "jetzt optimieren" ||
                label == "optimize now" ||
                label == "optimieren jetzt"
        }

        if (clicked) {
            lastActionAt = now
            finish("Samsung-Gerätewartung: „Jetzt optimieren“ wurde gestartet.")
            return
        }

        // Samsung may hide Quick optimization when Device Care reports that the
        // device is already in good condition. Never substitute a different action.
        val visibleText = collectVisibleText(root)
        if (isAlreadyHealthy(visibleText)) {
            finish("Samsung meldet einen guten Gerätezustand. Die Schnelloptimierung wird deshalb möglicherweise nicht angezeigt. Es wurde nichts verändert.")
            return
        }

        if (now - phaseStartedAt > PHASE_TIMEOUT_MS) {
            finish("„Jetzt optimieren“ ist auf dieser Samsung-Seite nicht sichtbar. Samsung blendet die Schnelloptimierung bei gutem Gerätezustand manchmal aus. Es wurde kein anderer Knopf angetippt.")
        }
    }

    /**
     * Search every visible node. Match text and contentDescription separately:
     * combining both strings made exact button labels fail on some One UI versions.
     */
    private fun clickFirstMatching(
        root: AccessibilityNodeInfo,
        predicate: (String) -> Boolean
    ): Boolean {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)

        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            val labels = listOf(
                normalize(node.text?.toString().orEmpty()),
                normalize(node.contentDescription?.toString().orEmpty())
            ).filter { it.isNotBlank() }

            if (labels.any(predicate) && clickNodeOrClickableParent(node)) {
                return true
            }

            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                pending.add(child)
            }
        }
        return false
    }

    private fun clickNodeOrClickableParent(node: AccessibilityNodeInfo): Boolean {
        var candidate: AccessibilityNodeInfo? = node
        repeat(MAX_CLICK_ANCESTORS) {
            val current = candidate ?: return@repeat
            if (current.isEnabled && current.isClickable &&
                current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) {
                return true
            }
            candidate = current.parent
        }
        return false
    }

    private fun scrollFirstAvailableContainer(root: AccessibilityNodeInfo): Boolean {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)

        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (node.isEnabled && node.isScrollable &&
                node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            ) {
                return true
            }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                pending.add(child)
            }
        }
        return false
    }

    private fun collectVisibleText(root: AccessibilityNodeInfo): String {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        val values = ArrayList<String>()
        pending.add(root)
        while (pending.isNotEmpty() && values.size < MAX_TEXT_NODES) {
            val node = pending.removeFirst()
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let { values.add(normalize(it)) }
            node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { values.add(normalize(it)) }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                pending.add(child)
            }
        }
        return values.joinToString(" ")
    }

    private fun isAlreadyHealthy(value: String): Boolean =
        value.contains("geraet ist in gutem zustand") ||
            value.contains("dein geraet ist in gutem zustand") ||
            value.contains("guter geraetezustand") ||
            value.contains("good condition") ||
            value.contains("great condition") ||
            value.contains("alles in ordnung")

    private fun processRobloxGraphics(root: AccessibilityNodeInfo) {
        val phase = prefs.getInt(KEY_ROBLOX_PHASE, PHASE_ROBLOX_OPEN_MENU)
        if (phase != lastRobloxPhase) {
            lastRobloxPhase = phase
            robloxPhaseStartedAt = SystemClock.elapsedRealtime()
            lastActionAt = 0L
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastActionAt < ACTION_COOLDOWN_MS) return

        when (phase) {
            PHASE_ROBLOX_OPEN_MENU -> {
                val text = collectVisibleText(root)
                if (hasGraphicsQualityLabel(text)) {
                    setRobloxPhase(PHASE_ROBLOX_CHECK_MODE, "Roblox-Grafikmenü bereits geöffnet.")
                } else if (clickFirstMatching(root) { it == "roblox menu" || it == "menu" || it == "pause menu" }) {
                    lastActionAt = now
                    setRobloxPhase(PHASE_ROBLOX_OPEN_SETTINGS, "Roblox-Menü geöffnet. Suche Einstellungen …")
                } else if (robloxPhaseTimedOut()) {
                    finishRoblox("Roblox-Menü nicht gefunden. Öffne in einer laufenden Erfahrung zuerst das Roblox-Menü und starte die Optimierung erneut.")
                }
            }

            PHASE_ROBLOX_OPEN_SETTINGS -> {
                val text = collectVisibleText(root)
                if (hasGraphicsQualityLabel(text) || text.contains("graphics mode") || text.contains("grafikmodus")) {
                    setRobloxPhase(PHASE_ROBLOX_CHECK_MODE, "Roblox-Grafikeinstellungen erkannt.")
                } else if (clickFirstMatching(root) { it == "settings" || it == "einstellungen" }) {
                    lastActionAt = now
                    setRobloxPhase(PHASE_ROBLOX_CHECK_MODE, "Roblox-Einstellungen geöffnet. Prüfe Grafikmodus …")
                } else if (robloxPhaseTimedOut()) {
                    finishRoblox("Roblox-Einstellungen wurden nicht gefunden. Es wurde nichts geändert.")
                }
            }

            PHASE_ROBLOX_CHECK_MODE -> {
                val text = collectVisibleText(root)
                val slider = findGraphicsSlider(root)
                if (hasGraphicsQualityLabel(text) && slider != null) {
                    setRobloxPhase(PHASE_ROBLOX_SET_QUALITY, "Grafikregler erkannt. Prüfe aktuellen Wert …")
                } else if (clickFirstMatching(root) { it == "graphics mode" || it == "grafikmodus" || it.startsWith("graphics mode ") }) {
                    lastActionAt = now
                    setRobloxPhase(PHASE_ROBLOX_SELECT_MANUAL, "Grafikmodus geöffnet. Suche Manual …")
                } else if (robloxPhaseTimedOut()) {
                    finishRoblox("Roblox-Grafikmodus oder Grafikregler ist nicht zugänglich. Es wurde nichts geändert.")
                }
            }

            PHASE_ROBLOX_SELECT_MANUAL -> {
                val text = collectVisibleText(root)
                if (hasGraphicsQualityLabel(text) && findGraphicsSlider(root) != null) {
                    setRobloxPhase(PHASE_ROBLOX_SET_QUALITY, "Manueller Grafikregler erkannt.")
                } else if (clickFirstMatching(root) { it == "manual" || it == "manuell" }) {
                    lastActionAt = now
                    setRobloxPhase(PHASE_ROBLOX_SET_QUALITY, "Manueller Grafikmodus ausgewählt.")
                } else if (robloxPhaseTimedOut()) {
                    finishRoblox("Die Option Manual wurde nicht zugänglich angezeigt. Keine Grafikqualität verändert.")
                }
            }

            PHASE_ROBLOX_SET_QUALITY -> {
                val node = findGraphicsSlider(root)
                val range = node?.rangeInfo
                if (node == null || range == null || range.max <= range.min) {
                    if (robloxPhaseTimedOut()) finishRoblox("Roblox-Grafikregler ist über die Bedienungshilfe nicht erreichbar. Es wurde nichts geändert.")
                    return
                }

                val restore = prefs.getBoolean(KEY_ROBLOX_RESTORE, false)
                val requested = if (restore) {
                    if (!prefs.contains(KEY_ROBLOX_ORIGINAL)) {
                        finishRoblox("Kein gespeicherter Ausgangswert vorhanden. Nichts wiederhergestellt.")
                        return
                    }
                    prefs.getFloat(KEY_ROBLOX_ORIGINAL, range.current).coerceIn(range.min, range.max)
                } else {
                    prefs.edit()
                        .putFloat(KEY_ROBLOX_ORIGINAL, range.current)
                        .putFloat(KEY_ROBLOX_RANGE_MIN, range.min)
                        .putFloat(KEY_ROBLOX_RANGE_MAX, range.max)
                        .apply()
                    val target = range.min + (range.max - range.min) * PERFORMANCE_QUALITY_FRACTION
                    if (range.type == AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT) target.roundToInt().toFloat() else target
                }

                val args = Bundle().apply {
                    putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, requested)
                }
                if (node.performAction(AccessibilityNodeInfo.ACTION_SET_PROGRESS, args)) {
                    prefs.edit()
                        .putFloat(KEY_ROBLOX_TARGET, requested)
                        .putInt(KEY_ROBLOX_PHASE, PHASE_ROBLOX_VERIFY)
                        .apply()
                    lastActionAt = now
                    setStatus(if (restore) "Stelle ursprüngliche Roblox-Grafikqualität wieder her …" else "Setze Roblox-Grafikqualität auf Leistungsmodus …")
                    LiveScreenState.setAiState("Analyzing", "Roblox-Grafikregler wird angepasst")
                    lastRobloxPhase = PHASE_ROBLOX_VERIFY
                    robloxPhaseStartedAt = now
                } else if (robloxPhaseTimedOut()) {
                    finishRoblox("Android hat die Änderung am Grafikregler abgelehnt. Keine weitere Einstellung wurde verändert.")
                }
            }

            PHASE_ROBLOX_VERIFY -> {
                val range = findGraphicsSlider(root)?.rangeInfo
                val target = prefs.getFloat(KEY_ROBLOX_TARGET, Float.NaN)
                if (range != null && target.isFinite() && abs(range.current - target) <= 0.3f) {
                    val restored = prefs.getBoolean(KEY_ROBLOX_RESTORE, false)
                    finishRoblox(
                        if (restored) "Ursprüngliche Roblox-Grafikqualität wurde wiederhergestellt."
                        else "Roblox-Grafikqualität wurde auf einen niedrigeren Wert gesetzt. Android liefert keine zuverlässige FPS-Messung für Roblox."
                    )
                    LiveScreenState.setAiState("Reacting", "Roblox-Grafikänderung erfolgreich überprüft")
                } else if (robloxPhaseTimedOut()) {
                    finishRoblox("Die Änderung konnte nicht sicher bestätigt werden. Bitte kontrolliere den Roblox-Regler manuell.")
                }
            }

            else -> finishRoblox("Roblox-Optimierung wurde beendet.")
        }
    }

    private fun hasGraphicsQualityLabel(value: String): Boolean =
        value.contains("graphics quality") || value.contains("grafikqualitaet")

    private fun findGraphicsSlider(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val screenText = collectVisibleText(root)
        if (!hasGraphicsQualityLabel(screenText)) return null

        val pending = ArrayDeque<AccessibilityNodeInfo>()
        val candidates = ArrayList<AccessibilityNodeInfo>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            val range = node.rangeInfo
            val className = node.className?.toString().orEmpty().lowercase(Locale.ROOT)
            val label = normalize(node.text?.toString().orEmpty() + " " + node.contentDescription?.toString().orEmpty())
            val explicitlyLabelled = hasGraphicsQualityLabel(label)
            if (range != null && range.max > range.min && node.isEnabled &&
                (explicitlyLabelled || className.contains("seekbar"))
            ) {
                candidates.add(node)
            }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                pending.add(child)
            }
        }
        // If the slider itself has no label, only use it when exactly one seekbar
        // is present on the screen that explicitly contains the Graphics Quality heading.
        return candidates.singleOrNull()
    }

    private fun setRobloxPhase(next: Int, message: String) {
        prefs.edit().putInt(KEY_ROBLOX_PHASE, next).putString(KEY_ROBLOX_STATUS, message).apply()
        lastRobloxPhase = next
        robloxPhaseStartedAt = SystemClock.elapsedRealtime()
        lastActionAt = 0L
        setStatus(message)
        LiveScreenState.setAiState("Analyzing", message)
    }

    private fun robloxPhaseTimedOut(): Boolean =
        SystemClock.elapsedRealtime() - robloxPhaseStartedAt > ROBLOX_PHASE_TIMEOUT_MS

    private fun finishRoblox(message: String) {
        prefs.edit()
            .putBoolean(KEY_ROBLOX_RUNNING, false)
            .putBoolean(KEY_ROBLOX_RESTORE, false)
            .putString(KEY_ROBLOX_STATUS, message)
            .apply()
        lastRobloxPhase = PHASE_IDLE
        setStatus(message)
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }

    private fun resetPhaseClock() {
        phaseStartedAt = SystemClock.elapsedRealtime()
        lastActionAt = 0L
        scrollAttempts = 0
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

    private fun setStatus(message: String) {
        prefs.edit().putString(KEY_STATUS, message).apply()
        LiveScreenState.setAction(message)
    }

    private fun finish(message: String) {
        stopAutomation(message)
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }

    override fun onInterrupt() {
        LiveScreenState.setActivePackage("Bedienungshilfe unterbrochen")
        stopAutomation("Android hat die Bedienungshilfe unterbrochen.")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        LiveScreenState.setActivePackage("Bedienungshilfe nicht aktiv")
        stopAutomation("Bedienungshilfe wurde getrennt.")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        LiveScreenState.setActivePackage("Bedienungshilfe nicht aktiv")
        stopAutomation("Bedienungshilfe nicht aktiv.")
        super.onDestroy()
    }

    private fun stopAutomation(message: String = "Automatik gestoppt.") {
        prefs.edit()
            .putBoolean(KEY_RUNNING, false)
            .putBoolean(KEY_ROBLOX_RUNNING, false)
            .putBoolean(KEY_ROBLOX_RESTORE, false)
            .putInt(KEY_PHASE, PHASE_IDLE)
            .putString(KEY_STATUS, message)
            .apply()
        lastPhase = PHASE_IDLE
        wasOutsideAllowedApp = false
        scrollAttempts = 0
        LiveScreenState.setAction(message)
    }

    companion object {
        private const val PREFS_NAME = "redline_settings_automation"
        private const val KEY_RUNNING = "automation_running"
        private const val KEY_PHASE = "automation_phase"
        private const val KEY_ROBLOX_RUNNING = "roblox_optimization_running"
        private const val KEY_ROBLOX_PHASE = "roblox_optimization_phase"
        private const val KEY_ROBLOX_RESTORE = "roblox_restore_mode"
        private const val KEY_ROBLOX_ORIGINAL = "roblox_original_graphics_quality"
        private const val KEY_ROBLOX_RANGE_MIN = "roblox_graphics_min"
        private const val KEY_ROBLOX_RANGE_MAX = "roblox_graphics_max"
        private const val KEY_ROBLOX_TARGET = "roblox_graphics_target"
        private const val KEY_ROBLOX_STATUS = "roblox_optimization_status"
        const val KEY_STATUS = "automation_status"

        private const val PHASE_IDLE = 0
        private const val PHASE_FIND_DEVICE_CARE = 1
        private const val PHASE_RUN_OPTIMIZE = 2
        private const val PHASE_ROBLOX_OPEN_MENU = 10
        private const val PHASE_ROBLOX_OPEN_SETTINGS = 11
        private const val PHASE_ROBLOX_CHECK_MODE = 12
        private const val PHASE_ROBLOX_SELECT_MANUAL = 13
        private const val PHASE_ROBLOX_SET_QUALITY = 14
        private const val PHASE_ROBLOX_VERIFY = 15

        private const val SETTINGS_PACKAGE = "com.android.settings"
        private const val DEVICE_CARE_PACKAGE = "com.samsung.android.lool"
        private const val ROBLOX_PACKAGE = "com.roblox.client"
        private const val ROBLOX_PHASE_TIMEOUT_MS = 18_000L
        private const val PERFORMANCE_QUALITY_FRACTION = 0.25f
        private val CONTROL_PACKAGES = setOf(SETTINGS_PACKAGE, DEVICE_CARE_PACKAGE)
        private const val PHASE_TIMEOUT_MS = 35_000L
        private const val ACTION_COOLDOWN_MS = 850L
        private const val MAX_CLICK_ANCESTORS = 12
        private const val MAX_SCROLL_ATTEMPTS = 6
        private const val MAX_TEXT_NODES = 500

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

        fun startRobloxFpsOptimization(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_RUNNING, false)
                .putBoolean(KEY_ROBLOX_RUNNING, true)
                .putBoolean(KEY_ROBLOX_RESTORE, false)
                .putInt(KEY_ROBLOX_PHASE, PHASE_ROBLOX_OPEN_MENU)
                .putString(KEY_ROBLOX_STATUS, "Warte auf Roblox-Menü …")
                .apply()
            LiveScreenState.setAiState("Analyzing", "Roblox-Grafikeinstellungen werden gesucht")
        }

        fun restoreRobloxGraphics(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_RUNNING, false)
                .putBoolean(KEY_ROBLOX_RUNNING, true)
                .putBoolean(KEY_ROBLOX_RESTORE, true)
                .putInt(KEY_ROBLOX_PHASE, PHASE_ROBLOX_OPEN_MENU)
                .putString(KEY_ROBLOX_STATUS, "Warte auf Roblox-Menü zur Wiederherstellung …")
                .apply()
            LiveScreenState.setAiState("Analyzing", "Gespeicherte Roblox-Grafikeinstellung wird gesucht")
        }

        fun hasSavedRobloxGraphics(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).contains(KEY_ROBLOX_ORIGINAL)

        fun start(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_RUNNING, true)
                .putInt(KEY_PHASE, PHASE_FIND_DEVICE_CARE)
                .putString(KEY_STATUS, "Starte Gerätewartungssuche …")
                .apply()
            LiveScreenState.setAction("Suche Akku und Gerätewartung")
        }

        fun stop(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_RUNNING, false)
                .putBoolean(KEY_ROBLOX_RUNNING, false)
                .putBoolean(KEY_ROBLOX_RESTORE, false)
                .putInt(KEY_PHASE, PHASE_IDLE)
                .putString(KEY_STATUS, "Automatik manuell gestoppt.")
                .apply()
            LiveScreenState.setAction("Automatik manuell gestoppt.")
        }
    }
}
