package com.example.badmintontracker

import android.content.Context
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * Sends a JSON session summary from the watch to the paired phone app
 * using Google Play Services' Wearable Data Layer (MessageClient).
 *
 * The phone app must be installed and Bluetooth-paired via the Wear OS
 * app for `connectedNodes` to return anything — this fails silently
 * (empty node list) if the phone app isn't around, which is fine for a
 * standalone watch app.
 */
object WatchToPhoneSync {
    private const val SESSION_PATH = "/badminton/session"

    suspend fun sendSessionSummary(
        context: Context,
        smashCount: Int,
        clearCount: Int,
        dropCount: Int,
        serveCount: Int,
        bestSpeedKph: Float,
        rallyCount: Int,
        longestRally: Int,
        avgHeartRate: Double,
        calories: Double
    ) {
        // Nothing worth sending if no shots were recorded this session.
        if (smashCount + clearCount + dropCount == 0) return

        val json = JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("smashCount", smashCount)
            put("clearCount", clearCount)
            put("dropCount", dropCount)
            put("serveCount", serveCount)
            put("bestSpeedKph", bestSpeedKph.toDouble())
            put("rallyCount", rallyCount)
            put("longestRally", longestRally)
            put("avgHeartRate", avgHeartRate)
            put("calories", calories)
        }
        val payload = json.toString().toByteArray(Charsets.UTF_8)

        val nodeClient = Wearable.getNodeClient(context)
        val messageClient = Wearable.getMessageClient(context)

        val nodes = runCatching { nodeClient.connectedNodes.await() }.getOrDefault(emptyList())
        for (node in nodes) {
            runCatching { messageClient.sendMessage(node.id, SESSION_PATH, payload).await() }
        }
    }
}
