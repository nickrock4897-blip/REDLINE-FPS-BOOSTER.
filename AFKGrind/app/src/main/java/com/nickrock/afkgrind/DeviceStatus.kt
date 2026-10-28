package com.nickrock.afkgrind

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings

data class DeviceSnapshot(val battery: Int?, val charging: Boolean, val saver: Boolean, val batteryOptimized: Boolean, val overlayAllowed: Boolean, val screenTimeoutMs: Int?)

object DeviceStatus {
 fun read(c: Context): DeviceSnapshot {
  val app = c.applicationContext
  val i = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
  val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
  val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
  val battery = if (level >= 0 && scale > 0) (level * 100f / scale).toInt().coerceIn(0, 100) else null
  val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
  val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
  val timeout = try { Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT) } catch (_: Exception) { -1 }
  return DeviceSnapshot(battery, status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL, pm.isPowerSaveMode,
   try { !pm.isIgnoringBatteryOptimizations(app.packageName) } catch (_: Exception) { false }, Settings.canDrawOverlays(app), timeout.takeIf { it >= 0 })
 }
}
