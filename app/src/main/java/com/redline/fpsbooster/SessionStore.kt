package com.redline.fpsbooster

import android.content.Context
import android.os.SystemClock
import java.util.Locale

data class GrindRecord(val startWallMs: Long, val durationMs: Long)

object SessionStore {
    private const val PREFS = "redline_afk_store"
    private const val ACTIVE = "active"
    private const val RUNNING = "running"
    private const val ACCUMULATED = "accumulated"
    private const val RUNNING_SINCE = "running_since"
    private const val START_WALL = "start_wall"
    private const val HISTORY = "history"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun isActive(context: Context) = prefs(context).getBoolean(ACTIVE, false)
    fun isRunning(context: Context) = prefs(context).getBoolean(RUNNING, false)

    fun start(context: Context) {
        val p = prefs(context)
        if (p.getBoolean(ACTIVE, false)) return
        p.edit().putBoolean(ACTIVE, true).putBoolean(RUNNING, true)
            .putLong(ACCUMULATED, 0L).putLong(RUNNING_SINCE, SystemClock.elapsedRealtime())
            .putLong(START_WALL, System.currentTimeMillis()).apply()
    }

    fun pause(context: Context) {
        val p = prefs(context)
        if (!p.getBoolean(RUNNING, false)) return
        val now = SystemClock.elapsedRealtime()
        val elapsed = (now - p.getLong(RUNNING_SINCE, now)).coerceAtLeast(0L)
        p.edit().putLong(ACCUMULATED, p.getLong(ACCUMULATED, 0L) + elapsed)
            .putBoolean(RUNNING, false).putLong(RUNNING_SINCE, 0L).apply()
    }

    fun resume(context: Context) {
        val p = prefs(context)
        if (!p.getBoolean(ACTIVE, false) || p.getBoolean(RUNNING, false)) return
        p.edit().putBoolean(RUNNING, true).putLong(RUNNING_SINCE, SystemClock.elapsedRealtime()).apply()
    }

    fun durationMs(context: Context): Long {
        val p = prefs(context)
        val base = p.getLong(ACCUMULATED, 0L)
        if (!p.getBoolean(RUNNING, false)) return base
        return (base + (SystemClock.elapsedRealtime() - p.getLong(RUNNING_SINCE, SystemClock.elapsedRealtime())).coerceAtLeast(0L))
    }

    fun finish(context: Context) {
        val p = prefs(context)
        if (!p.getBoolean(ACTIVE, false)) return
        val duration = durationMs(context)
        val start = p.getLong(START_WALL, System.currentTimeMillis())
        val old = p.getString(HISTORY, "").orEmpty()
        val entry = "$start,$duration"
        p.edit().putString(HISTORY, if (old.isBlank()) entry else "$entry;$old")
            .putBoolean(ACTIVE, false).putBoolean(RUNNING, false)
            .putLong(ACCUMULATED, 0L).putLong(RUNNING_SINCE, 0L).apply()
    }

    fun records(context: Context): List<GrindRecord> =
        prefs(context).getString(HISTORY, "").orEmpty().split(';').mapNotNull { item ->
            val parts = item.split(',')
            if (parts.size != 2) null else {
                val start = parts[0].toLongOrNull()
                val duration = parts[1].toLongOrNull()
                if (start == null || duration == null) null else GrindRecord(start, duration)
            }
        }

    fun deleteRecord(context: Context, record: GrindRecord) {
        val filtered = records(context).filterNot { it == record }
        prefs(context).edit().putString(HISTORY, filtered.joinToString(";") { "${it.startWallMs},${it.durationMs}" }).apply()
    }

    fun totalMs(context: Context) = records(context).sumOf { it.durationMs } + if (isActive(context)) durationMs(context) else 0L

    fun format(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return String.format(Locale.GERMANY, "%02d:%02d:%02d", h, m, s)
    }
}
