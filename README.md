# REDLINE FPS BOOSTER

Android-App mit AFK Grind und einem lokal arbeitenden Bildschirm-Assistenten für Android-Einstellungen.

## AFK Grind
- Sitzungs-Timer mit Start, Pause, Fortsetzen und Beenden
- Lokaler Sitzungsverlauf und Gesamtzeit
- Optionales, verschiebbares Overlay (Android-Overlay-Berechtigung erforderlich)
- Akkuanzeige, Energiespar-Hinweise und Performance-Modus für die App selbst

## Lokaler Bildschirm-Assistent
- Verwendet Androids Bedienungshilfe. Der Benutzer muss sie selbst in Android aktivieren.
- Erst nach dem Start in REDLINE wird der Bildschirm erfasst.
- Die Bildschirm-Erfassung ist auf die Android-Einstellungs-App `com.android.settings` beschränkt.
- Auf Android 11 oder neuer erstellt REDLINE in kurzen Abständen Bildschirm-Schnappschüsse, erkennt sichtbaren Text direkt auf dem Gerät und verwirft die Bilder danach.
- Es werden keine Screenshots oder erkannten Bildschirmtexte gespeichert oder an einen Server gesendet.
- Die automatische Bedienung klickt nur klar bezeichnete Bedienelemente der Android-Einstellungen, die für den gestarteten Ablauf vorgesehen sind.
- Beim Verlassen der Android-Einstellungen oder beim Drücken von „Automatik stoppen“ wird die Sitzung beendet.
- Das ist lokale Texterkennung mit festen Regeln, kein verbundenes generatives KI-Modell und keine freie Steuerung aller Apps.

## Grenzen
- Android kann geschützte Bildschirme oder Inhalte von der Bildschirm-Erfassung ausschließen.
- Die Automatik verändert nur eindeutig erkannte, unterstützte Einstellungen.
- Kein direkter FPS-Zuwachs oder eine Änderung der Roblox-Grafik-Engine wird versprochen.

## APK bauen
GitHub Actions baut den aktuellen Quellcode. Unter **Actions → Android APK** kann der Workflow manuell gestartet werden. Nach einem erfolgreichen Lauf die APK im Bereich **Artifacts** herunterladen. Die Debug-APK ist nicht für eine öffentliche Play-Store-Veröffentlichung signiert.

## Lokal bauen
Voraussetzungen: JDK 17, Android SDK 35 und Gradle.

```bash
gradle :app:assembleDebug
```

APK-Ausgabe: `app/build/outputs/apk/debug/app-debug.apk`.
