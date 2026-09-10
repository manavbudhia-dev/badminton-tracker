package com.example.badmintontracker.phone

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject

class WearDataListenerService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != SESSION_PATH) return

        val session = runCatching {
            val json = JSONObject(String(event.data, Charsets.UTF_8))
            SessionSummary(
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                smashCount = json.optInt("smashCount", 0),
                clearCount = json.optInt("clearCount", 0),
                dropCount = json.optInt("dropCount", 0),
                serveCount = json.optInt("serveCount", 0),
                bestSpeedKph = json.optDouble("bestSpeedKph", 0.0),
                rallyCount = json.optInt("rallyCount", 0),
                longestRally = json.optInt("longestRally", 0),
                avgHeartRate = json.optDouble("avgHeartRate", 0.0),
                calories = json.optDouble("calories", 0.0)
            )
        }.onFailure { e ->
            // Don't let a malformed/unexpected payload crash this service —
            // it's exported=true (required for Play Services to deliver
            // wear messages), so it needs to tolerate bad input gracefully
            // rather than assuming every message is well-formed.
            Log.w("WearDataListenerService", "Dropping unreadable session message", e)
        }.getOrNull() ?: return

        SessionStore.save(applicationContext, session)
    }

    companion object {
        private const val SESSION_PATH = "/badminton/session"
    }
}
