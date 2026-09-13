package com.example.badmintontracker

import org.json.JSONArray
import org.json.JSONObject

/**
 * One classified shot, kept for the "Shot Log" feature — a per-shot record
 * (not just the running totals already in SessionRecord) so a session can
 * be looked back on shot-by-shot: exactly when your fastest smash happened,
 * not just that it happened sometime this session.
 *
 * Appended to in MainActivity.onSensorChanged, persisted as part of a
 * session in SessionHistoryStore, and synced to the phone in
 * WatchToPhoneSync.
 */
data class ShotLogEntry(
    val timestampMillis: Long,
    val type: String, // "Smash" | "Clear" | "Drop" | "Serve"
    val speedKph: Float
)

/**
 * Labels a classified shot for the log, or returns null to skip logging it.
 *
 * Serve takes priority over the SMASH/CLEAR/DROP classification when both
 * apply — see ServeDetector.kt's doc comment on why serve is orthogonal —
 * since "this was a serve" is usually the more useful thing to know than a
 * low-confidence sub-classification of what kind of shot it also looked
 * like. ShotType.UNKNOWN (and not a serve) returns null: the classifier
 * wasn't confident enough to bucket it into smashCount/clearCount/
 * dropCount either, and a log entry reading "Unknown — 140 kph" wouldn't be
 * useful to look back on, just noise.
 */
fun labelForShot(shotType: ShotType, isServe: Boolean): String? = when {
    isServe -> "Serve"
    shotType == ShotType.SMASH -> "Smash"
    shotType == ShotType.CLEAR -> "Clear"
    shotType == ShotType.DROP -> "Drop"
    else -> null
}

fun List<ShotLogEntry>.toJsonArray(): JSONArray = JSONArray().also { array ->
    forEach { entry ->
        array.put(
            JSONObject().apply {
                put("timestamp", entry.timestampMillis)
                put("type", entry.type)
                put("speedKph", entry.speedKph.toDouble())
            }
        )
    }
}

/** Tolerant of a null/absent array (older data) and of individual malformed entries — skips rather than throwing. */
fun JSONArray?.toShotLogEntries(): List<ShotLogEntry> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { i ->
        runCatching {
            val obj = getJSONObject(i)
            ShotLogEntry(
                timestampMillis = obj.optLong("timestamp", 0L),
                type = obj.optString("type", "Smash"),
                speedKph = obj.optDouble("speedKph", 0.0).toFloat()
            )
        }.getOrNull()
    }
}
