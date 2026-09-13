package com.example.badmintontracker.phone

import org.json.JSONArray
import org.json.JSONObject

/**
 * One classified shot within a session — mirrors the watch app's
 * ShotLogEntry (app/.../ShotLog.kt) on purpose, same shape, same JSON
 * field names, so the two sides can be kept in sync by inspection.
 */
data class ShotLogEntry(
    val timestampMillis: Long,
    val type: String, // "Smash" | "Clear" | "Drop" | "Serve"
    val speedKph: Float
)

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

/** Tolerant of a null/absent array (sessions synced before this feature existed) and of individual malformed entries. */
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
