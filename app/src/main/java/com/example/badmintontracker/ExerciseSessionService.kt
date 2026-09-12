package com.example.badmintontracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseTrackedStatus
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

data class HealthMetrics(
    val heartRateBpm: Double = 0.0,
    val caloriesKcal: Double = 0.0
)

/**
 * Keeps the Health Services exercise session alive using a ForegroundService
 * — this is Google's recommended architecture (see README.md), because the
 * Activity alone can be killed when the screen turns off mid-match.
 * MainActivity binds to this service and observes [metrics] for live
 * heart rate / calories.
 *
 * Based on the official pattern at:
 * https://developer.android.com/health-and-fitness/health-services/active-data
 */
class ExerciseSessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val exerciseClient by lazy { HealthServices.getClient(this).exerciseClient }

    private val _metrics = MutableStateFlow(HealthMetrics())
    val metrics: StateFlow<HealthMetrics> = _metrics

    // Fired once per update with (approximate timestamp, latest bpm) — used
    // by HeartRateRecoveryTracker, which needs every observation to build a
    // short time series around each rally's rest window, not just whichever
    // value is newest for display (that's what `metrics` above is for).
    // The timestamp is "now, when this callback fired" rather than a
    // sensor-reported one: Health Services delivers HR updates roughly once
    // a second during an active exercise session, which is plenty of
    // resolution for a 15-20 second recovery window, and this reuses the
    // exact same `.lastOrNull()?.value` accessor already proven below
    // rather than parsing SampleDataPoint's own timestamp fields.
    var onHeartRateSample: ((timestampMillis: Long, bpm: Double) -> Unit)? = null

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): ExerciseSessionService = this@ExerciseSessionService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        // NOTE: startForeground() is deliberately NOT called here. This
        // service is bound as soon as MainActivity launches (see
        // bindService in MainActivity.onCreate), regardless of whether the
        // user has pressed "Start" yet — calling startForeground() at that
        // point would pin an ongoing "session running" notification even
        // while idle. It's called from startExercise()/stopExercise()
        // instead, so the notification only exists while a session is
        // actually being tracked.

        exerciseClient.setUpdateCallback(object : ExerciseUpdateCallback {
            override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
                val hr = update.latestMetrics.getData(DataType.HEART_RATE_BPM)
                    .lastOrNull()?.value ?: _metrics.value.heartRateBpm
                val cal = update.latestMetrics.getData(DataType.CALORIES_TOTAL)
                    .lastOrNull()?.total ?: _metrics.value.caloriesKcal
                _metrics.value = HealthMetrics(hr, cal)
                if (hr > 0.0) onHeartRateSample?.invoke(System.currentTimeMillis(), hr)
            }

            override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {
                // Not used yet — could mark a "lap" per game/set later.
            }

            override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {
                // If HEART_RATE_BPM stays UNAVAILABLE for a while, the strap
                // is probably loose — worth surfacing in the UI eventually.
            }
        })
    }

    fun startExercise() {
        scope.launch {
            try {
                // Don't steal an exercise session another app already owns.
                val info = exerciseClient.getCurrentExerciseInfoAsync().await()
                if (info.exerciseTrackedStatus == ExerciseTrackedStatus.OTHER_APP_IN_PROGRESS) {
                    return@launch
                }
                val config = ExerciseConfig(
                    exerciseType = ExerciseType.BADMINTON,
                    dataTypes = setOf(DataType.HEART_RATE_BPM, DataType.CALORIES_TOTAL),
                    isAutoPauseAndResumeEnabled = false,
                    isGpsEnabled = false // indoor sport, no GPS needed
                )
                exerciseClient.startExerciseAsync(config).await()
                // Only pin the "session running" notification once we've
                // actually managed to start tracking.
                startForeground(NOTIFICATION_ID, buildNotification())
            } catch (e: Exception) {
                // Health Services can throw for all sorts of recoverable
                // reasons (e.g. a stale session left over from a previous
                // crash, a sensor briefly unavailable) — none of that should
                // crash the app. Sensor-based smash tracking in MainActivity
                // is independent of this and keeps working either way.
                Log.w(TAG, "startExercise failed, HR/calories won't be tracked this session", e)
            }
        }
    }

    fun stopExercise() {
        scope.launch {
            try {
                exerciseClient.endExerciseAsync().await()
            } catch (e: Exception) {
                // Most commonly: there was no active exercise to end (e.g.
                // startExercise() above failed or was skipped). Nothing to
                // recover from here, just don't crash on Stop.
                Log.w(TAG, "endExercise failed (was a session actually running?)", e)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val channelId = "exercise_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Badminton session", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Badminton session running")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 42
        private const val TAG = "ExerciseSessionService"
    }
}
