package com.redline.fpsbooster

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

private data class ChatMessage(val role: String, val text: String)

@Composable
fun AiChatScreen(context: Context) {
    val prefs = remember { context.getSharedPreferences("redline_ai", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val scroll = rememberLazyListState()
    var endpoint by remember { mutableStateOf(prefs.getString("endpoint", "") ?: "") }
    var token by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Server nicht eingerichtet") }
    var busy by remember { mutableStateOf(false) }
    var setup by remember { mutableStateOf(endpoint.isBlank()) }
    var chat by remember { mutableStateOf(listOf(ChatMessage("assistant", "Ich bin REDLINE AI. Richte deinen privaten Server ein, um den KI-Chat zu starten."))) }

    LaunchedEffect(chat.size) { if (chat.isNotEmpty()) scroll.animateScrollToItem(chat.lastIndex) }

    Column(Modifier.fillMaxSize().background(Color(0xFF0B0913)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF191427))) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("REDLINE AI", color = Color(0xFFDF42F5), fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.weight(1f))
                    Text(status, color = Color(0xFFB8B1C9), fontSize = 10.sp)
                }
                Text("Freier KI-Chat. Nur deine Chat-Nachrichten werden übertragen. Bildschirmbilder, OCR und Gerätedaten bleiben lokal.", color = Color(0xFFB8B1C9), fontSize = 11.sp)
                TextButton(onClick = { setup = !setup }) { Text(if (setup) "EINRICHTUNG SCHLIESSEN" else "SERVER EINRICHTEN", color = Color(0xFFDF42F5)) }
                if (setup) {
                    OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth(), label = { Text("Private HTTPS-Serveradresse") }, placeholder = { Text("https://dein-worker.workers.dev") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                    OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("REDLINE Server-Token") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val valid = runCatching { URI(endpoint.trim()).let { it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null } }.getOrDefault(false)
                            status = if (!valid) "Bitte gültige HTTPS-Adresse eintragen." else if (token.length < 24) "Token muss mindestens 24 Zeichen haben." else {
                                prefs.edit().putString("endpoint", endpoint.trim().trimEnd('/')).apply()
                                "Adresse gespeichert – Verbindung testen."
                            }
                        }, modifier = Modifier.weight(1f)) { Text("SPEICHERN") }
                        OutlinedButton(onClick = {
                            scope.launch {
                                busy = true
                                status = "Prüfe Verbindung ..."
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { request(endpoint.trim().trimEnd('/') + "/health", token, "GET", null) }
                                }
                                status = result.fold(
                                    { if (it.optBoolean("ok")) "Verbunden" else it.optString("error", "Server nicht bereit") },
                                    { it.message ?: "Verbindung fehlgeschlagen." }
                                )
                                busy = false
                            }
                        }, enabled = !busy && token.length >= 24 && endpoint.isNotBlank(), modifier = Modifier.weight(1f)) { Text("TESTEN") }
                    }
                    Text("Keinen OpenAI-API-Key hier eingeben. Das ist nur das separate REDLINE-Token.", color = Color(0xFFFFC66D), fontSize = 10.sp)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("CHAT", color = Color(0xFFDF42F5), fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { chat = listOf(ChatMessage("assistant", "Neuer Chat gestartet.")) }, enabled = !busy) { Text("NEUER CHAT", color = Color(0xFFB8B1C9)) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = scroll, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(chat) { msg ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (msg.role == "user") Arrangement.End else Arrangement.Start) {
                    Surface(color = if (msg.role == "user") Color(0xFF342044) else Color(0xFF191427), shape = RoundedCornerShape(14.dp), modifier = Modifier.widthIn(max = 330.dp)) {
                        Column(Modifier.padding(11.dp)) {
                            Text(if (msg.role == "user") "DU" else "REDLINE AI", color = Color(0xFFDF42F5), fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Text(msg.text, color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
            }
            if (busy) item { Text("REDLINE AI denkt nach ...", color = Color(0xFFB8B1C9)) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            OutlinedTextField(draft, { draft = it }, Modifier.weight(1f), placeholder = { Text("Nachricht schreiben ...") }, maxLines = 4)
            Button(onClick = {
                val q = draft.trim()
                if (q.isNotEmpty() && !busy) {
                    if (token.length < 24 || endpoint.isBlank()) { setup = true; status = "Zuerst Server verbinden." }
                    else {
                        val updated = (chat + ChatMessage("user", q)).takeLast(18)
                        chat = updated
                        draft = ""
                        busy = true
                        status = "KI antwortet ..."
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runCatching {
                                    val arr = JSONArray()
                                    updated.forEach { arr.put(JSONObject().put("role", it.role).put("content", it.text)) }
                                    request(endpoint.trim().trimEnd('/') + "/chat", token, "POST", JSONObject().put("messages", arr)).getString("reply")
                                }
                            }
                            chat = updated + ChatMessage("assistant", result.getOrElse { "Fehler: ${it.message ?: "Server nicht erreichbar"}" })
                            status = if (result.isSuccess) "Antwort erhalten" else "Verbindungsfehler"
                            busy = false
                        }
                    }
                }
            }, enabled = draft.isNotBlank() && !busy, modifier = Modifier.height(54.dp)) { Text("SENDEN", fontSize = 10.sp) }
        }
    }
}

private fun request(address: String, token: String, method: String, body: JSONObject?): JSONObject {
    val c = URL(address).openConnection() as HttpURLConnection
    try {
        c.requestMethod = method
        c.connectTimeout = 12000
        c.readTimeout = 55000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Accept", "application/json")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: "{}"
        val result = JSONObject(text)
        if (code !in 200..299) throw IllegalStateException(result.optString("error", "HTTP $code"))
        return result
    } finally { c.disconnect() }
}
