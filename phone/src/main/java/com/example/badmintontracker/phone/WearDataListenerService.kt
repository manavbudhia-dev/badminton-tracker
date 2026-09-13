package com.example.badmintontracker.phone

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService

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

                // Everything for this item — parsing AND saving — is inside
                // one try/catch, with cleanup in `finally`. Previously only
                // the parse step was wrapped in runCatching; if
                // SessionStore.save() itself threw (a DB error, a full
                // disk, etc.) that exception would propagate straight out
                // of onDataChanged, which (a) skipped the delete below for
                // this item, and, worse, (b) skipped every *remaining*
                // item in this batch's for-loop too, since an uncaught
                // exception aborts the loop entirely. Either way the item
                // never gets deleted, so it gets redelivered next sync and
                // can fail the exact same way forever. Isolating per item
                // means one bad item is logged and skipped, and the batch
                // — and its cleanup — keeps going.
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
                            avgRecoveryBpm = map.getDouble("avgRecoveryBpm", 0.0)
                        )
                    }.onFailure { e ->
                        // Don't let a malformed/unexpected payload crash this
                        // service — it's exported=true (required for Play
                        // Services to deliver wear data), so it needs to
                        // tolerate bad input gracefully rather than assuming
                        // every item is well-formed.
                        Log.w(TAG, "Dropping unreadable session item", e)
                    }.getOrNull()

                    // SessionStore.save() below is dedupe-safe (Room ignores
                    // a re-inserted timestamp), so it's fine to still delete
                    // a duplicate/unreadable item here — either way this
                    // service has finished with it and it shouldn't linger
                    // in the data layer or get redelivered again next sync.
                    if (session != null) SessionStore.save(applicationContext, session)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process session item, deleting it anyway", e)
                } finally {
                    // Best-effort cleanup: once we've processed (or given up
                    // on) an item, remove it so it doesn't accumulate
                    // on-device indefinitely. In `finally` — not after the
                    // try block — so it still runs even if save() above
                    // threw. Wrapped in its own runCatching too, since
                    // deleteDataItems() failing shouldn't stop us from
                    // moving on to the next item in this batch.
                    runCatching {
                        Wearable.getDataClient(applicationContext).deleteDataItems(item.uri)
                    }.onFailure { e ->
                        Log.w(TAG, "Failed to delete synced data item", e)
                    }
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
