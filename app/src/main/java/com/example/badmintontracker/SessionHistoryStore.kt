package com.example.badmintontracker

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

data class SessionRecord(
    val timestamp: Long,
    val smashCount: Int,
    val clearCount: Int,
    val dropCount: Int,
    val serveCount: Int,
    val bestSpeedKph: Float,
    val rallyCount: Int,
    val longestRally: Int,
    val avgHeartRate: Double,
    val calories: Double,
    // Average heart-rate drop (bpm) between a rally ending and the next
    // serve, across every rest window in the session — see
    // HeartRateRecovery.kt. Defaults to 0.0 so this stays source- and
    // JSON-compatible with sessions saved before this field existed.
    val avgRecoveryBpm: Double = 0.0
)

/**
 * Local, on-watch session history. Before this, a session's counters only
 * lived in memory (`MainActivity`'s Compose state) and were gone the moment
 * the activity died — and the only durable copy was whatever summary made
 * it across to the phone app, which doesn't help if the phone was never
 * paired/open.
 *
 * Deliberately reuses the phone app's approach (`SessionStore.kt`):
 * SharedPreferences holding one JSON array, not Room — this app stores at
 * most a few dozen sessions for personal use, so a real database is more
 * dependency/boilerplate than the problem needs. `SessionRecord` mirrors
 * the phone's `SessionSummary` shape on purpose, so migrating either side
 * to Room later (see README.md known limitations) is a straight swap.
 */
object SessionHistoryStore {
    private const val PREFS = "badminton_watch_sessions"
    private const val KEY = "sessions_json"
    private const val MAX_SESSIONS = 50 // watch storage is tighter than phone

    fun save(context: Context, session: SessionRecord) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = loadRaw(context)
        existing.put(sessionToJson(session))
        while (existing.length() > MAX_SESSIONS) existing.remove(0)
        prefs.edit().putString(KEY, existing.toString()).apply()
    }

    fun loadAll(context: Context): List<SessionRecord> {
        val array = loadRaw(context)
        // One malformed entry shouldn't wipe out the rest of the history —
        // parse each session defensively and just skip the ones that don't
        // parse, instead of letting a single bad object take down the whole
        // list (and, since this runs from MainActivity.onCreate, the whole
        // app on every future launch).
        return (0 until array.length()).mapNotNull { i ->
            runCatching {
                val obj = array.getJSONObject(i)
                SessionRecord(
                    timestamp = obj.optLong("timestamp", 0L),
                    smashCount = obj.optInt("smashCount", 0),
                    clearCount = obj.optInt("clearCount", 0),
                    dropCount = obj.optInt("dropCount", 0),
                    // optInt so sessions saved before serve detection existed
                    // still load fine, just showing 0.
                    serveCount = obj.optInt("serveCount", 0),
                    bestSpeedKph = obj.optDouble("bestSpeedKph", 0.0).toFloat(),
                    rallyCount = obj.optInt("rallyCount", 0),
                    longestRally = obj.optInt("longestRally", 0),
                    avgHeartRate = obj.optDouble("avgHeartRate", 0.0),
                    calories = obj.optDouble("calories", 0.0),
                    avgRecoveryBpm = obj.optDouble("avgRecoveryBpm", 0.0)
                )
            }.onFailure { e ->
                Log.w("SessionHistoryStore", "Skipping unreadable session at index $i", e)
            }.getOrNull()
        }.sortedByDescending { it.timestamp }
    }

    private fun loadRaw(context: Context): JSONArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null) ?: return JSONArray()
        return try {
            JSONArray(raw)
        } catch (e: Exception) {
            // The stored string itself isn't valid JSON at all (shouldn't
            // normally happen, but a corrupted write is still possible) —
            // fall back to an empty history rather than crashing on every
            // launch from now on. Nothing recoverable to salvage here since
            // we can't even parse it as an array.
            Log.w("SessionHistoryStore", "Stored session history is corrupt, starting fresh", e)
            JSONArray()
        }
    }

    private fun sessionToJson(session: SessionRecord): JSONObject = JSONObject().apply {
        put("timestamp", session.timestamp)
        put("smashCount", session.smashCount)
        put("clearCount", session.clearCount)
        put("dropCount", session.dropCount)
        put("serveCount", session.serveCount)
        put("bestSpeedKph", session.bestSpeedKph.toDouble())
        put("rallyCount", session.rallyCount)
        put("longestRally", session.longestRally)
        put("avgHeartRate", session.avgHeartRate)
        put("calories", session.calories)
        put("avgRecoveryBpm", session.avgRecoveryBpm)
    }
}
