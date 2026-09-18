package com.example.badmintontracker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseTrackedStatus
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

data class HealthMetrics(
    val heartRateBpm: Double = 0.0,
    val caloriesKcal: Double = 0.0
)

/**
 * Live shot-tracking state for the current session — everything
 * MainActivity's UI needs to render while a session is running. Rebuilt
 * from scratch each time [ExerciseSessionService.startTracking] is called.
 */
data class ShotTrackingState(
    val smashCount: Int = 0,
    val clearCount: Int = 0,
    val dropCount: Int = 0,
    val serveCount: Int = 0,
    val bestSpeedKph: Float = 0f,
    val lastSpeedKph: Float = 0f,
    val rallyCount: Int = 0,
    val longestRally: Int = 0,
    val lastRecoveryPoint: RallyRecoveryPoint? = null
)

/**
 * Keeps the Health Services exercise session alive using a ForegroundService
 * — this is Google's recommended architecture (see README.md), because the
 * Activity alone can be killed when the screen turns off mid-match.
 * MainActivity binds to this service and observes [metrics] for live
 * heart rate / calories.
 *
 * IMPORTANT — this service also owns live shot detection (accelerometer +
 * gyroscope -> SmashDetector -> shot classification -> [trackingState]).
 * That used to live in MainActivity as a SensorEventListener registered on
 * the Activity itself, which meant Wear OS's onPause() (fired the instant
 * the screen dims or the wrist drops) silently killed shot tracking a few
 * seconds into every match — the Activity going background-but-not-dead is
 * completely normal on a watch, and unregistering sensors on onPause()
 * assumed otherwise. Registering the sensors here instead, tied to
 * startTracking()/stopTracking() rather than any Activity lifecycle
 * callback, means shot detection keeps running for the entire session
 * regardless of screen state — exactly like the heart-rate/calories
 * tracking already did.
 *
 * Based on the official pattern at:
 * https://developer.android.com/health-and-fitness/health-services/active-data
 */
