package com.redline.fpsbooster

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Live, observable state for the user-consented screen session.
 * Only counters and short category summaries are retained; raw OCR text and frames are not stored.
 */
object LiveScreenState {
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _preview = MutableStateFlow<Bitmap?>(null)
    val preview = _preview.asStateFlow()

    private val _status = MutableStateFlow("Nicht aktiv")
    val status = _status.asStateFlow()

    private val _currentAction = MutableStateFlow("Wartet auf Start")
    val currentAction = _currentAction.asStateFlow()

    private val _recognizedSummary = MutableStateFlow("Noch keine Analyse")
    val recognizedSummary = _recognizedSummary.asStateFlow()

    private val _activePackage = MutableStateFlow("Unbekannt")
    val activePackage = _activePackage.asStateFlow()

    private val _aiState = MutableStateFlow("Stopped")
    val aiState = _aiState.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing = _isCapturing.asStateFlow()

    private val _framesReceived = MutableStateFlow(0L)
    val framesReceived = _framesReceived.asStateFlow()

    private val _framesAnalyzed = MutableStateFlow(0L)
    val framesAnalyzed = _framesAnalyzed.asStateFlow()

    private val _sceneChanges = MutableStateFlow(0L)
    val sceneChanges = _sceneChanges.asStateFlow()

    private val _lastFrameAt = MutableStateFlow(0L)
    val lastFrameAt = _lastFrameAt.asStateFlow()

    private val _lastError = MutableStateFlow("")
    val lastError = _lastError.asStateFlow()

    fun beginSession() {
        _aiState.value = "Starting"
        _isCapturing.value = false
        _status.value = "Starting AI… Bitte Bildschirmfreigabe bestätigen."
        _currentAction.value = "Starte Bildschirmübertragung"
        _recognizedSummary.value = "Warte auf erstes Bildschirmbild"
        _framesReceived.value = 0L
        _framesAnalyzed.value = 0L
        _sceneChanges.value = 0L
        _lastFrameAt.value = 0L
        _lastError.value = ""
        _activePackage.value = "Wird erkannt …"
    }

    fun captureReady() {
        _isCapturing.value = true
        _aiState.value = "Active"
        _status.value = "AI Active • warte auf erstes Frame"
        _currentAction.value = "Bildschirmquelle verbunden"
    }

    fun publishPreview(bitmap: Bitmap) {
        val previous = _preview.value
        _preview.value = bitmap
        if (previous != null && previous !== bitmap) {
            // Give Compose time to switch its Image reference before releasing native memory.
            mainHandler.postDelayed({
                if (_preview.value !== previous && !previous.isRecycled) {
                    runCatching { previous.recycle() }
                }
            }, PREVIEW_RELEASE_DELAY_MS)
        }
    }

    fun frameReceived(sceneChanged: Boolean) {
        _framesReceived.value = _framesReceived.value + 1
        if (sceneChanged) _sceneChanges.value = _sceneChanges.value + 1
        _lastFrameAt.value = SystemClock.elapsedRealtime()
        _lastError.value = ""
        if (_isCapturing.value) {
            _aiState.value = "Analyzing"
            _status.value = "Screen frame empfangen • lokale Analyse läuft"
            _currentAction.value = if (sceneChanged) "Bildschirmänderung erkannt – analysiere …" else "Analysiere aktuelles Bildschirmbild …"
        }
    }

    fun analysisCompleted(summary: String, sceneChanged: Boolean) {
        _framesAnalyzed.value = _framesAnalyzed.value + 1
        _recognizedSummary.value = summary
        if (_isCapturing.value) {
            _aiState.value = if (sceneChanged) "Reacting" else "Watching Screen"
            _status.value = if (sceneChanged) "Screen change erkannt • ausgewertet" else "AI Active • Bildschirm wird überwacht"
            _currentAction.value = summary
        }
    }

    fun analysisFailed(message: String) {
        _lastError.value = message
        _recognizedSummary.value = "Lokale Texterkennung fehlgeschlagen"
        if (_isCapturing.value) {
            _aiState.value = "Watching Screen"
            _status.value = "Bildschirm aktiv • Texterkennung mit Fehler"
            _currentAction.value = message
        }
    }

    fun setWatching() {
        if (_isCapturing.value) {
            _aiState.value = "Watching Screen"
            _status.value = "AI Active • wartet auf neue Bildschirmbilder"
        }
    }

    fun setPackage(value: String) {
        _activePackage.value = value
    }

    fun setAiState(value: String, action: String? = null) {
        _aiState.value = value
        if (action != null) _currentAction.value = action
    }

    fun setStatus(value: String) {
        _status.value = value
    }

    fun setAction(value: String) {
        _currentAction.value = value
    }

    fun setSummary(value: String) {
        _recognizedSummary.value = value
    }

    fun setActivePackage(value: String) {
        _activePackage.value = value
    }

    fun setError(message: String) {
        _lastError.value = message
        _aiState.value = "Error"
        _status.value = "AI Error • $message"
        _currentAction.value = message
    }

    fun stopSession(message: String = "Vom Benutzer gestoppt.") {
        _isCapturing.value = false
        _aiState.value = "Stopped"
        _status.value = message
        _currentAction.value = "Bildschirmübertragung beendet"
        _activePackage.value = "Unbekannt"
        _lastFrameAt.value = 0L
        val old = _preview.value
        _preview.value = null
        if (old != null) {
            mainHandler.postDelayed({
                if (_preview.value !== old && !old.isRecycled) runCatching { old.recycle() }
            }, PREVIEW_RELEASE_DELAY_MS)
        }
    }

    private const val PREVIEW_RELEASE_DELAY_MS = 2_000L
}
