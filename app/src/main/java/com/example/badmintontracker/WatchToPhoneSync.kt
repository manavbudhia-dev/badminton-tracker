package com.example.badmintontracker

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/**
 * Pushes a session summary from the watch to the paired phone app.
 *
 * Reliability note — this replaces an earlier MessageClient-based version:
 * MessageClient.sendMessage() only works while a node is actively
 * connected. If the phone is out of Bluetooth range, asleep, or the Wear OS
 * connection is briefly down at the exact moment a session ends, the
 * message used to be silently dropped with no retry — a real data-loss
 * risk for something that only happens once per session.
 *
 * DataClient.putDataItem() instead hands the payload to the Wear OS data
 * layer, which persists it on-device and keeps retrying delivery in the
 * background — including across app restarts and reconnects — until the
 * phone actually receives it. No custom retry/queue logic needed; the
 * platform already does this. See WearDataListenerService on the phone
 * side for how it's received and cleaned up once safely stored.
 */
object WatchToPhoneSync {
    // Each session gets its own path (this prefix + "/" + its timestamp)
    // rather than one shared path, because DataClient replaces-by-path:
    // reusing a single fixed path would mean an unsynced session gets
    // silently overwritten — and lost — the moment the next session ends
    // before the first one has synced.
    const val SESSION_PATH_PREFIX = "/badminton/session"

    private const val TAG = "WatchToPhoneSync"

    // The Wear OS Data Layer enforces a hard ~100KB limit per DataItem.
    // ExerciseSessionService caps a session at 1,000 logged shots, and even
    // with speedKph rounded to one decimal (see ShotLog.toJsonObject), a
    // full shot log alone can land in the 60-80KB range — before the other
    // fields and DataMap/parcel overhead. Rather than hoping that always
    // stays under the ceiling and silently losing the whole sync the day it
    // doesn't, the shot log is capped to a conservative slice of the total
    // budget, leaving headroom for everything else.
    private const val SHOT_LOG_BYTE_BUDGET = 80 * 1024

    suspend fun sendSessionSummary(
        context: Context,
        timestamp: Long,
        smashCount: Int,
        clearCount: Int,
        dropCount: Int,
        serveCount: Int,
        bestSpeedKph: Float,
        rallyCount: Int,
        longestRally: Int,
        avgHeartRate: Double,
        calories: Double,
        avgRecoveryBpm: Double = 0.0,
        shots: List<ShotLogEntry> = emptyList()
    ) {
        // Nothing worth sending if no shots were recorded this session.
        if (smashCount + clearCount + dropCount == 0) return

        val trimmedShots = shots.trimmedToByteBudget(SHOT_LOG_BYTE_BUDGET)
        if (trimmedShots.size < shots.size) {
            Log.w(
                TAG,
                "Shot log for session $timestamp trimmed from ${shots.size} to " +
                    "${trimmedShots.size} entries to stay under the Data Layer's payload limit"
            )
        }

        fun buildRequest(includeShots: Boolean) =
            PutDataMapRequest.create("$SESSION_PATH_PREFIX/$timestamp").apply {
                dataMap.putLong("timestamp", timestamp)
                dataMap.putInt("smashCount", smashCount)
                dataMap.putInt("clearCount", clearCount)
                dataMap.putInt("dropCount", dropCount)
                dataMap.putInt("serveCount", serveCount)
                dataMap.putDouble("bestSpeedKph", bestSpeedKph.toDouble())
                dataMap.putInt("rallyCount", rallyCount)
                dataMap.putInt("longestRally", longestRally)
                dataMap.putDouble("avgHeartRate", avgHeartRate)
                dataMap.putDouble("calories", calories)
                dataMap.putDouble("avgRecoveryBpm", avgRecoveryBpm)
                // Sent as a JSON string rather than a native DataMap list — DataMap
                // supports typed lists but not lists of nested objects, and this is
                // simpler than flattening ShotLogEntry into three parallel arrays.
                dataMap.putString(
                    "shots",
                    (if (includeShots) trimmedShots else emptyList()).toJsonArray().toString()
                )
            }.asPutDataRequest().setUrgent() // ask the system to sync this as soon as a connection exists

        // putDataItem() only throws for local problems (payload too large,
        // Play Services unavailable, etc.) — NOT for "phone unreachable
        // right now". An unreachable phone is exactly the case this
        // DataItem is designed to ride out, so there's nothing extra to do
        // on that path; it just stays queued until it lands. The trim above
        // should keep every real session under the payload limit, but if a
        // put still fails, fall back to resending just the aggregate
        // summary (no shot log) rather than losing the whole session
        // silently — and log either way instead of swallowing it outright.
        val result = runCatching {
            Wearable.getDataClient(context).putDataItem(buildRequest(includeShots = true)).await()
        }
        if (result.isFailure) {
            Log.w(TAG, "Failed to sync session $timestamp to phone", result.exceptionOrNull())
            if (trimmedShots.isNotEmpty()) {
                runCatching {
                    Wearable.getDataClient(context).putDataItem(buildRequest(includeShots = false)).await()
                }.onFailure { e ->
                    Log.w(TAG, "Retry without shot log for session $timestamp also failed", e)
                }
            }
        }
    }

    /**
     * Keeps entries (in order) only while their serialized JSON stays within
     * [maxBytes], measuring the same per-entry encoding [List.toJsonArray]
     * uses (via [ShotLogEntry.toJsonObject]) rather than a rough estimate.
     * O(n): each entry is serialized once, not re-measured as the array
     * grows.
     */
    private fun List<ShotLogEntry>.trimmedToByteBudget(maxBytes: Int): List<ShotLogEntry> {
        if (isEmpty()) return this
        var usedBytes = 2 // enclosing "[" and "]"
        val kept = ArrayList<ShotLogEntry>(size)
        for ((index, entry) in withIndex()) {
            val separatorBytes = if (index > 0) 1 else 0 // the "," joining entries
            val entryBytes = entry.toJsonObject().toString().toByteArray(Charsets.UTF_8).size
            if (usedBytes + separatorBytes + entryBytes > maxBytes) break
            usedBytes += separatorBytes + entryBytes
            kept.add(entry)
        }
        return kept
    }
}