class ExerciseSessionService : Service(), SensorEventListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val exerciseClient by lazy { HealthServices.getClient(this).exerciseClient }

    private val _metrics = MutableStateFlow(HealthMetrics())
    val metrics: StateFlow<HealthMetrics> = _metrics

    private val _trackingState = MutableStateFlow(ShotTrackingState())
    val trackingState: StateFlow<ShotTrackingState> = _trackingState

    // --- Shot detection pipeline (moved here from MainActivity) ----------
    private lateinit var sensorManager: SensorManager
    private var accelSensor: Sensor? = null
    private var gyroSensor: Sensor? = null

    private val detector = SmashDetector()
    private val classifier = ShotClassifier()
    private val rallyTracker = RallyTracker()
    private val serveDetector = ServeDetector()
    private var mlClassifier: MLShotClassifier? = null
    val hasMlModel: Boolean get() = mlClassifier != null

    private val heartRateRecoveryTracker = HeartRateRecoveryTracker()
    // Per-shot record for the current session — see ShotLog.kt. Read back
    // via snapshotShotLog() when a session ends.
    private val shotLog = mutableListOf<ShotLogEntry>()
    private var previousShotTimestamp = 0L

    private var isTracking = false
    private var wakeLock: PowerManager.WakeLock? = null

    // --- Calibration-capture pipeline (moved here from MainActivity) -----
    // A second, independent SmashDetector — same reasoning as `detector`
    // above and the same one-instance-per-sensor-consumer rule live shot
    // detection follows: a reference swing during calibration shouldn't
    // touch smashCount/clearCount/dropCount, the rally tracker, or
    // bestSpeedKph, and it shares the live detector's default trigger
    // threshold (for CalibrationSession's math to line up) but never its
    // calibration line, since only peakAccelMagnitude is ever read back.
    // Living here rather than in MainActivity means a calibration capture
    // — which can involve real waiting time between "swing now" and the
    // user actually swinging — survives the same screen-timeout/onPause
    // that used to kill live shot tracking, for the same reason (see this
    // class's doc comment). MainActivity's FLAG_KEEP_SCREEN_ON while
    // isCalibrating (see MainActivity.kt) keeps the wizard's UI itself
    // visible; this is what keeps the *capture* alive underneath it even if
    // something else backgrounds the Activity anyway.
    private val calibrationDetector = SmashDetector(triggerThreshold = SmashDetector.DEFAULT_TRIGGER_THRESHOLD)
    private var isCalibrating = false
    private val _calibrationSwingCaptured = MutableSharedFlow<Float>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    /** Emits one peakAccelMagnitude reading per captured reference swing — see startCalibrationCapture(). */
    val calibrationSwingCaptured: SharedFlow<Float> = _calibrationSwingCaptured

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
        // while idle. It's called from startTracking() instead, so the
        // notification only exists while a session is actually running.

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        // Null if shot_classifier.tflite isn't in assets/ yet — onSensorChanged
        // falls back to the rule-based ShotClassifier in that case.
        mlClassifier = MLShotClassifier.tryLoad(this)

        // Apply a previously-saved calibration line immediately — without
        // this, every fresh launch would silently fall back to the factory
        // guess even for someone who already calibrated.
        val calibration = SpeedCalibrationStore.load(this)
        detector.updateCalibration(calibration.slope, calibration.intercept)

        exerciseClient.setUpdateCallback(object : ExerciseUpdateCallback {
            override fun onRegistered() {
                // No-op: nothing needs to happen until the first update arrives.
            }

            override fun onRegistrationFailed(throwable: Throwable) {
                Log.w(TAG, "Failed to register exercise update callback", throwable)
            }

            override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
                val hr = update.latestMetrics.getData(DataType.HEART_RATE_BPM)
                    .lastOrNull()?.value ?: _metrics.value.heartRateBpm
                val cal = update.latestMetrics.getData(DataType.CALORIES_TOTAL)
                    ?.total ?: _metrics.value.caloriesKcal
                _metrics.value = HealthMetrics(hr, cal)
                // Health Services delivers HR updates roughly once a second
                // during an active exercise session — plenty of resolution
                // for HeartRateRecoveryTracker's 15-20 second rest windows.
                if (hr > 0.0) heartRateRecoveryTracker.onHeartRateSample(System.currentTimeMillis(), hr)
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

    /**
     * Starts a tracking session: resets all counters, starts listening to
     * the accelerometer + gyroscope for shot detection, holds a partial
     * wake lock so sensor sampling keeps running with the screen off, and
     * starts the underlying Health Services exercise for heart rate /
     * calories. Call [stopTracking] to end it.
     */
    fun startTracking() {
        if (isTracking) return
        isTracking = true
        rallyTracker.reset()
        heartRateRecoveryTracker.reset()
        shotLog.clear()
        previousShotTimestamp = 0L
        _trackingState.value = ShotTrackingState()

        accelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        acquireWakeLock()
        startExercise()
    }

    /** Stops shot detection, releases the wake lock, and ends the exercise session. */
    fun stopTracking() {
        if (!isTracking) return
        isTracking = false
        sensorManager.unregisterListener(this)
        releaseWakeLock()
        stopExercise()
    }

    /**
     * Starts listening for one calibration reference swing — see
     * calibrationDetector's doc comment above for why this is a completely
     * separate pipeline from startTracking()'s. Guarded against also being
     * mid-tracking-session so the two never listen to the sensors at once;
     * MainActivity's UI already gates calibration to !isTracking too, so
     * this is a defensive double-check rather than the primary guard.
     */
    fun startCalibrationCapture() {
        if (isTracking || isCalibrating) return
        isCalibrating = true
        accelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyroSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stopCalibrationCapture() {
        if (!isCalibrating) return
        isCalibrating = false
        sensorManager.unregisterListener(this)
    }

    /** Snapshot of this session's per-shot log, read once when the session ends. */
    fun snapshotShotLog(): List<ShotLogEntry> = shotLog.toList()

    /** Snapshot of this session's heart-rate-recovery points, read once when the session ends. */
    fun snapshotRecoveryPoints(): List<RallyRecoveryPoint> = heartRateRecoveryTracker.points

    fun updateCalibration(slope: Float, intercept: Float) {
        detector.updateCalibration(slope, intercept)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$TAG:shotTracking"
        ).apply {
            setReferenceCounted(false)
            // A generous safety-valve timeout, not the expected session
            // length: stopTracking() always releases explicitly. This just
            // guarantees the lock can't be held forever if that call is
            // ever missed (e.g. the process is killed unusually).
            acquire(MAX_WAKE_LOCK_DURATION_MILLIS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (isCalibrating) {
            handleCalibrationSensorEvent(event)
            return
        }
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE ->
                detector.onGyroSample(event.values[0], event.values[1], event.values[2])

            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val shot = detector.onAccelSample(
                    System.currentTimeMillis(), event.values[0], event.values[1], event.values[2]
                ) ?: return

                val classification = mlClassifier?.classify(shot) ?: classifier.classify(shot)
                val current = _trackingState.value
                var smashCount = current.smashCount
                var clearCount = current.clearCount
                var dropCount = current.dropCount
                when (classification) {
                    ShotType.SMASH -> smashCount++
                    ShotType.CLEAR -> clearCount++
                    ShotType.DROP -> dropCount++
                    ShotType.UNKNOWN -> { /* not confident enough to bucket it */ }
                }
                val lastSpeedKph = shot.estimatedSpeedKph
                val bestSpeedKph = if (shot.estimatedSpeedKph > current.bestSpeedKph) shot.estimatedSpeedKph else current.bestSpeedKph

                // Serve is orthogonal to SMASH/CLEAR/DROP/UNKNOWN (see
                // ServeDetector.kt) — a shot can be classified as, say, DROP
                // *and* flagged as a serve at the same time.
                val isServe = serveDetector.isServe(shot)
                var serveCount = current.serveCount
                if (isServe) serveCount++

                // Shot Log: a per-shot record (time + type + speed) so a
                // session can be looked back on shot-by-shot later, not just
                // as running totals. Capped defensively — see companion
                // object — since watch storage is tighter than the phone's.
                labelForShot(classification, isServe)?.let { label ->
                    if (shotLog.size < MAX_LOGGED_SHOTS_PER_SESSION) {
                        shotLog.add(ShotLogEntry(shot.timestampMillis, label, shot.estimatedSpeedKph))
                    }
                }

                // Every detected shot counts as rally activity. Whether it
                // starts a *new* rally is decided by RallyTracker: an actual
                // serve always does, and otherwise the gap since the last
                // shot is the fallback signal.
                val startedNewRally = rallyTracker.onShot(shot.timestampMillis, isServe)

                // A new rally starting means the *previous* shot was the
                // last one of the rally that just ended — i.e. exactly the
                // boundaries of the rest window that just elapsed. Skipped
                // on the very first shot of the session (previousShotTimestamp
                // still 0), since there's no rest before a first rally.
                var lastRecoveryPoint = current.lastRecoveryPoint
                if (startedNewRally && previousShotTimestamp > 0L) {
                    heartRateRecoveryTracker.onRallyBoundary(previousShotTimestamp, shot.timestampMillis)
                    lastRecoveryPoint = heartRateRecoveryTracker.points.lastOrNull()
                }
                previousShotTimestamp = shot.timestampMillis

                _trackingState.value = ShotTrackingState(
                    smashCount = smashCount,
                    clearCount = clearCount,
                    dropCount = dropCount,
                    serveCount = serveCount,
                    bestSpeedKph = bestSpeedKph,
                    lastSpeedKph = lastSpeedKph,
                    rallyCount = rallyTracker.rallyCount,
                    longestRally = rallyTracker.longestRallyShots,
                    lastRecoveryPoint = lastRecoveryPoint
                )
            }
        }
    }

    private fun handleCalibrationSensorEvent(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE ->
                calibrationDetector.onGyroSample(event.values[0], event.values[1], event.values[2])

            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val shot = calibrationDetector.onAccelSample(
                    System.currentTimeMillis(), event.values[0], event.values[1], event.values[2]
                ) ?: return
                // tryEmit rather than a suspend emit: this runs on a plain
                // system sensor callback, not inside a coroutine.
                _calibrationSwingCaptured.tryEmit(shot.peakAccelMagnitude)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun startExercise() {
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
                //
                // The type is passed here explicitly, matching
                // android:foregroundServiceType="health" in the manifest,
                // rather than relying on the manifest value alone. On
                // Android 14+ (Wear OS 4/5) a foreground service started
                // without a valid, permission-backed type throws a fatal
                // SecurityException — ServiceCompat.startForeground handles
                // that correctly across API levels (a no-op extra on older
                // OSes, required from API 29+, enforced from API 34).
                ServiceCompat.startForeground(
                    this@ExerciseSessionService,
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
                )
            } catch (e: Exception) {
                // Health Services can throw for all sorts of recoverable
                // reasons (e.g. a stale session left over from a previous
                // crash, a sensor briefly unavailable) — none of that should
                // crash the app. Sensor-based smash tracking is independent
                // of this and keeps working either way.
                Log.w(TAG, "startExercise failed, HR/calories won't be tracked this session", e)
            }
        }
    }

    private fun stopExercise() {
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
        // Unregistering an already-unregistered listener is a harmless
        // no-op, so this covers both isTracking and isCalibrating (and
        // "neither") without needing to branch on which one was active.
        sensorManager.unregisterListener(this)
        if (isTracking) releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        mlClassifier?.close()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Builds the "session running" foreground-service notification and, via
     * the Ongoing Activity API, wires it up to also show a tappable icon at
     * the bottom of the watch face and an entry in the Recents launcher —
     * both one tap back into MainActivity — for as long as this
     * notification stays posted. stopExercise()'s stopForeground(
     * STOP_FOREGROUND_REMOVE) cancels the notification, which is also what
     * ends the Ongoing Activity; nothing extra to tear down there.
     * https://developer.android.com/training/wearables/notifications/ongoing-activity
     */
    private fun buildNotification(): Notification {
        val channelId = "exercise_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Badminton session", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        // Reused as both the notification's own tap target and the Ongoing
        // Activity's touch intent (the watch-face icon and the Recents
        // entry), so all three ways back into the app land in the same
        // place. FLAG_ACTIVITY_NEW_TASK because this PendingIntent is fired
        // from outside any activity's own task (the watch face, Recents, or
        // the notification shade).
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Badminton session running")
            .setSmallIcon(R.drawable.ic_ongoing_badminton)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)

        // Session length shown in the Recents launcher as a live-updating
        // stopwatch — SystemClock.elapsedRealtime() rather than
        // currentTimeMillis(), per the API's own guidance, since elapsed
        // realtime can't jump backwards/forwards if the wall clock changes
        // mid-session. The system renders and ticks this itself; nothing
        // here needs to update it manually.
        val status = Status.Builder()
            .addTemplate("Tracking \u00b7 #time#")
            .addPart("time", Status.StopwatchPart(SystemClock.elapsedRealtime()))
            .build()

        val ongoingActivity = OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, notificationBuilder)
            .setStaticIcon(Icon.createWithResource(this, R.drawable.ic_ongoing_badminton))
            .setTouchIntent(openAppPendingIntent)
            .setStatus(status)
            .build()
        // Mutates notificationBuilder in place to carry the Ongoing
        // Activity data; must happen before .build() below.
        ongoingActivity.apply(applicationContext)

        return notificationBuilder.build()
    }

    companion object {
        private const val NOTIFICATION_ID = 42
        private const val TAG = "ExerciseSessionService"

        // Safety-valve timeout for the wake lock — see acquireWakeLock().
        private const val MAX_WAKE_LOCK_DURATION_MILLIS = 3 * 60 * 60 * 1000L // 3 hours

        /**
         * Safety cap on Shot Log entries per session — watch storage is
         * tighter than the phone's (see SessionHistoryStore.kt / SessionDatabase.kt).
         * A real session rarely exceeds a few hundred shots; this just
         * stops a pathologically long recording (e.g. left running by
         * mistake) from growing the local database and the phone-sync
         * payload without bound. Aggregate counts (smashCount etc.) keep
         * counting past this cap regardless — only the detailed log stops
         * growing.
         */
        const val MAX_LOGGED_SHOTS_PER_SESSION = 1000
    }
}
