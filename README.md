# REDLINE FPS BOOSTER

Android-App mit integriertem **AFK Grind**-Bereich und einer begrenzten Automatik für Anzeige-Einstellungen.

## AFK Grind
- Sitzungs-Timer mit Start, Pause, Fortsetzen und Beenden
- Lokaler Sitzungsverlauf und Gesamtzeit
- Display wach halten, solange die App oder das freigegebene Overlay aktiv ist
- Kleines, verschiebbares Overlay über anderen Apps (Android-Overlay-Berechtigung erforderlich)
- Akkuanzeige und Hinweise zu Energiesparmodus und System-Einstellungen
- Performance-Modus für die Booster-App selbst

## Automatische Anzeige-Einstellung
- Nutzt Androids Bedienungshilfe, nicht Screen Recording oder MediaProjection.
- Funktioniert ausschließlich in der Android-Einstellungs-App.
- Sucht nach einer Bildwiederholraten-Einstellung und kann eine eindeutig bezeichnete höhere Option wie „Hoch“, „Adaptiv“ oder 90/120/144 Hz auswählen.
- Findet REDLINE keine passende Option, wird abgebrochen und nichts geändert.
- Die Bedienungshilfe muss vom Benutzer selbst in Android aktiviert werden.
- Die Automatik kann jederzeit in REDLINE gestoppt oder in Android unter Bedienungshilfen deaktiviert werden.

Die Automatik verspricht keinen direkten FPS-Boost. Die Bildwiederholrate wird nur erhöht, wenn das Gerät eine entsprechende Einstellung anbietet. Roblox' eigene Grafikqualität muss innerhalb von Roblox eingestellt werden.

## APK bauen
GitHub Actions baut den aktuellen Quellcode aus diesem Repository. Unter **Actions → Android APK** kann der Workflow manuell gestartet werden. Nach einem erfolgreichen Lauf die APK im Bereich **Artifacts** herunterladen und auf Android installieren. Die Debug-APK ist nicht für eine öffentliche Play-Store-Veröffentlichung signiert.

## Lokal bauen
Voraussetzungen: JDK 17, Android SDK 35 und Gradle.

```bash
gradle :app:assembleDebug
```

APK-Ausgabe: `app/build/outputs/apk/debug/app-debug.apk`.
