package com.example.badmintontracker

import android.content.Context
import android.util.Log
import org.json.JSONArray

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
    // HeartRateRecovery.kt. Defaults to 0.0 so this stays source-compatible
    // with sessions saved before this field existed.
    val avgRecoveryBpm: Double = 0.0,
    // Per-shot log for this session — see ShotLog.kt. Populated when a
    // session has just ended (MainActivity builds this record straight from
    // the in-memory snapshot). NOT populated by SessionHistoryStore.loadAll()
    // — that would mean holding up to 50 sessions * 1,000 shots in memory at
    // once just to render a history list that never shows them. Call
    // SessionHistoryStore.loadShotLog() for one session's shots on demand
    // instead (see that function's doc).
    val shots: List<ShotLogEntry> = emptyList()
)

/**
 * Local, on-watch session history, backed by Room (see SessionDatabase.kt
 * for the entities/DAO and why Room replaced an earlier SharedPreferences +
 * one-file-per-session scheme). Before any of this existed, a session's
 * counters only lived in memory (`MainActivity`'s Compose state) and were
 * gone the moment the activity died — and the only durable copy was
 * whatever summary made it across to the phone app, which doesn't help if
 * the phone was never paired/open.
 *
 * All functions here are suspend and expected to be called from a
 * background dispatcher (see call sites in MainActivity.kt) — Room forbids
 * running its queries on the main thread by default, and that default is
 * worth keeping rather than opting out of.
 */
object SessionHistoryStore {
    private const val MAX_SESSIONS = 50 // watch storage is tighter than phone
    private const val TAG = "SessionHistoryStore"

    @Volatile
    private var legacyMigrationDone = false

    suspend fun save(context: Context, session: SessionRecord) {
        migrateLegacyStoreIfNeeded(context)
        val dao = SessionDatabase.get(context).sessionDao()
        dao.insertSession(session.toEntity())
        if (session.shots.isNotEmpty()) {
            dao.insertShots(session.shots.map { it.toEntity(session.timestamp) })
        }
        // Evict everything past the MAX_SESSIONS most recent — their shots
        // cascade-delete automatically (see ShotEntity's foreign key).
        val keep = dao.mostRecentTimestamps(MAX_SESSIONS)
        if (keep.isNotEmpty()) dao.deleteSessionsNotIn(keep)
    }

    /** The most recent [MAX_SESSIONS] sessions — what the History list screen shows. */
    suspend fun loadAll(context: Context): List<SessionRecord> {
        migrateLegacyStoreIfNeeded(context)
        return loadSessionsPage(context, limit = MAX_SESSIONS, offset = 0)
    }

    /**
     * A genuine paginated read — LIMIT/OFFSET pushed down to SQLite via
     * Room, so a future "load more" History UI (or anything else that
     * doesn't want the whole table at once) can page through sessions
     * without ever holding more than one page in memory. [loadAll] is just
     * this with a single page sized to [MAX_SESSIONS].
     */
    suspend fun loadSessionsPage(context: Context, limit: Int, offset: Int): List<SessionRecord> =
        SessionDatabase.get(context).sessionDao()
            .loadSessionsPage(limit, offset)
            .map { it.toSessionRecord() }

    /**
     * Loads one session's per-shot log on demand — e.g. when the user
     * actually opens that session's shot-by-shot detail. Deliberately not
     * part of [loadAll]/[loadSessionsPage]: eagerly reading every session's
     * (up to 1,000-entry) shot log just to populate a list screen that
     * never shows them is exactly the memory/latency problem the Room
     * migration (and the file-per-session scheme before it) was for.
     */
    suspend fun loadShotLog(context: Context, timestamp: Long): List<ShotLogEntry> =
        SessionDatabase.get(context).sessionDao()
            .loadShotsForSession(timestamp)
            .map { it.toShotLogEntry() }

    /**
     * One-time import of data left over from the pre-Room storage scheme:
     * a single SharedPreferences JSON blob of session summaries, plus one
     * shot-log JSON file per session under filesDir/shot_logs/. Runs at
     * most once per process (the [legacyMigrationDone] guard) and is
     * self-terminating across app restarts too: once the legacy
     * SharedPreferences key is cleared at the end, there's nothing left to
     * find on the next launch, so this becomes a no-op single existence
     * check from then on.
     *
     * Deliberately best-effort: if anything here fails, the exception is
     * caught and logged rather than propagated, since a failed migration
     * should never block the app from using its (now-empty) Room database
     * going forward — the alternative, crashing on every launch until the
     * bad legacy data is fixed, is strictly worse than losing that old
     * history.
     */
    private suspend fun migrateLegacyStoreIfNeeded(context: Context) {
        if (legacyMigrationDone) return
        try {
            val prefs = context.getSharedPreferences("badminton_watch_sessions", Context.MODE_PRIVATE)
            val raw = prefs.getString("sessions_json", null)
            if (raw != null) {
                val array = try {
                    JSONArray(raw)
                } catch (e: Exception) {
                    Log.w(TAG, "Legacy session history is corrupt, discarding it", e)
                    null
                }
                if (array != null) {
                    val dao = SessionDatabase.get(context).sessionDao()
                    val shotLogDir = java.io.File(context.filesDir, "shot_logs")
                    for (i in 0 until array.length()) {
                        runCatching {
                            val obj = array.getJSONObject(i)
                            val timestamp = obj.optLong("timestamp", 0L)
                            dao.insertSession(
                                SessionEntity(
                                    timestamp = timestamp,
                                    smashCount = obj.optInt("smashCount", 0),
                                    clearCount = obj.optInt("clearCount", 0),
                                    dropCount = obj.optInt("dropCount", 0),
                                    serveCount = obj.optInt("serveCount", 0),
                                    bestSpeedKph = obj.optDouble("bestSpeedKph", 0.0).toFloat(),
                                    rallyCount = obj.optInt("rallyCount", 0),
                                    longestRally = obj.optInt("longestRally", 0),
                                    avgHeartRate = obj.optDouble("avgHeartRate", 0.0),
                                    calories = obj.optDouble("calories", 0.0),
                                    avgRecoveryBpm = obj.optDouble("avgRecoveryBpm", 0.0)
                                )
                            )
                            val shotLogFile = java.io.File(shotLogDir, "$timestamp.json")
                            if (shotLogFile.exists()) {
                                val shots = JSONArray(shotLogFile.readText()).toShotLogEntries()
                                if (shots.isNotEmpty()) {
                                    dao.insertShots(shots.map { it.toEntity(timestamp) })
                                }
                            }
                        }.onFailure { e ->
                            Log.w(TAG, "Skipping unreadable legacy session at index $i during migration", e)
                        }
                    }
                }
                // Clear the legacy blob (and its shot-log files) once
                // imported — or once we've given up on it, in the corrupt
                // case above — so this whole block becomes a cheap no-op
                // (the `raw != null` check fails) on every future call.
                prefs.edit().remove("sessions_json").apply()
                shotLogDir.deleteRecursively()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Legacy SharedPreferences -> Room migration failed, continuing without it", e)
        } finally {
            legacyMigrationDone = true
        }
    }
}
