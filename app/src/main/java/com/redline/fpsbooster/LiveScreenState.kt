package com.redline.fpsbooster

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory screen session state. No frames or OCR text are persisted.
 */
object LiveScreenState {
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

    fun publishPreview(bitmap: Bitmap) {
        _preview.value = bitmap
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

    fun clear() {
        _preview.value = null
        _status.value = "Nicht aktiv"
        _currentAction.value = "Wartet auf Start"
        _recognizedSummary.value = "Noch keine Analyse"
        _activePackage.value = "Unbekannt"
    }
}
