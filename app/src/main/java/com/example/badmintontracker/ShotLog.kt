package com.example.badmintontracker

import org.json.JSONArray
import org.json.JSONObject

/**
 * One classified shot, kept for the "Shot Log" feature — a per-shot record
 * (not just the running totals already in SessionRecord) so a session can
 * be looked back on shot-by-shot: exactly when your fastest smash happened,
 * not just that it happened sometime this session.
 *
 * Appended to in ExerciseSessionService.onSensorChanged, persisted as part
 * of a session in SessionHistoryStore, and synced to the phone in
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

/**
 * Serializes a single entry. Pulled out of [toJsonArray] so anything that
 * needs to reason about one entry's serialized size (see
 * WatchToPhoneSync.trimmedToByteBudget) uses the exact same encoding rather
 * than an approximation of it.
 *
 * speedKph is rounded to one decimal place before being handed to
 * org.json. Without this, `entry.speedKph.toDouble()` widens a 32-bit float
 * straight to a 64-bit double, which exposes the float's binary rounding
 * error as a ~17-digit decimal (e.g. 187.34f becomes
 * 187.33999633789062) instead of the clean value that was actually
 * measured. That's not just cosmetic: at up to 1,000 shots per session,
 * those extra ~12 digits per entry are a large, pure-waste contributor to
 * exactly the payload sizes that overflow SharedPreferences (see
 * SessionHistoryStore) and the Data Layer's ~100KB DataItem limit (see
 * WatchToPhoneSync) — and a phone-accelerometer speed estimate never had
 * that much genuine precision in the first place.
 */
fun ShotLogEntry.toJsonObject(): JSONObject = JSONObject().apply {
    put("timestamp", timestampMillis)
    put("type", type)
    put("speedKph", Math.round(speedKph * 10f) / 10.0)
}

fun List<ShotLogEntry>.toJsonArray(): JSONArray = JSONArray().also { array ->
    forEach { entry -> array.put(entry.toJsonObject()) }
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
