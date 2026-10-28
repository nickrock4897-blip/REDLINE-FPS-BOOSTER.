package com.nickrock.afkgrind

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

private val Bg = Color(0xFF100E18)
private val Panel = Color(0xFF1D192A)
private val Purple = Color(0xFF973DFF)
private val Red = Color(0xFFFF4868)
private val Muted = Color(0xFFB9B1CA)
private val Good = Color(0xFF6CE5B1)
private val Warn = Color(0xFFFFC45C)
private enum class Page(val label: String, val mark: String) { Dashboard("Start","⌂"), Settings("AFK Settings","⚙"), Performance("Leistung","✦"), History("Verlauf","◷"), General("Mehr","⋯") }

class MainActivity : ComponentActivity() {
 private var page by mutableStateOf(Page.Dashboard)
 private var tick by mutableLongStateOf(0L)
 private var overlayDialog by mutableStateOf(false)
 private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
 override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)
  if (intent.getBooleanExtra("open_settings", false)) page = Page.Settings
  applyKeepScreen()
  if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
  setContent {
   MaterialTheme(colorScheme = darkColorScheme(primary = Purple, secondary = Red, background = Bg, surface = Panel)) {
    LaunchedEffect(Unit) { while (true) { tick = System.currentTimeMillis(); applyKeepScreen(); delay(1000L) } }
    Surface(Modifier.fillMaxSize(), color = Bg) {
     AppShell(page, { page = it }, tick, overlayDialog, { overlayDialog = false }, { overlayDialog = false; openOverlaySettings() },
      { startSession() }, { pauseSession() }, { finishSession() }, { openOverlaySettings() }, { openBatterySettings() },
      { startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS)) }, { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) })
    }
   }
  }
 }
 override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); if (intent.getBooleanExtra("open_settings", false)) page = Page.Settings }
 override fun onResume() { super.onResume(); applyKeepScreen() }
 private fun applyKeepScreen() { if (GrindStore.running(this)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
 private fun startSession() {
  GrindStore.startOrResume(this); applyKeepScreen()
  if (Settings.canDrawOverlays(this)) ContextCompat.startForegroundService(this, Intent(this, AfkOverlayService::class.java).setAction(AfkOverlayService.START)) else overlayDialog = true
 }
 private fun pauseSession() {
  GrindStore.pause(this); applyKeepScreen()
  if (Settings.canDrawOverlays(this)) ContextCompat.startForegroundService(this, Intent(this, AfkOverlayService::class.java).setAction(AfkOverlayService.PAUSE))
 }
 private fun finishSession() {
  GrindStore.finish(this); window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  if (Settings.canDrawOverlays(this)) ContextCompat.startForegroundService(this, Intent(this, AfkOverlayService::class.java).setAction(AfkOverlayService.FINISH))
 }
 private fun openOverlaySettings() { try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } catch (_: Exception) { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } }
 private fun openBatterySettings() { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
}

@Composable private fun AppShell(
 page: Page, changePage: (Page) -> Unit, tick: Long, overlayDialog: Boolean,
 dismissOverlay: () -> Unit, enableOverlay: () -> Unit, start: () -> Unit, pause: () -> Unit, finish: () -> Unit,
 overlaySettings: () -> Unit, batterySettings: () -> Unit, displaySettings: () -> Unit, appSettings: () -> Unit
) {
 val c = LocalContext.current
 val device = remember(tick) { DeviceStatus.read(c) }
 val running = remember(tick) { GrindStore.running(c) }
 val paused = remember(tick) { GrindStore.paused(c) }
 val elapsed = remember(tick) { GrindStore.elapsed(c) }
 val total = remember(tick) { GrindStore.total(c) }
 var performance by remember { mutableStateOf(GrindStore.performance(c)) }
 var warning by remember { mutableIntStateOf(GrindStore.warning(c)) }
 var historyRefresh by remember { mutableIntStateOf(0) }
 Column(Modifier.fillMaxSize().padding(WindowInsets.navigationBars.asPaddingValues())) {
  Column(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Purple, Red))).padding(20.dp)) {
   Text("AFK GRIND", fontSize = 25.sp, fontWeight = FontWeight.Black, color = Color.White)
   Text("Deine Sitzung. Dein Timer. Dein Überblick.", fontSize = 13.sp, color = Color.White.copy(alpha = .9f))
  }
  Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
   when (page) {
    Page.Dashboard -> {
     Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(24.dp)) {
      Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
       Text(if (running) "AFK GRIND: ON" else if (paused) "AFK GRIND: PAUSIERT" else "AFK GRIND: OFF", color = if (running) Good else Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
       Spacer(Modifier.height(10.dp))
       Box(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color(0xFF42216C), Color(0xFF49202D))), RoundedCornerShape(18.dp)).padding(18.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("GRIND-ZEIT", color = Muted, fontSize = 12.sp); Text(formatTime(elapsed), color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Black) }
       }
       Spacer(Modifier.height(10.dp))
       StatusLine("Display-Schutz", if (running) "AKTIV" else "INAKTIV", if (running) Good else Warn)
       StatusLine("AFK Settings", if (device.overlayAllowed && device.screenTimeoutMs != null) "GRUNDFUNKTIONEN BEREIT" else "PRÜFEN", if (device.overlayAllowed && device.screenTimeoutMs != null) Good else Warn)
       StatusLine("Performance Mode", if (performance) "ON" else "OFF", Purple)
       StatusLine("Akku", device.battery?.let { "$it%" } ?: "Nicht verfügbar", if ((device.battery ?: 100) <= warning) Warn else Color.White)
       Spacer(Modifier.height(14.dp))
       Button(onClick = start, modifier = Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = if (running) Color(0xFF3B3349) else Purple), shape = RoundedCornerShape(16.dp)) {
        Text(if (running) "AFK GRIND LÄUFT" else if (paused) "FORTSETZEN" else "AFK GRIND STARTEN", fontWeight = FontWeight.Bold)
       }
       Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = pause, modifier = Modifier.weight(1f), enabled = running, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF332B42))) { Text("Pause") }
        Button(onClick = { finish(); historyRefresh++ }, modifier = Modifier.weight(1f), enabled = running || paused, colors = ButtonDefaults.buttonColors(containerColor = Red)) { Text("Beenden") }
       }
      }
     }
     Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Metric("GESAMTZEIT", formatTime(total), Modifier.weight(1f)); Metric("OVERLAY", if (device.overlayAllowed) "Erlaubt" else "Fehlt", Modifier.weight(1f))
     }
     PanelCard("SCHNELLZUGRIFF") {
      ActionButton("AFK SETTINGS OPTIMIEREN", overlaySettings); ActionButton("Akku-Einstellungen öffnen", batterySettings); ActionButton("Performance Mode", { changePage(Page.Performance) })
     }
     if ((device.battery ?: 100) <= warning) InfoCard("Akku niedrig: Bei längeren Sitzungen kann das Tablet leer werden. Achte auch auf Wärme.", Warn)
    }
    Page.Settings -> {
     Text("AFK SETTINGS", fontSize = 22.sp, color = Color.White, fontWeight = FontWeight.Bold)
     Text("Diese Prüfung ist auf Informationen begrenzt, die Android dieser App zugänglich macht.", fontSize = 13.sp, color = Muted)
     StatusCard("Schwebendes Menü", if (device.overlayAllowed) "AKTIV" else "NICHT AKTIV", if (device.overlayAllowed) Good else Warn)
     StatusCard("Display-Zeitüberschreitung", device.screenTimeoutMs?.let { timeoutLabel(it) } ?: "Nicht verfügbar", Muted)
     StatusCard("Akkuoptimierung", if (device.batteryOptimized) "App ist nicht ausgenommen" else "Status eingeschränkt oder Ausnahme aktiv", if (device.batteryOptimized) Warn else Good)
     StatusCard("Energiesparmodus", if (device.saver) "AKTIV" else "Inaktiv", if (device.saver) Warn else Good)
     StatusCard("Hintergrundbetrieb", "Vordergrunddienst beim Start verfügbar", Good)
     StatusCard("Manuelle Sperre", "Kann Sitzung unterbrechen", Warn)
     ActionButton("AFK SETTINGS OPTIMIEREN", { if (!device.overlayAllowed) overlaySettings() else batterySettings() })
     ActionButton("Overlay-Berechtigung einrichten", overlaySettings); ActionButton("Akku-Einstellungen öffnen", batterySettings)
     ActionButton("Display-Einstellungen öffnen", displaySettings); ActionButton("App-Berechtigungen ansehen", appSettings)
     InfoCard("AFK Grind kann geschützte Einstellungen nicht heimlich ändern. Eine Ausnahme bei der Akkuoptimierung ist optional; Herstellerregeln können den Hintergrundbetrieb trotzdem beeinflussen.", Muted)
    }
    Page.Performance -> {
     Text("PERFORMANCE MODE", fontSize = 22.sp, color = Color.White, fontWeight = FontWeight.Bold)
     PanelCard("APP-LEISTUNG") {
      Row(verticalAlignment = Alignment.CenterVertically) {
       Column(Modifier.weight(1f)) { Text("Ressourcenschonender Modus", color = Color.White, fontWeight = FontWeight.Bold); Text("Begrenzt nur app-eigene Zusatzarbeit; ändert Roblox nicht.", color = Muted, fontSize = 12.sp) }
       Switch(checked = performance, onCheckedChange = { performance = it; GrindStore.setPerformance(c, it) })
      }
      StatusCard("Timer", "Ein Update pro Sekunde", Good); StatusCard("Animationen", "Nur dezente App-Oberfläche", Good)
      StatusCard("Akkustand", device.battery?.let { "$it%" } ?: "Nicht verfügbar", if ((device.battery ?: 100) <= warning) Warn else Good)
      StatusCard("Energiesparmodus", if (device.saver) "Aktiv" else "Inaktiv", if (device.saver) Warn else Good)
      Text("FPS von Roblox", color = Color.White, fontWeight = FontWeight.Bold)
      Text("Android gibt einer normalen App keinen zuverlässigen FPS-Wert einer anderen App. Für Grafik und Bildrate nutze Roblox' eigene Spieloptionen, sofern verfügbar. Ein direkter FPS-Boost wird nicht versprochen.", color = Muted, fontSize = 13.sp)
     }
    }
    Page.History -> {
     Text("GRIND-VERLAUF", fontSize = 22.sp, color = Color.White, fontWeight = FontWeight.Bold)
     Metric("GESAMTE GRIND-ZEIT", formatTime(total), Modifier.fillMaxWidth())
     val sessions = remember(tick / 1000L, historyRefresh) { GrindStore.sessions(c) }
     if (sessions.isEmpty()) InfoCard("Noch keine beendeten Sitzungen gespeichert. Deine nächste beendete Sitzung erscheint hier.", Muted)
     sessions.forEachIndexed { index, s ->
      PanelCard(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(s.startedAt))) {
       Text("Dauer: ${formatTime(s.durationMs)}", color = Muted)
       TextButton(onClick = { GrindStore.deleteSession(c, index); historyRefresh++ }) { Text("Diese Sitzung löschen", color = Red) }
      }
     }
     if (sessions.isNotEmpty()) ActionButton("Gesamten Verlauf löschen", { GrindStore.clearHistory(c); historyRefresh++ })
    }
    Page.General -> {
     Text("ALLGEMEINE EINSTELLUNGEN", fontSize = 21.sp, color = Color.White, fontWeight = FontWeight.Bold)
     PanelCard("AKKUWARNUNG") {
      Text("Warnen ab $warning %", color = Color.White, fontWeight = FontWeight.Bold)
      Slider(value = warning.toFloat(), onValueChange = { warning = it.toInt(); GrindStore.setWarning(c, warning) }, valueRange = 5f..50f, steps = 8)
      Text("Die Warnung erscheint im Dashboard bei niedrigem Akkustand.", color = Muted, fontSize = 12.sp)
     }
     InfoCard("Das Display kann wärmer werden und mehr Akku verbrauchen. Keine Garantie für unbegrenzte Sitzungen. Keine simulierten Eingaben, kein Root und kein Zugriff auf Roblox' interne Grafik-Engine.", Muted)
    }
   }
  }
  Row(Modifier.fillMaxWidth().background(Panel).padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceAround) {
   Page.entries.forEach { item ->
    Column(Modifier.weight(1f).clickable { changePage(item) }.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
     Text(item.mark, color = if (item == page) Purple else Muted, fontSize = 18.sp)
     Text(item.label, color = if (item == page) Color.White else Muted, fontSize = 10.sp, maxLines = 1)
    }
   }
  }
 }
 if (overlayDialog) AlertDialog(onDismissRequest = dismissOverlay, containerColor = Panel,
  title = { Text("Schwebendes Menü einrichten") },
  text = { Text("Die Sitzung läuft, aber das Menü über Roblox braucht die Android-Berechtigung Über anderen Apps anzeigen. Aktiviere sie auf der nächsten Einstellungsseite.") },
  confirmButton = { TextButton(onClick = enableOverlay) { Text("JETZT EINRICHTEN") } },
  dismissButton = { TextButton(onClick = dismissOverlay) { Text("Später") } })
}

