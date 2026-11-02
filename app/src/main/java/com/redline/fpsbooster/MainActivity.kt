package com.redline.fpsbooster

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.media.projection.MediaProjectionManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date

private val Bg = Color(0xFF0B0913)
private val CardBg = Color(0xFF191427)
private val Pink = Color(0xFFDF42F5)
private val Red = Color(0xFFFF405E)
private val Muted = Color(0xFFB8B1C9)

class MainActivity : ComponentActivity() {
    private var revision by mutableIntStateOf(0)
    private var selected by mutableStateOf("AFK Grind")
    private lateinit var screenCaptureLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        screenCaptureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                runCatching {
                    startForegroundService(ScreenCaptureService.createIntent(this, result.resultCode, data))
                }.onSuccess {
                    SettingsAutomationService.start(this)
                    Toast.makeText(this, "AI Live Screen wurde gestartet.", Toast.LENGTH_SHORT).show()
                    runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                }.onFailure { error ->
                    SettingsAutomationService.stop(this)
                    Toast.makeText(this, "Live Screen konnte nicht starten: ${error.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            } else {
                LiveScreenState.setStatus("Bildschirmfreigabe abgelehnt oder abgebrochen.")
                Toast.makeText(this, "Ohne deine Freigabe wird kein Bildschirm erfasst.", Toast.LENGTH_SHORT).show()
            }
        }
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Pink, secondary = Red, background = Bg, surface = CardBg)) { AppContent() } }
    }

    override fun onResume() {
        super.onResume()
        revision++
        if (SessionStore.isActive(this) && Settings.canDrawOverlays(this)) {
            runCatching { startForegroundService(AfkOverlayService.intent(this)) }
        }
    }

    private fun startAiControl() {
        if (!SettingsAutomationService.isEnabled(this)) {
            Toast.makeText(
                this,
                "Bitte aktiviere REDLINE zuerst unter Einstellungen > Bedienungshilfen. Danach erneut START AI CONTROL drücken.",
                Toast.LENGTH_LONG
            ).show()
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            return
        }
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // Android shows its official per-session screen-sharing consent dialog.
        screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun stopAiControl() {
        SettingsAutomationService.stop(this)
        stopService(ScreenCaptureService.stopIntent(this))
        LiveScreenState.setStatus("Vom Benutzer gestoppt.")
        Toast.makeText(this, "AI Control gestoppt.", Toast.LENGTH_SHORT).show()
    }

    private fun updateDisplayProtection() {
        val active = SessionStore.isRunning(this)
        if (active) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
            }
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }

    @Composable
    private fun AppContent() {
        var tick by remember { mutableIntStateOf(0) }
        var performance by remember { mutableStateOf(getSharedPreferences("redline_afk_store", MODE_PRIVATE).getBoolean("performance_mode", true)) }
        var warning by remember { mutableStateOf("") }
        val livePreview by LiveScreenState.preview.collectAsState()
        val liveStatus by LiveScreenState.status.collectAsState()
        val currentAction by LiveScreenState.currentAction.collectAsState()
        val currentSettings by LiveScreenState.recognizedSummary.collectAsState()
        val activePackage by LiveScreenState.activePackage.collectAsState()
        val deviceDetails = remember(tick) { DeviceDiagnostics.summary(this) }
        LaunchedEffect(Unit) {
            while (true) {
                tick++
                revision++
                updateDisplayProtection()
                kotlinx.coroutines.delay(1000)
            }
        }
        val battery = (getSystemService(BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        val overlayGranted = Settings.canDrawOverlays(this)
        val current = SessionStore.durationMs(this)
        val running = SessionStore.isRunning(this)
        val active = SessionStore.isActive(this)
        val records = remember(tick, revision) { SessionStore.records(this) }
        val total = remember(tick, revision) { SessionStore.totalMs(this) }
        BackHandler(selected != "AFK Grind") { selected = "AFK Grind" }

        Column(Modifier.fillMaxSize().background(Bg)) {
            Column(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color(0xFF32113F), Color(0xFF151025), Color(0xFF3A111C)))).padding(horizontal = 20.dp, vertical = 18.dp)) {
                Text("REDLINE", color = Pink, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                Text("FPS BOOSTER", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold)
                Text("AFK Grind • Dein Gaming-Begleiter", color = Muted, fontSize = 13.sp)
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("AFK Grind", "AFK Settings", "Performance", "Verlauf").forEach { label ->
                    TextButton(onClick = { selected = label }, modifier = Modifier.weight(1f)) {
                        Text(label, color = if (selected == label) Pink else Muted, fontSize = 10.sp, fontWeight = if (selected == label) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            when (selected) {
                "AFK Grind" -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Column(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF271437), Color(0xFF28121F))), RoundedCornerShape(22.dp)).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (running) "AFK GRIND: ON" else if (active) "AFK GRIND: PAUSIERT" else "AFK GRIND: OFF", color = if (running) Color(0xFF70F0B4) else Red, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
                            Spacer(Modifier.height(10.dp))
                            Text("GRIND-ZEIT", color = Muted, fontSize = 12.sp, letterSpacing = 2.sp)
                            Text(SessionStore.format(current), color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Bold)
                            Text("Gesamt: ${SessionStore.format(total)}", color = Muted, fontSize = 13.sp)
                            Spacer(Modifier.height(14.dp))
                            Button(onClick = {
                                warning = ""
                                if (!SessionStore.isActive(this@MainActivity)) SessionStore.start(this@MainActivity) else SessionStore.finish(this@MainActivity)
                                if (SessionStore.isActive(this@MainActivity)) {
                                    if (Settings.canDrawOverlays(this@MainActivity)) runCatching { startForegroundService(AfkOverlayService.intent(this@MainActivity)) }
                                    else {
                                        warning = "Für das schwebende Menü braucht AFK Grind die Overlay-Berechtigung. Die Sitzung läuft in der App weiter."
                                        runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                                    }
                                } else stopService(AfkOverlayService.intent(this@MainActivity))
                                revision++
                            }, modifier = Modifier.fillMaxWidth().height(54.dp), colors = ButtonDefaults.buttonColors(containerColor = if (active) Red else Pink)) {
                                Icon(if (active) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                                Spacer(Modifier.width(8.dp))
                                Text(if (active) "SITZUNG BEENDEN" else "AFK GRIND STARTEN", fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { SessionStore.pause(this@MainActivity); revision++ }, enabled = active && running, modifier = Modifier.weight(1f)) { Text("Pause") }
                                OutlinedButton(onClick = { SessionStore.resume(this@MainActivity); revision++ }, enabled = active && !running, modifier = Modifier.weight(1f)) { Text("Fortsetzen") }
                            }
                        }
                    }
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("AI LIVE SCREEN & CONTROL", color = Pink, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                                Text("Android zeigt vor jeder Sitzung die offizielle Bildschirmfreigabe. REDLINE zeigt eine Live-Vorschau lokal auf dem Gerät. Automatische Klicks sind auf eindeutig erkannte Android-Einstellungen begrenzt.", color = Muted, fontSize = 12.sp)
                                Button(onClick = { startAiControl() }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                                    Icon(Icons.Default.CenterFocusStrong, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("START AI CONTROL", fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(onClick = { stopAiControl(); revision++ }, modifier = Modifier.fillMaxWidth()) {
                                    Text("STOP AI CONTROL")
                                }
                                Text("AI STATUS: $liveStatus", color = Pink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text("CURRENT ACTION: $currentAction", color = Color.White, fontSize = 11.sp)
                                Text("CURRENT SETTINGS: $currentSettings", color = Muted, fontSize = 11.sp)
                                Text("ACTIVE APP: $activePackage", color = Muted, fontSize = 11.sp)
                                if (livePreview != null) {
                                    Image(
                                        bitmap = livePreview!!.asImageBitmap(),
                                        contentDescription = "Lokale Live-Bildschirmvorschau",
                                        modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)
                                    )
                                } else {
                                    Text("LIVE SCREEN PREVIEW: Noch keine Freigabe aktiv.", color = Muted, fontSize = 11.sp)
                                }
                                Text("Kein Upload • keine Speicherung • Stop jederzeit möglich • Einstellungen werden nicht blind verändert", color = Muted, fontSize = 10.sp)
                            }
                        }
                    }
                    item {
                        val automationStatus = getSharedPreferences("redline_settings_automation", MODE_PRIVATE)
                            .getString("automation_status", "Noch nicht gestartet") ?: "Noch nicht gestartet"
                        StatusCard("OPTIMIZATION REPORT", automationStatus, Pink, "Die Automatik berichtet nur nachweisbare Aktionen. Unterstützte Werte werden unten angezeigt; manuelle Android-Sperren bleiben unangetastet.")
                    }
                    item { StatusCard("DISPLAY-SCHUTZ", if (running) "Aktiv • Display wach" else "Inaktiv", if (running) Color(0xFF70F0B4) else Muted, "Androids Keep-Screen-On-Funktion wird genutzt.") }
                    item { StatusCard("AFK SETTINGS", if (overlayGranted) "Bereit" else "Overlay-Einrichtung erforderlich", if (overlayGranted) Color(0xFF70F0B4) else Color(0xFFFFC66D), "Akku: $battery% • Energiesparmodus: ${if (powerManager.isPowerSaveMode) "AN" else "AUS"}") }
                    item {
                        SectionTitle("DEVICE DETECTION", "Gerätedaten vom aktuellen Android-System")
                        deviceDetails.forEach { (label, value) ->
                            Text("$label: $value", color = Muted, fontSize = 12.sp)
                        }
                    }
                    item { StatusCard("RESTORE SETTINGS", "Keine Systemwerte automatisch geändert", Muted, "Die aktuelle Automatik startet nur die von Samsung bereitgestellte Gerätewartung. Geschützte Werte werden nicht verstellt; deshalb gibt es nichts automatisch zurückzusetzen.") }
                    item { StatusCard("PERFORMANCE MODE", if (performance) "ON" else "OFF", Pink, "Optimiert nur diese App; kein versprochener direkter Roblox-FPS-Boost.") }
                    if (warning.isNotBlank()) item { Text(warning, color = Color(0xFFFFC66D), fontSize = 13.sp) }
                    item { Text("Hinweis: Ein dauerhaft helles Display verbraucht mehr Akku und kann das Tablet erwärmen.", color = Muted, fontSize = 12.sp) }
                }
                "AFK Settings" -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { SectionTitle("AFK SETTINGS", "Prüfen und einrichten") }
                    item { StatusCard("Overlay-Berechtigung", if (overlayGranted) "AKTIV" else "NICHT AKTIV", if (overlayGranted) Color(0xFF70F0B4) else Color(0xFFFFC66D), "Damit das kleine Menü über Roblox angezeigt werden kann.") }
                    item { Button(onClick = { runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } }, modifier = Modifier.fillMaxWidth()) { Text("JETZT EINRICHTEN") } }
                    item { StatusCard("Display-Zeitüberschreitung", timeoutLabel(), Muted, "Die App ändert diesen Systemwert nicht dauerhaft. FLAG_KEEP_SCREEN_ON wirkt während einer aktiven Sitzung.") }
                    item { OutlinedButton(onClick = { runCatching { startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS)) } }, modifier = Modifier.fillMaxWidth()) { Text("Display-Einstellungen öffnen") } }
                    item { StatusCard("Akkuoptimierung", "Status geprüft, nicht automatisch deaktiviert", Muted, "Systemeinstellungen können herstellerabhängig sein; unnötige Einschränkungen bitte nur bei Bedarf anpassen.") }
                    item { OutlinedButton(onClick = { runCatching { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }, modifier = Modifier.fillMaxWidth()) { Text("Akku-Einstellungen öffnen") } }
                    item { StatusCard("Hintergrundbetrieb", if (active && overlayGranted) "Overlay-Dienst gestartet" else "Noch nicht aktiv", Muted, "Android oder der Hersteller kann Hintergrundbetrieb trotzdem einschränken.") }
                    item { Button(onClick = {
                        if (!overlayGranted) runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
                        else if (!SessionStore.isActive(this@MainActivity)) { SessionStore.start(this@MainActivity); runCatching { startForegroundService(AfkOverlayService.intent(this@MainActivity)) } }
                        else runCatching { startForegroundService(AfkOverlayService.intent(this@MainActivity)) }
                        revision++
                    }, modifier = Modifier.fillMaxWidth()) { Text("AFK SETTINGS OPTIMIEREN") } }
                }
                "Performance" -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { SectionTitle("PERFORMANCE MODE", "Die Booster-App schlank halten") }
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = CardBg)) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Performance Mode", color = Color.White, fontWeight = FontWeight.Bold)
                                    Text("Weniger unnötige Aktualisierungen in der App.", color = Muted, fontSize = 12.sp)
                                }
                                Switch(checked = performance, onCheckedChange = { performance = it; getSharedPreferences("redline_afk_store", MODE_PRIVATE).edit().putBoolean("performance_mode", it).apply() })
                            }
                        }
                    }
                    item { StatusCard("FPS-ANZEIGE", "Nicht verfügbar", Muted, "Eine normale Android-App kann die FPS einer anderen App wie Roblox nicht zuverlässig direkt auslesen.") }
                    item { StatusCard("Roblox-Tipp", "Grafik manuell einstellen", Pink, "In Roblox die Grafik auf manuell stellen und die Qualitätsstufe bei Bedarf senken. Das kann helfen, wenn das Gerät ruckelt.") }
                    item { StatusCard("Ressourcen", "Timer aktualisiert einmal pro Sekunde", Muted, "Kein Root-Zugriff, keine manipulierten Eingaben und keine Änderung an Roblox-Dateien.") }
                }
                else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item { SectionTitle("GRIND-VERLAUF", "Lokal auf diesem Gerät gespeichert") }
                    if (records.isEmpty()) item { Text("Noch keine abgeschlossenen Sitzungen.", color = Muted) }
                    items(records) { record ->
                        Card(colors = CardDefaults.cardColors(containerColor = CardBg)) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(record.startWallMs)), color = Color.White)
                                    Text("Dauer: ${SessionStore.format(record.durationMs)}", color = Muted, fontSize = 13.sp)
                                }
                                IconButton(onClick = { SessionStore.deleteRecord(this@MainActivity, record); revision++ }) { Icon(Icons.Default.Delete, "Löschen", tint = Red) }
                            }
                        }
                    }
                    item { Text("Gesamte Grind-Zeit: ${SessionStore.format(total)}", color = Pink, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }

    @Composable
    private fun StatusCard(title: String, value: String, accent: Color, detail: String) {
        Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(title, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(4.dp))
                Text(value, color = accent, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text(detail, color = Muted, fontSize = 12.sp)
            }
        }
    }

    @Composable
    private fun SectionTitle(title: String, subtitle: String) {
        Column(Modifier.padding(bottom = 4.dp)) {
            Text(title, color = Pink, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Text(subtitle, color = Muted, fontSize = 13.sp)
        }
    }

    private fun timeoutLabel(): String {
        val timeout = runCatching { Settings.System.getInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT) }.getOrNull()
            ?: return "Nicht lesbar"
        return if (timeout < 60_000) "${timeout / 1000} Sekunden" else "${timeout / 60_000} Minuten"
    }
}
