package com.nickrock.afkgrind

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import kotlin.math.roundToInt

class AfkOverlayService : Service() {
 companion object {
  const val START = "afk.start"; const val PAUSE = "afk.pause"; const val RESUME = "afk.resume"; const val FINISH = "afk.finish"
  private const val CHANNEL = "afk-grind-session"; private const val NOTIFICATION_ID = 4410
 }
 private lateinit var wm: WindowManager
 private var panel: View? = null
 private var timer: TextView? = null
 private var state: TextView? = null
 private var expanded = true
 private var lp: WindowManager.LayoutParams? = null
 private val handler = Handler(Looper.getMainLooper())

 override fun onCreate() {
  super.onCreate(); wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
  if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "AFK Grind", NotificationManager.IMPORTANCE_LOW))
  val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("open_settings", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("AFK Grind aktiv").setContentText("Sitzungstimer und optionales Overlay").setContentIntent(open).setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE).build()
  startForeground(NOTIFICATION_ID, notification)
 }
 override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
  when (intent?.action) {
   PAUSE -> GrindStore.pause(this)
   RESUME, START -> GrindStore.startOrResume(this)
   FINISH -> { GrindStore.finish(this); stopSelf(); return START_NOT_STICKY }
  }
  if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
  if (panel == null) buildPanel() else refresh()
  return START_STICKY
 }
 private fun buildPanel() {
  val density = resources.displayMetrics.density; fun dp(n: Int) = (n * density).roundToInt()
  val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(10)); background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(38,22,62), Color.rgb(82,25,48))).apply { cornerRadius = dp(18).toFloat(); setStroke(dp(1), Color.rgb(158,74,255)) } }
  val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
  header.addView(TextView(this).apply { text = "AFK GRIND"; setTextColor(Color.WHITE); textSize = 13f; typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, dp(36), 1f))
  header.addView(Button(this).apply { text = "↕"; minWidth = dp(38); minHeight = dp(34); setOnClickListener { expanded = !expanded; rebuild() } }, LinearLayout.LayoutParams(dp(42), dp(36)))
  header.setOnTouchListener(object : View.OnTouchListener {
   var sx = 0f; var sy = 0f; var dx = 0f; var dy = 0f
   override fun onTouch(v: View, e: MotionEvent): Boolean {
    val p = lp ?: return false
    when (e.action) {
     MotionEvent.ACTION_DOWN -> { sx = p.x.toFloat(); sy = p.y.toFloat(); dx = e.rawX; dy = e.rawY; return true }
     MotionEvent.ACTION_MOVE -> { p.x = (sx - (e.rawX - dx)).roundToInt().coerceAtLeast(0); p.y = (sy + (e.rawY - dy)).roundToInt().coerceAtLeast(0); try { panel?.let { wm.updateViewLayout(it, p) } } catch (_: Exception) { }; return true }
     MotionEvent.ACTION_UP -> return true
    }; return false
   }
  })
  box.addView(header)
  timer = TextView(this).apply { textSize = 22f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(5), 0, dp(3)) }
  state = TextView(this).apply { textSize = 10f; setTextColor(Color.rgb(208,190,255)) }
  box.addView(timer); box.addView(state)
  if (expanded) {
   box.addView(action("Pause / Fortsetzen", dp(38)) { if (GrindStore.running(this)) GrindStore.pause(this) else GrindStore.startOrResume(this); refresh() })
   box.addView(action("AFK OFF – beenden", dp(38)) { GrindStore.finish(this); stopSelf() })
   box.addView(action("AFK Settings öffnen", dp(38)) { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("open_settings", true)) })
  } else box.addView(action("Menü öffnen", dp(36)) { expanded = true; rebuild() })
  val params = lp ?: WindowManager.LayoutParams(dp(244), WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.END; x = dp(12); y = dp(100) }
  params.width = dp(244); params.height = WindowManager.LayoutParams.WRAP_CONTENT
  params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or (if (GrindStore.running(this)) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0)
  lp = params
  try { if (panel == null) { panel = box; wm.addView(box, params) } else { wm.removeView(panel); panel = box; wm.addView(box, params) } } catch (_: Exception) { panel = null; stopSelf(); return }
  refresh()
 }
 private fun action(label: String, height: Int, onClick: () -> Unit) = Button(this).apply { text = label; isAllCaps = false; textSize = 11f; minHeight = height; setOnClickListener { onClick() } }
 private fun rebuild() { try { panel?.let { wm.removeView(it) } } catch (_: Exception) { }; panel = null; buildPanel() }
 private fun refresh() {
  timer?.text = formatTime(GrindStore.elapsed(this))
  state?.text = when { GrindStore.running(this) -> "DISPLAY-SCHUTZ: AKTIV"; GrindStore.paused(this) -> "SITZUNG PAUSIERT"; else -> "AFK GRIND: OFF" }
  lp?.let { p -> p.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or (if (GrindStore.running(this)) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0); try { panel?.let { wm.updateViewLayout(it, p) } } catch (_: Exception) { } }
  handler.removeCallbacks(ticker); if (panel != null) handler.postDelayed(ticker, 1000L)
 }
 private val ticker = object : Runnable { override fun run() { refresh() } }
 override fun onDestroy() { handler.removeCallbacksAndMessages(null); try { panel?.let { wm.removeView(it) } } catch (_: Exception) { }; panel = null; super.onDestroy() }
 override fun onBind(intent: Intent?): IBinder? = null
}
fun formatTime(ms: Long): String {
 val s = ms.coerceAtLeast(0L) / 1000L
 return "%02d:%02d:%02d".format(s / 3600L, (s % 3600L) / 60L, s % 60L)
}
