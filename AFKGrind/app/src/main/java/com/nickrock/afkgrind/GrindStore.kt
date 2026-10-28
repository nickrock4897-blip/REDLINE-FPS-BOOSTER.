package com.nickrock.afkgrind

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class GrindSession(val startedAt: Long, val endedAt: Long, val durationMs: Long)

object GrindStore {
 private const val PREF = "afk_grind"
 private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
 fun running(c: Context) = p(c).getBoolean("running", false)
 fun paused(c: Context) = p(c).getBoolean("paused", false)
 fun active(c: Context) = running(c) || paused(c)
 fun performance(c: Context) = p(c).getBoolean("performance", true)
 fun setPerformance(c: Context, v: Boolean) { p(c).edit().putBoolean("performance", v).apply() }
 fun warning(c: Context) = p(c).getInt("warning", 20).coerceIn(5, 50)
 fun setWarning(c: Context, v: Int) { p(c).edit().putInt("warning", v.coerceIn(5, 50)).apply() }
 fun total(c: Context) = p(c).getLong("total", 0L).coerceAtLeast(0L)
 fun elapsed(c: Context, now: Long = System.currentTimeMillis()): Long {
  val prefs = p(c); val base = prefs.getLong("base", 0L)
  return if (prefs.getBoolean("running", false)) (base + (now - prefs.getLong("segmentStart", now)).coerceAtLeast(0L)).coerceAtLeast(0L) else base.coerceAtLeast(0L)
 }
 fun startOrResume(c: Context) {
  val prefs = p(c); if (prefs.getBoolean("running", false)) return
  val now = System.currentTimeMillis(); val wasActive = prefs.getBoolean("paused", false); val edit = prefs.edit()
  if (!wasActive) edit.putLong("sessionStart", now).putLong("base", 0L)
  edit.putLong("segmentStart", now).putBoolean("running", true).putBoolean("paused", false).apply()
 }
 fun pause(c: Context) {
  if (!running(c)) return
  p(c).edit().putLong("base", elapsed(c)).putBoolean("running", false).putBoolean("paused", true).apply()
 }
 fun finish(c: Context): GrindSession? {
  if (!active(c)) return null
  val prefs = p(c); val now = System.currentTimeMillis()
  val session = GrindSession(prefs.getLong("sessionStart", now), now, elapsed(c, now))
  val list = sessions(c).toMutableList(); list.add(0, session); val json = JSONArray()
  list.take(250).forEach { s -> json.put(JSONObject().put("start", s.startedAt).put("end", s.endedAt).put("duration", s.durationMs)) }
  prefs.edit().putString("history", json.toString()).putLong("total", total(c) + session.durationMs)
   .putLong("base", 0L).putLong("segmentStart", 0L).putLong("sessionStart", 0L).putBoolean("running", false).putBoolean("paused", false).apply()
  return session
 }
 fun sessions(c: Context): List<GrindSession> = try {
  val a = JSONArray(p(c).getString("history", "[]") ?: "[]")
  (0 until a.length()).mapNotNull { i -> val o = a.optJSONObject(i) ?: return@mapNotNull null
   GrindSession(o.optLong("start"), o.optLong("end"), o.optLong("duration").coerceAtLeast(0)) }
 } catch (_: Exception) { emptyList() }
 fun deleteSession(c: Context, index: Int) {
  val list = sessions(c).toMutableList(); if (index !in list.indices) return; list.removeAt(index)
  val arr = JSONArray(); list.forEach { arr.put(JSONObject().put("start", it.startedAt).put("end", it.endedAt).put("duration", it.durationMs)) }
  p(c).edit().putString("history", arr.toString()).apply()
 }
 fun clearHistory(c: Context) { p(c).edit().putString("history", "[]").apply() }
}
