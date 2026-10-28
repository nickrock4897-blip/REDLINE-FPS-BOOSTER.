# REDLINE FPS BOOSTER

Android-App mit integriertem **AFK Grind**-Bereich für längere Roblox-Sitzungen.

## AFK Grind

- Sitzungs-Timer mit Start, Pause, Fortsetzen und Beenden
- Lokaler Sitzungsverlauf und Gesamtzeit
- Display wach halten, solange die App oder das freigegebene Overlay aktiv ist
- Kleines, verschiebbares Overlay über Roblox (Android-Overlay-Berechtigung erforderlich)
- Akkuanzeige und Hinweise zu Energiesparmodus und System-Einstellungen
- Performance-Modus für die Booster-App selbst
- Dark Gaming UI mit Rot-/Violett-Akzenten

Die App simuliert keine Berührungen und ändert Roblox nicht. Android kann eine manuelle Bildschirmsperre oder herstellerspezifische Hintergrundbeschränkungen nicht einfach umgehen. Ein aktives Display verbraucht mehr Strom und kann das Gerät erwärmen.

## APK bauen

GitHub Actions baut bei jedem Push auf `main`, bei Pull Requests sowie manuell über **Actions → Android APK → Run workflow**.

Nach einem erfolgreichen Lauf:
1. Den Workflow-Lauf öffnen.
2. Unten zu **Artifacts** gehen.
3. `redline-fps-booster-debug-apk` herunterladen.
4. ZIP-Datei entpacken und `app-debug.apk` auf dem Android-Gerät installieren. Android kann fragen, ob Installationen aus dieser Quelle erlaubt werden sollen.

Die Debug-APK ist nicht für eine öffentliche Play-Store-Veröffentlichung signiert.

## Lokal bauen

Voraussetzungen: JDK 17, Android SDK und Gradle 8.10.2.

```bash
gradle assembleDebug
```

APK-Ausgabe: `app/build/outputs/apk/debug/app-debug.apk`.