@Composable private fun StatusLine(label: String, value: String, color: Color) {
 Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, color = Muted, fontSize = 12.sp); Text(value, color = color, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
}
@Composable private fun Metric(title: String, value: String, modifier: Modifier = Modifier) {
 Card(modifier, colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
  Column(Modifier.padding(15.dp)) { Text(title, color = Muted, fontSize = 10.sp); Spacer(Modifier.height(5.dp)); Text(value, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
 }
}
@Composable private fun PanelCard(title: String, content: @Composable ColumnScope.() -> Unit) {
 Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
  Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold); content() }
 }
}
@Composable private fun StatusCard(title: String, value: String, color: Color) { PanelCard(title) { Text(value, color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium) } }
@Composable private fun ActionButton(label: String, click: () -> Unit) { Button(onClick = click, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF332844))) { Text(label) } }
@Composable private fun InfoCard(text: String, color: Color) { Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) { Text(text, color = color, fontSize = 13.sp, modifier = Modifier.padding(15.dp)) } }
private fun timeoutLabel(ms: Int): String {
 if (ms <= 0) return "Sofort"
 val minutes = ms / 60000
 return when {
  minutes >= 60 && minutes % 60 == 0 -> "${minutes / 60} Std."
  minutes >= 60 -> "${minutes / 60} Std. ${minutes % 60} Min."
  minutes > 0 -> "$minutes Min."
  else -> "${ms / 1000} Sek."
 }
}
