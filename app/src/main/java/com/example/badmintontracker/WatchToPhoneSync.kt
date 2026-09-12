package com.example.badmintontracker

import android.content.Context
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
        avgRecoveryBpm: Double = 0.0
    ) {
        // Nothing worth sending if no shots were recorded this session.
        if (smashCount + clearCount + dropCount == 0) return

        val request = PutDataMapRequest.create("$SESSION_PATH_PREFIX/$timestamp").apply {
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
        }.asPutDataRequest().setUrgent() // ask the system to sync this as soon as a connection exists

        // putDataItem() only throws for local problems (payload too large,
        // Play Services unavailable, etc.) — NOT for "phone unreachable
        // right now". An unreachable phone is exactly the case this
        // DataItem is designed to ride out, so there's nothing extra to do
        // here on that path; it just stays queued until it lands.
        runCatching {
            Wearable.getDataClient(context).putDataItem(request).await()
        }
    }
}
