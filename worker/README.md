# REDLINE AI Gateway – Einrichtung

Der Worker verbindet den REDLINE-KI-Chat sicher mit der OpenAI Responses API. Der echte Anbieter-Schlüssel bleibt ausschließlich als Server-Secret gespeichert und gehört niemals in die Android-App oder ins GitHub-Repository.

## Was vor der Nutzung nötig ist
Ein Elternteil bzw. Erziehungsberechtigter muss die Server- und API-Einrichtung übernehmen und vorab ein Ausgabenlimit festlegen. API-Nutzung kann Geld kosten. Ohne Einrichtung bleibt die Chat-Oberfläche verfügbar, die KI antwortet aber noch nicht.

## Cloudflare Worker
1. Einen Cloudflare Worker mit dem Namen `redline-ai-gateway` anlegen.
2. Den Inhalt von `worker/src/index.js` als Worker-Code einsetzen und bereitstellen.
3. In den Worker-Einstellungen unter „Variables and Secrets“ zwei Secrets anlegen:
   - `OPENAI_API_KEY`: privater OpenAI-API-Schlüssel.
   - `APP_ACCESS_TOKEN`: eigenes langes zufälliges Token, mindestens 24 Zeichen.
4. Als Variable `OPENAI_MODEL` den Wert `gpt-6.1-sol` setzen (Standardmodell).
5. Worker veröffentlichen und die HTTPS-Adresse kopieren.

## REDLINE-App
Im Tab „KI Chat“ die HTTPS-Adresse des Workers und das separate `APP_ACCESS_TOKEN` eintragen, speichern und „TESTEN“ drücken. Der API-Schlüssel von OpenAI wird nicht in der App eingegeben. Das App-Token wird nur im Arbeitsspeicher gehalten und nach dem Schließen der App erneut benötigt.

## Datenschutz und Grenzen
- Nur Textnachrichten, die im KI-Chat geschrieben werden, werden zum Worker übertragen.
- MediaProjection-Bilddaten, OCR-Ergebnisse, Diagnosedaten und Roblox-Bildschirminhalte werden nicht an die KI gesendet.
- Der Worker sendet `store: false` an die Responses API und speichert keine Chat-Historie in einer Datenbank.
- Der Worker begrenzt einen Verlauf auf 18 Nachrichten, maximal 4.000 Zeichen pro Nachricht und 16.000 Zeichen insgesamt.
- Das App-Token ist ein Zugangsschutz, kein Ersatz für ein API-Ausgabenlimit. Bei Verdacht auf Offenlegung muss es ersetzt werden.
- REDLINE AI kann keine Android-Einstellungen ändern und keine anderen Apps bedienen. Bildschirmsteuerung bleibt die getrennte, begrenzte lokale Funktion.
