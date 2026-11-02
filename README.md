# REDLINE FPS BOOSTER

Android-App mit AFK Grind, gerätebezogener Diagnose und einem vom Benutzer gestarteten Live-Screen-Assistenten.

## Live Screen & Control
- **START AI CONTROL** ruft den offiziellen Android-Bildschirmfreigabe-Dialog auf. Die Freigabe muss für jede Sitzung vom Benutzer bestätigt werden.
- Ein sichtbarer MediaProjection-Foreground-Service erhält fortlaufend Bildschirmbilder. Es werden keine Videos oder Screenshots auf Datei gespeichert oder an einen Server gesendet.
- Die Live-Vorschau bleibt im Arbeitsspeicher. Die OCR-Texterkennung läuft lokal auf dem Tablet.
- OCR wird nur ausgeführt, wenn Android-Einstellungen oder Samsung Device Care aktiv sind.
- Die Bedienungshilfe empfängt App-Wechsel-Ereignisse, liest aber Bedienungselemente und führt Klicks nur in `com.android.settings` und `com.samsung.android.lool` aus.
- Automatisch unterstützt ist derzeit der Samsung-Gerätewartungsablauf: Gerätewartung finden und einen eindeutig beschrifteten „Jetzt optimieren“-Knopf auswählen.
- **STOP AI CONTROL** und die Stop-Aktion in der laufenden Benachrichtigung beenden die Bildschirmfreigabe und den Assistenten.

## Gerätdiagnose
REDLINE zeigt Hersteller/Modell, Android-Version, One-UI-Angabe (wenn das Gerät sie bereitstellt), aktive und unterstützte Display-Modi, verfügbaren/gesamten RAM und freien/gesamten internen Speicher. Werte werden nur aus Android-Systeminformationen gelesen.

## Grenzen und Sicherheit
- Das ist eine lokale OCR- und Regelsteuerung, kein angebundenes generatives KI-Modell. Die App sendet keine Bildschirmbilder an ChatGPT oder einen anderen KI-Dienst.
- Die Anzeigevorschau kann den ganzen Bildschirm enthalten, aber Bilddaten bleiben nur im Arbeitsspeicher und sind nicht für Fernzugriff verfügbar.
- Automatische Klicks sind auf Android-Einstellungen und Samsung Device Care begrenzt. Andere Apps – einschließlich Roblox – werden nicht automatisch bedient; persönliche Inhalte, Chats und Eingabefelder werden nicht angetippt.
- Roblox-Grafikoptionen müssen in Roblox selbst vom Benutzer geprüft und angepasst werden. REDLINE ändert keine Roblox-Dateien.
- Geschützte Fenster können von Android geschwärzt oder von der Erfassung ausgeschlossen werden.
- Animationsskalen, Energiesparschutz, thermischer Schutz, Sicherheitsoptionen und gesperrte Systemwerte werden nicht automatisch geändert. Es gibt daher keine automatische Rücksetzung für solche Werte.
- Das System kann nach einem bestätigten Device-Care-Auftrag Hintergrundaufgaben beenden; dieser Vorgang ist nicht rückgängig zu machen.
- Ein direkter FPS-Zuwachs wird nicht versprochen. Unterstützte Display-Modi und Systemressourcen hängen vom tatsächlichen Modell ab.

## APK bauen
GitHub Actions baut den aktuellen Quellcode. Unter **Actions → Android APK** kann der Workflow manuell gestartet werden. Nach einem erfolgreichen Lauf die APK im Bereich **Artifacts** herunterladen. Die Debug-APK ist nicht für eine öffentliche Play-Store-Veröffentlichung signiert.

## Lokal bauen
Voraussetzungen: JDK 17, Android SDK 35 und Gradle.

```bash
gradle :app:assembleDebug
```

APK-Ausgabe: `app/build/outputs/apk/debug/app-debug.apk`.
