package com.example.badmintontracker.phone

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

data class SessionSummary(
    val timestamp: Long,
    val smashCount: Int,
    val clearCount: Int,
    val dropCount: Int,
    val serveCount: Int,
    val bestSpeedKph: Double,
    val rallyCount: Int,
    val longestRally: Int,
    val avgHeartRate: Double,
    val calories: Double
)

/**
 * Very simple local history store using SharedPreferences (a JSON array
 * under one key). Fine for a personal project / a few dozen sessions —
 * swap for a Room database if you want proper querying later.
 */
object SessionStore {
    private const val PREFS = "badminton_sessions"
    private const val KEY = "sessions_json"
    private const val MAX_SESSIONS = 100

    fun save(context: Context, session: SessionSummary) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = loadRaw(context)
        existing.put(sessionToJson(session))
        while (existing.length() > MAX_SESSIONS) existing.remove(0)
        prefs.edit().putString(KEY, existing.toString()).apply()
    }

    fun loadAll(context: Context): List<SessionSummary> {
        val array = loadRaw(context)
        // Skip individual unparseable entries rather than letting one bad
        // object crash the whole load (which would otherwise crash the app
        // every time it's opened from now on, with no way to recover).
        return (0 until array.length()).mapNotNull { i ->
            runCatching {
                val obj = array.getJSONObject(i)
                SessionSummary(
                    timestamp = obj.optLong("timestamp", 0L),
                    smashCount = obj.optInt("smashCount", 0),
                    clearCount = obj.optInt("clearCount", 0),
                    dropCount = obj.optInt("dropCount", 0),
                    // optInt so older stored sessions (saved before rally
                    // tracking / serve detection existed) still load fine,
                    // just showing 0.
                    serveCount = obj.optInt("serveCount", 0),
                    bestSpeedKph = obj.optDouble("bestSpeedKph", 0.0),
                    rallyCount = obj.optInt("rallyCount", 0),
                    longestRally = obj.optInt("longestRally", 0),
                    avgHeartRate = obj.optDouble("avgHeartRate", 0.0),
                    calories = obj.optDouble("calories", 0.0)
                )
            }.onFailure { e ->
                Log.w("SessionStore", "Skipping unreadable session at index $i", e)
            }.getOrNull()
        }.sortedByDescending { it.timestamp }
    }

    private fun loadRaw(context: Context): JSONArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null) ?: return JSONArray()
        return try {
            JSONArray(raw)
        } catch (e: Exception) {
            Log.w("SessionStore", "Stored session history is corrupt, starting fresh", e)
            JSONArray()
        }
    }

    private fun sessionToJson(session: SessionSummary): JSONObject = JSONObject().apply {
        put("timestamp", session.timestamp)
        put("smashCount", session.smashCount)
        put("clearCount", session.clearCount)
        put("dropCount", session.dropCount)
        put("serveCount", session.serveCount)
        put("bestSpeedKph", session.bestSpeedKph)
        put("rallyCount", session.rallyCount)
        put("longestRally", session.longestRally)
        put("avgHeartRate", session.avgHeartRate)
        put("calories", session.calories)
    }
}
