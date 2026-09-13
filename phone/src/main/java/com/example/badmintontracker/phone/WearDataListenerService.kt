package com.example.badmintontracker.phone

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONArray

/**
 * Receives session summaries synced from the watch via the Wear OS data
 * layer (see WatchToPhoneSync.kt on the watch side for why this uses
 * DataClient rather than a plain message).
 */
class WearDataListenerService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        try {
            for (event in dataEvents) {
                if (event.type != DataEvent.TYPE_CHANGED) continue
                val item = event.dataItem
                if (item.uri.path?.startsWith(SESSION_PATH_PREFIX) != true) continue

                // Each item gets its own try/finally. Previously,
                // SessionStore.save() and deleteDataItems() below ran
                // unguarded: if either threw, the exception propagated out
                // of this for-loop entirely, which (a) skipped the delete
                // for this item — leaving a "poison" item the watch would
                // keep redelivering forever if the same failure recurs
                // deterministically — and (b) abandoned every *other* event
                // still left in this buffer, not just this one. Isolating
                // each item's processing fixes both: a failure here is
                // logged and contained, the remaining events still get
                // processed, and the delete below always runs.
                try {
                    val session = runCatching {
                        val map = DataMapItem.fromDataItem(item).dataMap
                        SessionSummary(
                            timestamp = map.getLong("timestamp", System.currentTimeMillis()),
                            smashCount = map.getInt("smashCount", 0),
                            clearCount = map.getInt("clearCount", 0),
                            dropCount = map.getInt("dropCount", 0),
                            serveCount = map.getInt("serveCount", 0),
                            bestSpeedKph = map.getDouble("bestSpeedKph", 0.0),
                            rallyCount = map.getInt("rallyCount", 0),
                            longestRally = map.getInt("longestRally", 0),
                            avgHeartRate = map.getDouble("avgHeartRate", 0.0),
                            calories = map.getDouble("calories", 0.0),
                            avgRecoveryBpm = map.getDouble("avgRecoveryBpm", 0.0),
                            // getString returns null both when the key is absent
                            // (session synced before this feature existed) and
                            // when it's an empty session's "[]" — either way,
                            // absent-or-unparsable just means no shot log.
                            shots = runCatching { JSONArray(map.getString("shots")) }
                                .getOrNull()
                                .toShotLogEntries()
                        )
                    }.onFailure { e ->
                        // Don't let a malformed/unexpected payload crash this
                        // service — it's exported=true (required for Play
                        // Services to deliver wear data), so it needs to
                        // tolerate bad input gracefully rather than assuming
                        // every item is well-formed.
                        Log.w(TAG, "Dropping unreadable session item", e)
                    }.getOrNull()

                    // SessionStore.save() below is dedupe-safe (skips a
                    // timestamp it's already stored), so it's fine to still
                    // delete a duplicate/unreadable item here — either way this
                    // service has finished with it and it shouldn't linger in
                    // the data layer or get redelivered again next sync.
                    if (session != null) {
                        runCatching { SessionStore.save(applicationContext, session) }
                            .onFailure { e -> Log.w(TAG, "Failed to save session locally", e) }
                    }
                } finally {
                    // Best-effort cleanup: once we've processed (or given up on)
                    // an item, remove it so it doesn't accumulate on-device
                    // indefinitely. Fire-and-forget is fine — if this fails, the
                    // item just gets reprocessed on the next sync, and the
                    // dedupe check above means that's harmless, not a duplicate.
                    // Guarded with runCatching too: this call itself throwing
                    // (e.g. Play Services momentarily unavailable) used to be
                    // able to take down the rest of the loop just like the two
                    // calls above.
                    runCatching { Wearable.getDataClient(applicationContext).deleteDataItems(item.uri) }
                        .onFailure { e -> Log.w(TAG, "Failed to delete synced data item", e) }
                }
            }
        } finally {
            dataEvents.release()
        }
    }

    companion object {
        private const val TAG = "WearDataListenerService"
        private const val SESSION_PATH_PREFIX = "/badminton/session"
    }
}
