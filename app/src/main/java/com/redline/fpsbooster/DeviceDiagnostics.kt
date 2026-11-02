package com.redline.fpsbooster

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import android.view.WindowManager
import java.util.Locale

object DeviceDiagnostics {
    fun summary(context: Context): List<Pair<String, String>> {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also { activityManager.getMemoryInfo(it) }
        val storage = StatFs(context.filesDir.absolutePath)
        val display = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        val supportedModes = runCatching {
            display.supportedModes.map { it.refreshRate.toInt() }.distinct().sorted()
        }.getOrDefault(emptyList())
        val currentHz = runCatching { display.mode.refreshRate }.getOrNull()
        val oneUi = runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            clazz.getMethod("get", String::class.java)
                .invoke(null, "ro.build.version.sem") as? String
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Nicht auslesbar"

        return listOf(
            "Gerät" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "One UI" to oneUi,
            "Aktuelle Bildrate" to (currentHz?.let { "${"%.0f".format(Locale.GERMANY, it)} Hz" } ?: "Nicht auslesbar"),
            "Unterstützte Modi" to (supportedModes.takeIf { it.isNotEmpty() }?.joinToString(" / ") { "$it Hz" } ?: "Nicht auslesbar"),
            "RAM frei / gesamt" to "${formatBytes(memory.availMem)} / ${formatBytes(memory.totalMem)}",
            "Speicher frei / gesamt" to "${formatBytes(storage.availableBytes)} / ${formatBytes(storage.totalBytes)}"
        )
    }

    private fun formatBytes(bytes: Long): String {
        val gib = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return String.format(Locale.GERMANY, "%.1f GB", gib)
    }
}
