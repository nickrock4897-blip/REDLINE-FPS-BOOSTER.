const headers = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, Authorization",
  "Cache-Control": "no-store"
};

function reply(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { ...headers, "Content-Type": "application/json; charset=utf-8" }
  });
}

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers });
    const url = new URL(request.url);
    const expected = env.APP_ACCESS_TOKEN || "";
    const actual = request.headers.get("Authorization") || "";
    if (!expected || actual !== "Bearer " + expected) return reply({ error: "Nicht autorisiert." }, 401);

    if (request.method === "GET" && url.pathname.endsWith("/health")) {
      return reply({ ok: true, service: "REDLINE AI Gateway" });
    }
    if (request.method !== "POST" || !url.pathname.endsWith("/chat")) return reply({ error: "Endpunkt nicht gefunden." }, 404);
    if (!env.OPENAI_API_KEY) return reply({ error: "KI-Server ist noch nicht eingerichtet." }, 503);

    let data;
    try { data = await request.json(); } catch { return reply({ error: "Ungültige Anfrage." }, 400); }
    if (!Array.isArray(data.messages) || data.messages.length < 1 || data.messages.length > 18) {
      return reply({ error: "Der Chat-Verlauf ist ungültig oder zu lang." }, 400);
    }

    let chars = 0;
    const input = [];
    for (const item of data.messages) {
      if (!item || !["user", "assistant"].includes(item.role) || typeof item.content !== "string") {
        return reply({ error: "Ungültiges Nachrichtenformat." }, 400);
      }
      const content = item.content.trim();
      chars += content.length;
      if (!content || content.length > 4000 || chars > 16000) return reply({ error: "Nachricht oder Chat-Verlauf ist zu lang." }, 413);
      input.push({ role: item.role, content });
    }

    let upstream;
    try {
      upstream = await fetch("https://api.openai.com/v1/responses", {
        method: "POST",
        headers: {
          "Authorization": "Bearer " + env.OPENAI_API_KEY,
          "Content-Type": "application/json"
        },
        body: JSON.stringify({
          model: env.OPENAI_MODEL || "gpt-6.1-sol",
          instructions: [
            "Du bist REDLINE AI, ein hilfreicher, klarer und eigenständig denkender Chat-Assistent.",
            "Verstehe die Absicht und gib direkte, sorgfältig begründete Antworten.",
            "Wenn Optionen verglichen werden, entscheide dich für die beste und erkläre kurz warum.",
            "Erfinde keine Tatsachen oder ausgeführten Aktionen. Sage offen, wenn dir Informationen fehlen.",
            "Du kannst das Android-Gerät, den Bildschirm, Roblox oder andere Apps nicht sehen oder bedienen.",
            "Fordere niemals Passwörter, Zugangsschlüssel oder andere Geheimnisse an.",
            "Antworte standardmäßig auf Deutsch."
          ].join("\n"),
          input,
          reasoning: { effort: "medium" },
          max_output_tokens: 900,
          store: false
        }),
        signal: AbortSignal.timeout(45000)
      });
    } catch {
      return reply({ error: "Der KI-Dienst ist momentan nicht erreichbar." }, 502);
    }

    if (!upstream.ok) {
      if (upstream.status === 429) return reply({ error: "Das API-Nutzungslimit wurde erreicht." }, 429);
      if (upstream.status === 401 || upstream.status === 403) return reply({ error: "Der API-Zugang des Servers ist nicht gültig." }, 502);
      return reply({ error: "Der KI-Anbieter hat die Anfrage abgelehnt.", providerStatus: upstream.status }, 502);
    }

    let result;
    try { result = await upstream.json(); } catch { return reply({ error: "KI-Antwort konnte nicht gelesen werden." }, 502); }
    const output = [];
    for (const item of result.output || []) {
      if (item.type === "message") for (const part of item.content || []) {
        if (part.type === "output_text" && typeof part.text === "string") output.push(part.text);
      }
    }
    const text = output.join("\n").trim();
    if (!text) return reply({ error: "Die KI hat keine Textantwort geliefert." }, 502);
    return reply({ reply: text, model: env.OPENAI_MODEL || "gpt-6.1-sol" });
  }
};
