package com.example.badmintontracker

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.foundation.AmbientMode
import androidx.wear.compose.foundation.AmbientTickEffect
import androidx.wear.compose.foundation.LocalAmbientModeManager
import androidx.wear.compose.foundation.rememberAmbientModeManager
import androidx.wear.compose.material.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// ---------------------------------------------------------------------
// Samsung-ish accent palette — deep Galaxy blue + Samsung Health-style
// orange/teal/red accents, dark surface (matches One UI Watch's default
// dark theme). Tweak these freely to match your own watch face colors.
// ---------------------------------------------------------------------
private val SamsungBlue = Color(0xFF1B63D6)
private val SamsungOrange = Color(0xFFFF7A1A)
private val SamsungTeal = Color(0xFF00BFA6)
private val SamsungRed = Color(0xFFE53950)
private val SamsungPurple = Color(0xFF8E5CE0)

private val BadmintonWearColors = Colors(
    primary = SamsungBlue,
    primaryVariant = SamsungBlue.copy(alpha = 0.7f),
    secondary = SamsungOrange,
    background = Color.Black,
    surface = Color(0xFF1C1C1E),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White
)

class MainActivity : ComponentActivity(), SensorEventListener {

    // Sensor access here is now used only by the Arm Balance rep counter — a
    // short, foreground-only interaction. Live shot detection AND the
    // calibration wizard's reference-swing capture both live in
    // ExerciseSessionService instead (see its class doc for why): a Wear OS
    // Activity's onPause() fires as soon as the screen dims or the wrist
    // drops, which used to kill shot tracking (and could just as easily kill
    // a calibration capture mid-wait) within seconds via this same
    // SensorManager.
    private lateinit var sensorManager: SensorManager
    private var accelSensor: Sensor? = null
    private var usingMlModel by mutableStateOf(false)

    private var exerciseService: ExerciseSessionService? = null
    private var isBound = false

    private var isTracking by mutableStateOf(false)
    private var smashCount by mutableStateOf(0)
    private var clearCount by mutableStateOf(0)
    private var dropCount by mutableStateOf(0)
    private var serveCount by mutableStateOf(0)
    private var bestSpeedKph by mutableStateOf(0f)
    private var lastSpeedKph by mutableStateOf(0f)
    private var rallyCount by mutableStateOf(0)
    private var longestRally by mutableStateOf(0)
    private var heartRate by mutableStateOf(0.0)
    private var calories by mutableStateOf(0.0)
    private var history by mutableStateOf(listOf<SessionRecord>())
    private var showHistory by mutableStateOf(false)

    // --- Calibration wizard state ---------------------------------------
    private var isCalibrating by mutableStateOf(false) // true only while actively listening for a reference swing
    private var showCalibration by mutableStateOf(false)
    private var calibrationStep by mutableStateOf(CalibrationStep.INTRO)
    private var calibrationSoftSpeed by mutableStateOf(80f)
    private var calibrationHardSpeed by mutableStateOf(250f)
    private var calibrationResult by mutableStateOf<SpeedCalibration?>(null)
    private var hasCalibration by mutableStateOf(false) // reflects a saved, real (non-factory) calibration
    private var calibrationSession = CalibrationSession(triggerThreshold = SmashDetector.DEFAULT_TRIGGER_THRESHOLD)

    // --- Arm Balance / conditioning tracking state ----------------------
    // See StrengthTracking.kt for why this measures reps + relative power
    // from the accelerometer rather than the Watch's BIA sensor.
    private val repCounter = RepCounter()
    private var isLoggingSet by mutableStateOf(false) // true only while actively counting reps for a set
    private var showStrength by mutableStateOf(false)
    private var strengthMode by mutableStateOf(StrengthMode.MENU)
    private var selectedArm by mutableStateOf(Arm.DOMINANT)
    private var selectedWeightKg by mutableStateOf(4f)
    private var currentReps by mutableStateOf(0)
    private var conditioningSets by mutableStateOf(listOf<ConditioningSet>())
    private var bodyCompEntries by mutableStateOf(listOf<BodyCompositionEntry>())
    private var bodyCompInput by mutableStateOf(30f)

    // --- Heart Rate Recovery / live shot-tracking display state ----------
    // The actual HeartRateRecoveryTracker and shot-detection pipeline now
    // live in ExerciseSessionService (so they survive the screen turning
    // off) — this is just the Activity's mirror of the service's
    // trackingState flow, for Compose to render. See onServiceConnected.
    private var lastRecoveryPoint by mutableStateOf<RallyRecoveryPoint?>(null) // most recently completed rest window, for the live stat

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as ExerciseSessionService.LocalBinder
            exerciseService = localBinder.getService()
            isBound = true
            usingMlModel = exerciseService?.hasMlModel ?: false
            lifecycleScope.launch {
                exerciseService?.metrics?.collect { metrics ->
                    heartRate = metrics.heartRateBpm
                    calories = metrics.caloriesKcal
                }
            }
            lifecycleScope.launch {
                exerciseService?.trackingState?.collect { state ->
                    smashCount = state.smashCount
                    clearCount = state.clearCount
                    dropCount = state.dropCount
                    serveCount = state.serveCount
                    bestSpeedKph = state.bestSpeedKph
                    lastSpeedKph = state.lastSpeedKph
                    rallyCount = state.rallyCount
                    longestRally = state.longestRally
                    lastRecoveryPoint = state.lastRecoveryPoint
                }
            }
            // Calibration reference-swing capture now runs in the service
            // (see its class doc) — this just relays each captured swing
            // back into the wizard's own state machine below.
            lifecycleScope.launch {
                exerciseService?.calibrationSwingCaptured?.collect { peakAccelMagnitude ->
                    onCalibrationSwingCaptured(peakAccelMagnitude)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            exerciseService = null
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)

        requestNeededPermissions()

        // Room forbids querying on the main thread by default, so this is
        // dispatched to IO like SessionHistoryStore.save() already is
        // (see toggleTracking()) — `history` starts empty and the History
        // screen recomposes the moment this coroutine finishes, rather than
        // blocking app launch on a database read.
        lifecycleScope.launch(Dispatchers.IO) {
            val loaded = SessionHistoryStore.loadAll(this@MainActivity)
            history = loaded
        }

        // Just the "is there a saved calibration" flag for the UI — the
        // calibration line itself is loaded and applied by
        // ExerciseSessionService, which owns the live SmashDetector now.
        hasCalibration = SpeedCalibrationStore.load(this).isCalibrated

        conditioningSets = StrengthTrackingStore.loadSets(this)
        bodyCompEntries = StrengthTrackingStore.loadBodyComposition(this)

        Intent(this, ExerciseSessionService::class.java).also {
            bindService(it, connection, Context.BIND_AUTO_CREATE)
        }

        handleAutoStartIntent(intent)

        setContent {
            // Provided once at the top of the Compose tree (the documented
            // "production app" pattern — see LocalAmbientModeManager's own
            // KDoc) rather than re-created per-screen, and requires nothing
            // else: rememberAmbientModeManager() finds this Activity via
            // LocalActivity itself and enables always-on for it.
            val ambientModeManager = rememberAmbientModeManager()
            CompositionLocalProvider(LocalAmbientModeManager provides ambientModeManager) {
                // The calibration wizard needs the screen on for however
                // long it takes the user to actually swing — Wear OS's
                // default screen timeout can be well under that, which used
                // to silently abort the capture. (Capture itself now
                // survives that regardless, since it moved into
                // ExerciseSessionService — see its class doc — but the user
                // still needs to be able to see the wizard's prompts.)
                LaunchedEffect(isCalibrating) {
                    if (isCalibrating) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                if (ambientModeManager.currentAmbientMode is AmbientMode.Ambient) {
                    AmbientBadmintonScreen(
                        isTracking = isTracking,
                        liveRallyCount = rallyCount,
                        liveHeartRate = heartRate
                    )
                } else {
                    BadmintonTrackerScreen(
                        isTracking = isTracking,
                        smashCount = smashCount,
                        clearCount = clearCount,
                        dropCount = dropCount,
                        serveCount = serveCount,
                        bestSpeedKph = bestSpeedKph,
                        lastSpeedKph = lastSpeedKph,
                        rallyCount = rallyCount,
                        longestRally = longestRally,
                        heartRate = heartRate,
                        calories = calories,
                        history = history,
                        showHistory = showHistory,
                        usingMlModel = usingMlModel,
                        lastRecoveryPoint = lastRecoveryPoint,
                        hasCalibration = hasCalibration,
                        showCalibration = showCalibration,
                        calibrationStep = calibrationStep,
                        calibrationSoftSpeed = calibrationSoftSpeed,
                        calibrationHardSpeed = calibrationHardSpeed,
                        calibrationResult = calibrationResult,
                        showStrength = showStrength,
                        strengthMode = strengthMode,
                        selectedArm = selectedArm,
                        selectedWeightKg = selectedWeightKg,
                        currentReps = currentReps,
                        conditioningSets = conditioningSets,
                        bodyCompEntries = bodyCompEntries,
                        bodyCompInput = bodyCompInput,
                        onStartStop = ::toggleTracking,
                        onReset = ::resetSession,
                        onToggleHistory = ::onToggleHistory,
                        onToggleCalibration = ::onToggleCalibration,
                        onStartCalibrationCapture = ::onStartCalibrationCapture,
                        onAdjustSoftSpeed = ::onAdjustSoftSpeed,
                        onAdjustHardSpeed = ::onAdjustHardSpeed,
                        onConfirmSoftSpeed = ::onConfirmSoftSpeed,
                        onConfirmHardSpeed = ::onConfirmHardSpeed,
                        onRetryHardSwing = ::onRetryHardSwing,
                        onFinishCalibration = ::onFinishCalibration,
                        onToggleStrength = ::onToggleStrength,
                        onSelectArm = ::onSelectArm,
                        onAdjustWeight = ::onAdjustWeight,
                        onOpenSetSetup = ::onOpenSetSetup,
                        onStartSet = ::onStartSet,
                        onFinishSet = ::onFinishSet,
                        onCancelSet = ::onCancelSet,
                        onStartLogBodyComp = ::onStartLogBodyComp,
                        onAdjustBodyComp = ::onAdjustBodyComp,
                        onSaveBodyComp = ::onSaveBodyComp,
                        onViewBalance = ::onViewBalance,
                        onBackToStrengthMenu = ::onBackToStrengthMenu
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // MainActivity is launchMode="singleTask" (see AndroidManifest.xml) precisely so
        // a tile tap while the app is already open lands here instead of spawning a
        // second instance that would double-bind ExerciseSessionService and
        // double-register its sensor listeners.
        setIntent(intent)
        handleAutoStartIntent(intent)
    }

    /**
     * Starts tracking immediately when launched from StartSessionTileService's
     * "Start session" tile — the whole point of the tile is skipping the extra
     * "open app, then tap Start" step once you're already on court.
     *
     * Guarded by `!isTracking` so tapping the tile while a session is already
     * running can't accidentally flip it to Stop. The extra is then stripped
     * from the stored intent so a later process-death recreation — which
     * Android replays using this same original launch intent — can't
     * silently auto-start a new session on its own.
     */
    private fun handleAutoStartIntent(intent: Intent) {
        if (intent.getBooleanExtra(EXTRA_AUTO_START, false)) {
            intent.putExtra(EXTRA_AUTO_START, false)
            if (!isTracking) {
                toggleTracking()
            }
        }
    }

    private fun requestNeededPermissions() {
        val permissions = mutableListOf(Manifest.permission.BODY_SENSORS)
        // Declared in AndroidManifest.xml and needed by Health Services'
        // exercise tracking, but it's a dangerous permission on API 29+ and
        // was never actually requested here before — without this,
        // ExerciseClient can silently fail to report data on some devices.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun toggleTracking() {
        if (isTracking) {
            // Stopping: snapshot the shot log/recovery points before the
            // service resets them, stop shot detection + the Health
            // Services session, save this session locally so it survives
            // closing the app, and push a summary over to the phone app too.
            val sessionShotLog = exerciseService?.snapshotShotLog() ?: emptyList()
            val recoverySummary = summarizeRecovery(exerciseService?.snapshotRecoveryPoints() ?: emptyList())
            exerciseService?.stopTracking()
            // Computed once so the locally-saved record and the copy synced
            // to the phone refer to the exact same session. Previously each
            // side stamped its own System.currentTimeMillis() a few lines
            // apart, so "the same" session could end up with two slightly
            // different timestamps — now also load-bearing, since the synced
            // DataItem's path and the phone's dedupe check both key off this.
            val sessionTimestamp = System.currentTimeMillis()
            if (smashCount + clearCount + dropCount > 0) {
                val record = SessionRecord(
                    timestamp = sessionTimestamp,
                    smashCount = smashCount,
                    clearCount = clearCount,
                    dropCount = dropCount,
                    serveCount = serveCount,
                    bestSpeedKph = bestSpeedKph,
                    rallyCount = rallyCount,
                    longestRally = longestRally,
                    avgHeartRate = heartRate,
                    calories = calories,
                    avgRecoveryBpm = recoverySummary?.avgRecoveryBpm ?: 0.0,
                    shots = sessionShotLog
                )
                // history (Compose state, drives the UI) updates immediately —
                // it doesn't need to wait on the disk write below. The write
                // itself now uses commit() (see SessionHistoryStore.save's doc
                // comment), which blocks, so it's dispatched to IO rather than
                // run inline here on the main thread.
                history = listOf(record) + history
                lifecycleScope.launch(Dispatchers.IO) {
                    SessionHistoryStore.save(this@MainActivity, record)
                }
            }
            lifecycleScope.launch {
                WatchToPhoneSync.sendSessionSummary(
                    context = this@MainActivity,
                    timestamp = sessionTimestamp,
                    smashCount = smashCount,
                    clearCount = clearCount,
                    dropCount = dropCount,
                    serveCount = serveCount,
                    bestSpeedKph = bestSpeedKph,
                    rallyCount = rallyCount,
                    longestRally = longestRally,
                    avgHeartRate = heartRate,
                    calories = calories,
                    avgRecoveryBpm = recoverySummary?.avgRecoveryBpm ?: 0.0,
                    shots = sessionShotLog
                )
            }
        } else {
            // Every new session starts from zero — otherwise counts left
            // over from a previous Start/Stop cycle (if the user never hit
            // "Reset") would keep accumulating and get saved/synced as part
            // of what looks like a brand-new session. ExerciseSessionService
            // resets its own counters/shot log/rally state as part of
            // startTracking(); resetSession() below just clears the
            // Activity's own mirrored display state for an instant UI reset.
            resetSession()
            exerciseService?.startTracking()
        }
        isTracking = !isTracking
    }

    // The counterpart shot log / rally / recovery state lives in
    // ExerciseSessionService and is reset there by startTracking() — this
    // just clears the Activity's own mirrored Compose state so the UI
    // reflects zero immediately, without waiting on the trackingState flow.
    private fun resetSession() {
        smashCount = 0
        clearCount = 0
        dropCount = 0
        serveCount = 0
        bestSpeedKph = 0f
        lastSpeedKph = 0f
        rallyCount = 0
        longestRally = 0
        // Also clear stale heart-rate/calories from a previous session so
        // the live tiles don't show old numbers until Health Services
        // reports fresh values for the new session.
        heartRate = 0.0
        calories = 0.0
        lastRecoveryPoint = null
    }

    // Live shot detection AND calibration reference-swing capture both live
    // in ExerciseSessionService now (see its class doc). The Activity's own
    // SensorEventListener registration is only ever used for the Arm
    // Balance rep counter below.
    override fun onSensorChanged(event: SensorEvent) {
        if (isLoggingSet) {
            handleStrengthSensorEvent(event)
        }
    }

    // --- Calibration wizard -----------------------------------------------
    // The actual sensor listening + SmashDetector now live in
    // ExerciseSessionService (calibrationDetector there) — see its class
    // doc for why. This just drives the wizard's own state machine from
    // the swings it reports back via calibrationSwingCaptured (collected in
    // onServiceConnected above).

    private fun onCalibrationSwingCaptured(peakAccelMagnitude: Float) {
        when (calibrationStep) {
            CalibrationStep.CAPTURING_SOFT -> {
                calibrationSession.recordSoftSwing(peakAccelMagnitude)
                stopCalibrationCapture()
                calibrationStep = CalibrationStep.ENTER_SOFT_SPEED
            }
            CalibrationStep.CAPTURING_HARD -> {
                calibrationSession.recordHardSwing(peakAccelMagnitude)
                stopCalibrationCapture()
                calibrationStep = CalibrationStep.ENTER_HARD_SPEED
            }
            else -> { /* a stray sensor event outside an active capture step — ignore it */ }
        }
    }

    private fun startCalibrationCapture() {
        isCalibrating = true
        exerciseService?.startCalibrationCapture()
    }

    private fun stopCalibrationCapture() {
        isCalibrating = false
        exerciseService?.stopCalibrationCapture()
    }

    private fun onToggleCalibration() {
        if (showCalibration) {
            if (isCalibrating) stopCalibrationCapture()
            showCalibration = false
        } else {
            if (isTracking) return // gated in the UI too; double-guarded here
            showHistory = false
            if (isLoggingSet) stopStrengthCapture()
            showStrength = false
            calibrationStep = CalibrationStep.INTRO
            calibrationSession = CalibrationSession(triggerThreshold = SmashDetector.DEFAULT_TRIGGER_THRESHOLD)
            calibrationSoftSpeed = 80f
            calibrationHardSpeed = 250f
            calibrationResult = null
            showCalibration = true
        }
    }

    private fun onStartCalibrationCapture() {
        calibrationStep = CalibrationStep.CAPTURING_SOFT
        startCalibrationCapture()
    }

    private fun onAdjustSoftSpeed(delta: Float) {
        calibrationSoftSpeed = (calibrationSoftSpeed + delta).coerceIn(10f, 400f)
    }

    private fun onAdjustHardSpeed(delta: Float) {
        calibrationHardSpeed = (calibrationHardSpeed + delta).coerceIn(10f, 400f)
    }

    private fun onConfirmSoftSpeed() {
        calibrationSession.recordSoftSpeed(calibrationSoftSpeed)
        calibrationStep = CalibrationStep.CAPTURING_HARD
        startCalibrationCapture()
    }

    private fun onConfirmHardSpeed() {
        calibrationSession.recordHardSpeed(calibrationHardSpeed)
        val result = calibrationSession.solve()
        if (result == null) {
            // Too close in raw power to fit a sensible line — ask for the
            // hard swing again rather than saving something degenerate.
            calibrationStep = CalibrationStep.ERROR_TOO_SIMILAR
            return
        }
        SpeedCalibrationStore.save(this, result)
        exerciseService?.updateCalibration(result.slope, result.intercept)
        hasCalibration = true
        calibrationResult = result
        calibrationStep = CalibrationStep.DONE
    }

    private fun onRetryHardSwing() {
        calibrationStep = CalibrationStep.CAPTURING_HARD
        startCalibrationCapture()
    }

    private fun onFinishCalibration() {
        showCalibration = false
    }

    private fun onToggleHistory() {
        if (!showHistory) {
            if (isCalibrating) stopCalibrationCapture()
            showCalibration = false
            if (isLoggingSet) stopStrengthCapture()
            showStrength = false
        }
        showHistory = !showHistory
    }

    // --- Arm Balance / conditioning tracking -----------------------------
    // Routed through RepCounter + isLoggingSet rather than the live
    // `detector`, same reasoning as calibration above: a conditioning rep
    // shouldn't touch smash/clear/drop counts, the rally tracker, or
    // bestSpeedKph. Gated to only be reachable while !isTracking && !isCalibrating.

    private fun handleStrengthSensorEvent(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_LINEAR_ACCELERATION) return
        if (repCounter.onAccelSample(System.currentTimeMillis(), event.values[0], event.values[1], event.values[2])) {
            currentReps = repCounter.repCount
        }
    }

    private fun startStrengthCapture() {
        isLoggingSet = true
        // Rep counting only needs the accelerometer, unlike smash detection
        // which also uses the gyroscope for rotation.
        accelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    private fun stopStrengthCapture() {
        isLoggingSet = false
        sensorManager.unregisterListener(this)
    }

    private fun onToggleStrength() {
        if (showStrength) {
            if (isLoggingSet) stopStrengthCapture()
            showStrength = false
        } else {
            if (isTracking || isCalibrating) return // gated in the UI too; double-guarded here
            showHistory = false
            showCalibration = false
            strengthMode = StrengthMode.MENU
            showStrength = true
        }
    }

    private fun onSelectArm(arm: Arm) {
        selectedArm = arm
    }

    private fun onAdjustWeight(delta: Float) {
        selectedWeightKg = (selectedWeightKg + delta).coerceIn(1f, 40f)
    }

    private fun onOpenSetSetup() {
        strengthMode = StrengthMode.SET_SETUP
    }

    private fun onStartSet() {
        repCounter.reset()
        currentReps = 0
        strengthMode = StrengthMode.LOGGING_SET
        startStrengthCapture()
    }

    private fun onFinishSet() {
        stopStrengthCapture()
        if (repCounter.repCount > 0) {
            val set = ConditioningSet(
                timestamp = System.currentTimeMillis(),
                arm = selectedArm,
                weightKg = selectedWeightKg,
                reps = repCounter.repCount,
                avgPeakAccel = repCounter.averagePeak
            )
            StrengthTrackingStore.saveSet(this, set)
            conditioningSets = listOf(set) + conditioningSets
        }
        strengthMode = StrengthMode.SET_SUMMARY
    }

    private fun onCancelSet() {
        stopStrengthCapture()
        strengthMode = StrengthMode.MENU
    }

    private fun onStartLogBodyComp() {
        // Default to the last reading if there is one, rather than always
        // resetting to a generic starting value.
        bodyCompInput = bodyCompEntries.firstOrNull()?.skeletalMuscleMassKg ?: 30f
        strengthMode = StrengthMode.LOG_BODY_COMP
    }

    private fun onAdjustBodyComp(delta: Float) {
        bodyCompInput = (bodyCompInput + delta).coerceIn(10f, 60f)
    }

    private fun onSaveBodyComp() {
        val entry = BodyCompositionEntry(timestamp = System.currentTimeMillis(), skeletalMuscleMassKg = bodyCompInput)
        StrengthTrackingStore.saveBodyComposition(this, entry)
        bodyCompEntries = listOf(entry) + bodyCompEntries
        strengthMode = StrengthMode.MENU
    }

    private fun onViewBalance() {
        strengthMode = StrengthMode.VIEW_BALANCE
    }

    private fun onBackToStrengthMenu() {
        strengthMode = StrengthMode.MENU
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // Only ever unregisters/re-registers the Activity's OWN listener — used
    // for the Arm Balance rep counter, a foreground-only interaction. Live
    // shot tracking and calibration capture both run in
    // ExerciseSessionService and are completely unaffected by the
    // Activity's lifecycle, which is the whole point of that design.
    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onResume() {
        super.onResume()
        if (isLoggingSet) {
            accelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    companion object {
        /** Set by StartSessionTileService's launch action. See handleAutoStartIntent(). */
        const val EXTRA_AUTO_START = "com.example.badmintontracker.EXTRA_AUTO_START"
    }
}

// ---------------------------------------------------------------------
// Ambient mode — a deliberately minimal screen, not just a dimmed copy of
// BadmintonTrackerScreen. Per the Ambient Mode guidelines: mostly black,
// no fine detail or solid color blocks, and values that could go stale
// between the system's infrequent ambient redraws (heart rate updates
// roughly every second while tracking) are snapshotted rather than shown
// live — see the LaunchedEffect/AmbientTickEffect below — so what's on
// screen is never quietly out of date by minutes.
// ---------------------------------------------------------------------
@Composable
fun AmbientBadmintonScreen(
    isTracking: Boolean,
    liveRallyCount: Int,
    liveHeartRate: Double
) {
    val ambientModeManager = LocalAmbientModeManager.current

    var snapshotRallyCount by remember { mutableStateOf(liveRallyCount) }
    var snapshotHeartRate by remember { mutableStateOf(liveHeartRate) }
    LaunchedEffect(Unit) {
        snapshotRallyCount = liveRallyCount
        snapshotHeartRate = liveHeartRate
    }
    // Refreshes roughly once a minute while ambient, per the guidelines —
    // deliberately not on every liveRallyCount/liveHeartRate change, which
    // would just repaint bright pixels on the exact cadence ambient mode
    // exists to avoid.
    if (ambientModeManager != null) {
        ambientModeManager.AmbientTickEffect {
            snapshotRallyCount = liveRallyCount
            snapshotHeartRate = liveHeartRate
        }
    }

    MaterialTheme(colors = BadmintonWearColors) {
        Scaffold(timeText = { TimeText() }) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isTracking) {
                    Text(
                        "RALLY",
                        style = MaterialTheme.typography.caption2,
                        color = Color.White.copy(alpha = 0.5f)
                    )
                    Text(
                        "$snapshotRallyCount",
                        style = MaterialTheme.typography.display1,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        if (snapshotHeartRate > 0) "${snapshotHeartRate.roundToInt()} bpm" else "-- bpm",
                        style = MaterialTheme.typography.title3,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                } else {
                    Text("🏸", style = MaterialTheme.typography.display2, color = Color.White.copy(alpha = 0.6f))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------
// UI — Samsung One UI Watch-ish layout: scrollable round-friendly list,
// TimeText at the top like every Galaxy Watch face, a big glanceable
// speed card, emoji stat tiles instead of plain icons, colorful chips.
//
// NOTE: Card/Chip parameter names have shifted a little across
// compose-material versions — if Android Studio flags one, autocomplete
// will show the current signature, it's a small fix.
// ---------------------------------------------------------------------
@Composable
fun BadmintonTrackerScreen(
    isTracking: Boolean,
    smashCount: Int,
    clearCount: Int,
    dropCount: Int,
    serveCount: Int,
    bestSpeedKph: Float,
    lastSpeedKph: Float,
    rallyCount: Int,
    longestRally: Int,
    heartRate: Double,
    calories: Double,
    history: List<SessionRecord>,
    showHistory: Boolean,
    usingMlModel: Boolean,
    lastRecoveryPoint: RallyRecoveryPoint?,
    hasCalibration: Boolean,
    showCalibration: Boolean,
    calibrationStep: CalibrationStep,
    calibrationSoftSpeed: Float,
    calibrationHardSpeed: Float,
    calibrationResult: SpeedCalibration?,
    showStrength: Boolean,
    strengthMode: StrengthMode,
    selectedArm: Arm,
    selectedWeightKg: Float,
    currentReps: Int,
    conditioningSets: List<ConditioningSet>,
    bodyCompEntries: List<BodyCompositionEntry>,
    bodyCompInput: Float,
    onStartStop: () -> Unit,
    onReset: () -> Unit,
    onToggleHistory: () -> Unit,
    onToggleCalibration: () -> Unit,
    onStartCalibrationCapture: () -> Unit,
    onAdjustSoftSpeed: (Float) -> Unit,
    onAdjustHardSpeed: (Float) -> Unit,
    onConfirmSoftSpeed: () -> Unit,
    onConfirmHardSpeed: () -> Unit,
    onRetryHardSwing: () -> Unit,
    onFinishCalibration: () -> Unit,
    onToggleStrength: () -> Unit,
    onSelectArm: (Arm) -> Unit,
    onAdjustWeight: (Float) -> Unit,
    onOpenSetSetup: () -> Unit,
    onStartSet: () -> Unit,
    onFinishSet: () -> Unit,
    onCancelSet: () -> Unit,
    onStartLogBodyComp: () -> Unit,
    onAdjustBodyComp: (Float) -> Unit,
    onSaveBodyComp: () -> Unit,
    onViewBalance: () -> Unit,
    onBackToStrengthMenu: () -> Unit
) {
    val listState = rememberScalingLazyListState()

    MaterialTheme(colors = BadmintonWearColors) {
        Scaffold(
            timeText = { TimeText() },
            vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
            positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
        ) {
            if (showStrength) {
                StrengthScreen(
                    mode = strengthMode,
                    selectedArm = selectedArm,
                    selectedWeightKg = selectedWeightKg,
                    currentReps = currentReps,
                    conditioningSets = conditioningSets,
                    bodyCompEntries = bodyCompEntries,
                    bodyCompInput = bodyCompInput,
                    listState = listState,
                    onSelectArm = onSelectArm,
                    onAdjustWeight = onAdjustWeight,
                    onOpenSetSetup = onOpenSetSetup,
                    onStartSet = onStartSet,
                    onFinishSet = onFinishSet,
                    onCancelSet = onCancelSet,
                    onStartLogBodyComp = onStartLogBodyComp,
                    onAdjustBodyComp = onAdjustBodyComp,
                    onSaveBodyComp = onSaveBodyComp,
                    onViewBalance = onViewBalance,
                    onBack = onBackToStrengthMenu,
                    onClose = onToggleStrength
                )
                return@Scaffold
            }
            if (showCalibration) {
                CalibrationScreen(
                    step = calibrationStep,
                    softSpeed = calibrationSoftSpeed,
                    hardSpeed = calibrationHardSpeed,
                    calibrationResult = calibrationResult,
                    listState = listState,
                    onAdjustSoft = onAdjustSoftSpeed,
                    onAdjustHard = onAdjustHardSpeed,
                    onConfirmSoftSpeed = onConfirmSoftSpeed,
                    onConfirmHardSpeed = onConfirmHardSpeed,
                    onStartCapture = onStartCalibrationCapture,
                    onRetryHardSwing = onRetryHardSwing,
                    onFinish = onFinishCalibration,
                    onCancel = onToggleCalibration
                )
                return@Scaffold
            }
            if (showHistory) {
                HistoryScreen(history = history, listState = listState, onBack = onToggleHistory)
                return@Scaffold
            }
            ScalingLazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(top = 28.dp, bottom = 24.dp, start = 6.dp, end = 6.dp)
            ) {
                item {
                    Text("🏸 Smash Tracker", style = MaterialTheme.typography.title3)
                }

                item {
                    Text(
                        if (usingMlModel) "🧠 ML classifier" else "📏 rule-based classifier",
                        style = MaterialTheme.typography.caption3,
                        color = Color.White.copy(alpha = 0.55f)
                    )
                }

                // Calibration status is surfaced right here, next to the
                // classifier badge, rather than tucked in a settings menu —
                // an uncalibrated speed number looks exactly as confident as
                // a calibrated one otherwise, with nothing telling the user
                // it's still just a guess. Not shown mid-session since
                // calibration takes over the sensors (see onToggleCalibration).
                if (!isTracking) {
                    item {
                        Chip(
                            onClick = onToggleCalibration,
                            label = {
                                Text(
                                    if (hasCalibration) "✅ Calibrated — tap to redo" else "⚠️ Uncalibrated — tap to calibrate",
                                    style = MaterialTheme.typography.caption3
                                )
                            },
                            colors = if (hasCalibration) {
                                ChipDefaults.secondaryChipColors()
                            } else {
                                ChipDefaults.chipColors(
                                    backgroundColor = SamsungOrange.copy(alpha = 0.85f),
                                    contentColor = Color.White
                                )
                            },
                            modifier = Modifier.fillMaxWidth(0.9f)
                        )
                    }
                    item {
                        Chip(
                            onClick = onToggleStrength,
                            label = { Text("💪 Arm Balance", style = MaterialTheme.typography.caption3) },
                            colors = ChipDefaults.secondaryChipColors(),
                            modifier = Modifier.fillMaxWidth(0.9f)
                        )
                    }
                }

                item { Spacer(Modifier.height(6.dp)) }

                item { SpeedCard(lastSpeedKph, bestSpeedKph, isTracking) }

                item { Spacer(Modifier.height(6.dp)) }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatTile("💥", smashCount.toString(), "Smash", SamsungOrange)
                        StatTile("↗\uFE0F", clearCount.toString(), "Clear", SamsungBlue)
                        StatTile("🪶", dropCount.toString(), "Drop", SamsungTeal)
                    }
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatTile("🎯", serveCount.toString(), "Serve", SamsungPurple)
                        StatTile("🔁", rallyCount.toString(), "Rallies", SamsungPurple)
                        StatTile("📈", longestRally.toString(), "Longest", SamsungPurple)
                    }
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatTile("❤\uFE0F", heartRate.roundToInt().toString(), "bpm", SamsungRed)
                        StatTile("🔥", calories.roundToInt().toString(), "kcal", SamsungOrange)
                    }
                }

                // Live per-rally recovery — the whole point of this metric is
                // seeing it rally-by-rally during the match, not just as a
                // post-session average (see HeartRateRecovery.kt).
                if (isTracking) {
                    lastRecoveryPoint?.let { point ->
                        item {
                            Text(
                                "❤\uFE0F‍🩹 recovered ${point.recoveryBpm.roundToInt()} bpm in " +
                                    "${"%.1f".format(point.restDurationMillis / 1000.0)}s",
                                style = MaterialTheme.typography.caption3,
                                color = SamsungRed,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                item { Spacer(Modifier.height(6.dp)) }

                item {
                    Chip(
                        onClick = onStartStop,
                        label = { Text(if (isTracking) "Stop" else "Start") },
                        icon = { Text(if (isTracking) "⏹\uFE0F" else "▶\uFE0F") },
                        colors = ChipDefaults.chipColors(
                            backgroundColor = if (isTracking) SamsungRed else SamsungBlue,
                            contentColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth(0.85f)
                    )
                }

                item {
                    Chip(
                        onClick = onReset,
                        label = { Text("Reset") },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(0.6f)
                    )
                }

                item {
                    Chip(
                        onClick = onToggleHistory,
                        label = { Text("History") },
                        icon = { Text("📜") },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(0.6f)
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryScreen(
    history: List<SessionRecord>,
    listState: ScalingLazyListState,
    onBack: () -> Unit
) {
    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(top = 28.dp, bottom = 24.dp, start = 6.dp, end = 6.dp)
    ) {
        item {
            Text("📜 History", style = MaterialTheme.typography.title3)
        }

        item { Spacer(Modifier.height(6.dp)) }

        if (history.isEmpty()) {
            item {
                Text(
                    "No past sessions yet",
                    style = MaterialTheme.typography.caption2,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        } else {
            items(history) { session -> HistoryRow(session) }
        }

        item { Spacer(Modifier.height(6.dp)) }

        item {
            Chip(
                onClick = onBack,
                label = { Text("Back") },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(0.6f)
            )
        }
    }
}

@Composable
private fun HistoryRow(session: SessionRecord) {
    val dateFormat = remember { SimpleDateFormat("dd MMM, h:mm a", Locale.getDefault()) }
    Card(
        onClick = { },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(8.dp).fillMaxWidth()
        ) {
            Text(
                dateFormat.format(Date(session.timestamp)),
                style = MaterialTheme.typography.caption2,
                color = Color.White.copy(alpha = 0.85f)
            )
            Text(
                "💥${session.smashCount} ↗\uFE0F${session.clearCount} 🪶${session.dropCount}  •  " +
                    "${session.bestSpeedKph.roundToInt()}kph",
                style = MaterialTheme.typography.caption3,
                color = Color.White
            )
            Text(
                "🎯${session.serveCount} serves  •  🔁${session.rallyCount} rallies  •  " +
                    "longest ${session.longestRally}",
                style = MaterialTheme.typography.caption3,
                color = SamsungPurple
            )
            if (session.avgRecoveryBpm > 0.0) {
                Text(
                    "❤\uFE0F‍🩹 avg recovery ${session.avgRecoveryBpm.roundToInt()} bpm",
                    style = MaterialTheme.typography.caption3,
                    color = SamsungRed
                )
            }
        }
    }
}

// ---------------------------------------------------------------------
// Calibration wizard — two reference swings (soft, then as hard as you
// can), a real speed typed in for each, and CalibrationSession.solve()
// turns that into the line SmashDetector converts every future swing with.
// See SpeedCalibration.kt for the module this screen is driven by.
// ---------------------------------------------------------------------
@Composable
private fun CalibrationScreen(
    step: CalibrationStep,
    softSpeed: Float,
    hardSpeed: Float,
    calibrationResult: SpeedCalibration?,
    listState: ScalingLazyListState,
    onAdjustSoft: (Float) -> Unit,
    onAdjustHard: (Float) -> Unit,
    onConfirmSoftSpeed: () -> Unit,
    onConfirmHardSpeed: () -> Unit,
    onStartCapture: () -> Unit,
    onRetryHardSwing: () -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit
) {
    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(top = 28.dp, bottom = 24.dp, start = 10.dp, end = 10.dp)
    ) {
        item { Text("🎯 Calibrate", style = MaterialTheme.typography.title3) }
        item { Spacer(Modifier.height(6.dp)) }

        when (step) {
            CalibrationStep.INTRO -> {
                item {
                    Text(
                        "Play a soft shot, then a hard one. Tell us the real speed for each — from a " +
                            "radar gun, a speed-gun app, or a known reference smash — and we'll fit a " +
                            "line for every swing after that.",
                        style = MaterialTheme.typography.caption2,
                        color = Color.White.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
                item {
                    Chip(
                        onClick = onStartCapture,
                        label = { Text("Start") },
                        colors = ChipDefaults.chipColors(backgroundColor = SamsungBlue, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth(0.75f)
                    )
                }
                item {
                    Chip(
                        onClick = onCancel,
                        label = { Text("Not now") },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(0.6f)
                    )
                }
            }

            CalibrationStep.CAPTURING_SOFT -> {
                item { Text("Swing 1 of 2", style = MaterialTheme.typography.caption1, color = SamsungBlue) }
                item {
                    Text(
                        "Play a SOFT shot — a gentle drop or half-power clear. Waiting for the sensor…",
                        style = MaterialTheme.typography.caption2,
                        color = Color.White.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
                item {
                    Chip(
                        onClick = onCancel,
                        label = { Text("Cancel") },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(0.6f)
                    )
                }
            }

            CalibrationStep.ENTER_SOFT_SPEED -> {
                item { Text("Swing 1 captured ✓", style = MaterialTheme.typography.caption1, color = SamsungTeal) }
                item {
                    Text(
                        "What was its real speed?",
                        style = MaterialTheme.typography.caption2,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
                item { Spacer(Modifier.height(4.dp)) }
                item { SpeedStepper(value = softSpeed, onAdjust = onAdjustSoft) }
                item { Spacer(Modifier.height(8.dp)) }
                item {
                    Chip(
                        onClick = onConfirmSoftSpeed,
                        label = { Text("Next: hard swing") },
                        colors = ChipDefaults.chipColors(backgroundColor = SamsungBlue, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth(0.85f)
                    )
                }
            }

            CalibrationStep.CAPTURING_HARD -> {
                item { Text("Swing 2 of 2", style = MaterialTheme.typography.caption1, color = SamsungOrange) }
                item {
                    Text(
                        "Now play a HARD shot — as strong a smash as you can. Waiting for the sensor…",
                        style = MaterialTheme.typography.caption2,
                        color = Color.White.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
                item {
                    Chip(
                        onClick = onCancel,
                        label = { Text("Cancel") },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(0.6f)
                    )
                }
            }

            CalibrationStep.ENTER_HARD_SPEED -> {
                item { Text("Swing 2 captured ✓", style = MaterialTheme.typography.caption1, color = SamsungTeal) }
                item {
                    Text(
                        "What was its real speed?",
                        style = MaterialTheme.typography.caption2,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
                item { Spacer(Modifier.height(4.dp)) }
                item { SpeedStepper(value = hardSpeed, onAdjust = onAdjustHard) }
                item { Spacer(Modifier.height(8.dp)) }
                item {
                    Chip(
                        onClick = onConfirmHardSpeed,
                        label = { Text("Calculate") },
                        colors = ChipDefaults.chipColors(backgroundColor = SamsungBlue, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth(0.75f)
                    )
                }
            }

            CalibrationStep.ERROR_TOO_SIMILAR -> {
                item {
                    Text(
                        "Those two swings were too close in power to tell apart. Try a bigger gap — " +
                            "really soft, then really hard.",
                        style = MaterialTheme.typography.caption2,
                        color = SamsungRed,
                        textAlign = TextAlign.Center
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
                item {
                    Chip(
                        onClick = onRetryHardSwing,
                        label = { Text("Retry hard swing") },
                        colors = ChipDefaults.chipColors(backgroundColor = SamsungOrange, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth(0.85f)
                    )
                }
                item {
                    Chip(
                        onClick = onCancel,
                        label = { Text("Cancel") },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(0.6f)
                    )
                }
            }

            CalibrationStep.DONE -> {
                item { Text("Calibrated ✓", style = MaterialTheme.typography.title3, color = SamsungTeal) }
                item { Spacer(Modifier.height(4.dp)) }
                item {
                    Text(
                        "Your swings now convert using your own numbers, not the factory guess.",
                        style = MaterialTheme.typography.caption2,
                        color = Color.White.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center
                    )
                }
                calibrationResult?.let { result ->
                    item { Spacer(Modifier.height(6.dp)) }
                    item {
                        Text(
                            "slope ${"%.2f".format(result.slope)} · intercept ${result.intercept.roundToInt()}",
                            style = MaterialTheme.typography.caption3,
                            color = Color.White.copy(alpha = 0.55f)
                        )
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
                item {
                    Chip(
                        onClick = onFinish,
                        label = { Text("Done") },
                        colors = ChipDefaults.chipColors(backgroundColor = SamsungBlue, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth(0.6f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeedStepper(value: Float, onAdjust: (Float) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${value.roundToInt()} kph", style = MaterialTheme.typography.display3, color = Color.White)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Chip(
                onClick = { onAdjust(-25f) },
                label = { Text("−25", style = MaterialTheme.typography.caption2) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.width(52.dp)
            )
            Chip(
                onClick = { onAdjust(-5f) },
                label = { Text("−5", style = MaterialTheme.typography.caption2) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.width(46.dp)
            )
            Chip(
                onClick = { onAdjust(5f) },
                label = { Text("+5", style = MaterialTheme.typography.caption2) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.width(46.dp)
            )
            Chip(
                onClick = { onAdjust(25f) },
                label = { Text("+25", style = MaterialTheme.typography.caption2) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.width(52.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------
// Arm Balance — conditioning-set logging, a manual body-composition log,
// and a side-by-side comparison. See StrengthTracking.kt for why this
// tracks reps + relative power from the accelerometer rather than the
// Watch's BIA sensor (which is whole-body only and not third-party
// accessible in the first place).
// ---------------------------------------------------------------------
@Composable
private fun StrengthScreen(
    mode: StrengthMode,
    selectedArm: Arm,
    selectedWeightKg: Float,
    currentReps: Int,
    conditioningSets: List<ConditioningSet>,
    bodyCompEntries: List<BodyCompositionEntry>,
    bodyCompInput: Float,
    listState: ScalingLazyListState,
    onSelectArm: (Arm) -> Unit,
    onAdjustWeight: (Float) -> Unit,
    onOpenSetSetup: () -> Unit,
    onStartSet: () -> Unit,
    onFinishSet: () -> Unit,
    onCancelSet: () -> Unit,
    onStartLogBodyComp: () -> Unit,
    onAdjustBodyComp: (Float) -> Unit,
    onSaveBodyComp: () -> Unit,
    onViewBalance: () -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit
) {
    ScalingLazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(top = 28.dp, bottom = 24.dp, start = 10.dp, end = 10.dp)
    ) {
        item { Text("💪 Arm Balance", style = MaterialTheme.typography.title3) }
        item { Spacer(Modifier.height(6.dp)) }

        when (mode) {
            StrengthMode.MENU -> {
                item {
                    Text(
                        "Tracks reps & relative swing power per arm from wrist motion. Watch 7's " +
                            "BIA gives one whole-body reading, not per-arm — log that separately below.",
                        style = MaterialTheme.typography.caption3,
                        color = Color.White.copy(alpha = 0.65f),
                        textAlign = TextAlign.Center
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
                item {
                    Chip(
                        onClick = onOpenSetSetup,
                        label = { Text("Log a set") },
                        colors = ChipDefaults.chipColors(backgroundColor = SamsungBlue, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth(0.85f)
                    )
                }
                item {
                    Chip(
                        onClick = onStartLogBodyComp,
                        label = { Text("Log body composition") },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(0.85f)
                    )
                }
                item {
                    Chip(
                        onClick = onViewBalance,
                        label = { Text("View balance") },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(0.85f)
                    )
                }
                item {
                    Chip(
                        onClick = onClose,
                        label = { Text("Back") },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(0.5f)
                    )
                }
            }

            StrengthMode.SET_SETUP -> {
                item { Text("Which arm?", style = MaterialTheme.typography.caption2, color = Color.White.copy(alpha = 0.85f)) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip(
                            onClick = { onSelectArm(Arm.DOMINANT) },
                            label = { Text("Racket arm", style = MaterialTheme.typography.caption2) },
                            colors = if (selectedArm == Arm.DOMINANT) {
                                ChipDefaults.chipColors(backgroundColor = SamsungBlue, contentColor = Color.White)
                            } else {
                                ChipDefaults.secondaryChipColors()
                            },
                            modifier = Modifier.width(90.dp)
                        )
                        Chip(
                            onClick = { onSelectArm(Arm.NON_DOMINANT) },
                            label = { Text("Off arm", style = MaterialTheme.typography.caption2) },
                            colors = if (selectedArm == Arm.NON_DOMINANT) {
                                ChipDefaults.chipColors(backgroundColor = SamsungBlue, contentColor = Color.White)
                            } else {
                                ChipDefaults.secondaryChipColors()
                            },
                            modifier = Modifier.width(90.dp)
                        )
                    }
                }
                item { Spacer(Modifier.height(6.dp)) }
                item { Text("Dumbbell weight", style = MaterialTheme.typography.caption2, color = Color.White.copy(alpha = 0.85f)) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip(
                            onClick = { onAdjustWeight(4f - selectedWeightKg) },
                            label = { Text("4 kg", style = MaterialTheme.typography.caption2) },
                            colors = if (abs(selectedWeightKg - 4f) < 0.01f) {
                                ChipDefaults.chipColors(backgroundColor = SamsungBlue, contentColor = Color.White)
                            } else {
                                ChipDefaults.secondaryChipColors()
                            },
                            modifier = Modifier.width(70.dp)
                        )
                        Chip(
                            onClick = { onAdjustWeight(5f - selectedWeightKg) },
                            label = { Text("5 kg", style = MaterialTheme.typography.caption2) },
                            colors = if (abs(selectedWeightKg - 5f) < 0.01f) {
                                ChipDefaults.chipColors(backgroundColor = SamsungBlue, contentColor = Color.White)
                            } else {
                                ChipDefaults.secondaryChipColors()
                            },
                            modifier = Modifier.width(70.dp)
                        )
                        Chip(
                            onClick = { onAdjustWeight(-1f) },
                            label = { Text("−1", style = MaterialTheme.typography.caption2) },
                            colors = ChipDefaults.secondaryChipColors(),
                            modifier = Modifier.width(46.dp)
                        )
                        Chip(
                            onClick = { onAdjustWeight(1f) },
                            label = { Text("+1", style = MaterialTheme.typography.caption2) },
                            colors = ChipDefaults.secondaryChipColors(),
                            modifier = Modifier.width(46.dp)
                        )
                    }
                }
                item {
                    Text(
                        "${selectedWeightKg.roundToInt()} kg selected",
                        style = MaterialTheme.typography.caption3,
                        color = Color.White.copy(alpha = 0.55f)
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
                item {
                    Chip(
                        onClick = onStartSet,
                        label = { Text("Start set") },
                        colors = ChipDefaults.chipColors(backgroundColor = SamsungBlue, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth(0.75f)
                    )
                }
                item {
                    Chip(
                        onClick = onBack,
                        label = { Text("Back") },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(0.5f)
                    )
                }
            }

            StrengthMode.LOGGING_SET -> {
                item {
                    Text(
                        if (selectedArm == Arm.DOMINANT) "Racket arm · ${selectedWeightKg.roundToInt()} kg" else "Off arm · ${selectedWeightKg.roundToInt()} kg",
                        style = MaterialTheme.typography.caption1,
                        color = SamsungBlue
                    )
                }
                item { Text("$currentReps", style = MaterialTheme.typography.display1, color = Color.White) }
                item {
                    Text(
                        "reps",
                        style = MaterialTheme.typography.caption2,
                        color = Color.White.copy(alpha = 0.65f)
                    )
                }
                item { Spacer(Modifier.height(10.dp)) }
                item {
                    Chip(
                        onClick = onFinishSet,
                        label = { Text("Finish set") },
                        colors = ChipDefaults.chipColors(backgroundColor = SamsungTeal, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth(0.75f)
                    )
                }
                item {
                    Chip(
                        onClick = onCancelSet,
                        label = { Text("Cancel") },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(0.5f)
                    )
                }
            }

            StrengthMode.SET_SUMMARY -> {
                val last = conditioningSets.firstOrNull()
                item { Text("Set logged ✓", style = MaterialTheme.typography.title3, color = SamsungTeal) }
                item { Spacer(Modifier.height(4.dp)) }
                if (last != null) {
                    item {
                        Text(
                            (if (last.arm == Arm.DOMINANT) "Racket arm" else "Off arm") +
                                " · ${last.weightKg.roundToInt()} kg · ${last.reps} reps",
                            style = MaterialTheme.typography.caption2,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
                item {
                    Chip(
                        onClick = onOpenSetSetup,
                        label = { Text("Log another") },
                        colors = ChipDefaults.chipColors(backgroundColor = SamsungBlue, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth(0.75f)
                    )
                }
                item {
                    Chip(
                        onClick = onBack,
                        label = { Text("Done") },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(0.5f)
                    )
                }
            }

            StrengthMode.LOG_BODY_COMP -> {
                item {
                    Text(
                        "Enter the skeletal muscle mass from your last Watch 7 Body Composition scan " +
                            "(Samsung Health app).",
                        style = MaterialTheme.typography.caption2,
                        color = Color.White.copy(alpha = 0.85f),
                        textAlign = TextAlign.Center
                    )
                }
                item { Spacer(Modifier.height(6.dp)) }
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "${"%.1f".format(bodyCompInput)} kg",
                            style = MaterialTheme.typography.display3,
                            color = Color.White
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Chip(
                                onClick = { onAdjustBodyComp(-1f) },
                                label = { Text("−1", style = MaterialTheme.typography.caption2) },
                                colors = ChipDefaults.secondaryChipColors(),
                                modifier = Modifier.width(46.dp)
                            )
                            Chip(
                                onClick = { onAdjustBodyComp(-0.1f) },
                                label = { Text("−0.1", style = MaterialTheme.typography.caption2) },
                                colors = ChipDefaults.secondaryChipColors(),
                                modifier = Modifier.width(56.dp)
                            )
                            Chip(
                                onClick = { onAdjustBodyComp(0.1f) },
                                label = { Text("+0.1", style = MaterialTheme.typography.caption2) },
                                colors = ChipDefaults.secondaryChipColors(),
                                modifier = Modifier.width(56.dp)
                            )
                            Chip(
                                onClick = { onAdjustBodyComp(1f) },
                                label = { Text("+1", style = MaterialTheme.typography.caption2) },
                                colors = ChipDefaults.secondaryChipColors(),
                                modifier = Modifier.width(46.dp)
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
                item {
                    Chip(
                        onClick = onSaveBodyComp,
                        label = { Text("Save") },
                        colors = ChipDefaults.chipColors(backgroundColor = SamsungBlue, contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth(0.6f)
                    )
                }
                item {
                    Chip(
                        onClick = onBack,
                        label = { Text("Cancel") },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(0.5f)
                    )
                }
            }

            StrengthMode.VIEW_BALANCE -> {
                val sinceMillis = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
                val balance = computeArmBalance(conditioningSets, sinceMillis)
                item { Text("Last 30 days", style = MaterialTheme.typography.caption1, color = SamsungBlue) }
                item {
                    Text(
                        "Racket arm: ${balance.dominantSets} sets · ${balance.dominantReps} reps",
                        style = MaterialTheme.typography.caption2,
                        color = Color.White
                    )
                }
                item {
                    Text(
                        "Off arm: ${balance.nonDominantSets} sets · ${balance.nonDominantReps} reps",
                        style = MaterialTheme.typography.caption2,
                        color = Color.White
                    )
                }
                item { Spacer(Modifier.height(4.dp)) }
                item {
                    val imbalance = balance.repImbalancePercent
                    val message = if (imbalance == null) {
                        "Log an off-arm set to see a comparison."
                    } else when {
                        imbalance > 0 -> "Racket arm is doing ${imbalance.roundToInt()}% more reps than the off arm."
                        imbalance < 0 -> "Off arm is doing ${(-imbalance).roundToInt()}% more reps than the racket arm."
                        else -> "Both arms are even on reps."
                    }
                    Text(
                        message,
                        style = MaterialTheme.typography.caption3,
                        color = SamsungOrange,
                        textAlign = TextAlign.Center
                    )
                }
                item { Spacer(Modifier.height(10.dp)) }
                item {
                    Text(
                        "Body composition (whole body, from Samsung Health)",
                        style = MaterialTheme.typography.caption3,
                        color = Color.White.copy(alpha = 0.65f),
                        textAlign = TextAlign.Center
                    )
                }
                if (bodyCompEntries.isEmpty()) {
                    item {
                        Text(
                            "No entries logged yet.",
                            style = MaterialTheme.typography.caption3,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                    }
                } else {
                    val latest = bodyCompEntries.first()
                    val oldest = bodyCompEntries.last()
                    item {
                        Text(
                            "Latest: ${"%.1f".format(latest.skeletalMuscleMassKg)} kg",
                            style = MaterialTheme.typography.caption2,
                            color = Color.White
                        )
                    }
                    if (bodyCompEntries.size > 1) {
                        val delta = latest.skeletalMuscleMassKg - oldest.skeletalMuscleMassKg
                        item {
                            Text(
                                (if (delta >= 0) "+" else "") + "${"%.1f".format(delta)} kg since first log",
                                style = MaterialTheme.typography.caption3,
                                color = if (delta >= 0) SamsungTeal else SamsungRed
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
                item {
                    Chip(
                        onClick = onBack,
                        label = { Text("Back") },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(0.5f)
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeedCard(lastSpeedKph: Float, bestSpeedKph: Float, isTracking: Boolean) {
    Card(
        onClick = { },
        backgroundPainter = CardDefaults.cardBackgroundPainter(
            startBackgroundColor = SamsungBlue.copy(alpha = 0.9f),
            endBackgroundColor = SamsungBlue.copy(alpha = 0.55f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = 10.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (lastSpeedKph > 0f) "${lastSpeedKph.roundToInt()}" else "--",
                style = MaterialTheme.typography.display2,
                color = Color.White
            )
            Text(
                text = "kph  •  best ${bestSpeedKph.roundToInt()}",
                style = MaterialTheme.typography.caption2,
                color = Color.White.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
private fun StatTile(emoji: String, value: String, label: String, accent: Color) {
    Card(
        onClick = { },
        backgroundPainter = CardDefaults.cardBackgroundPainter(
            startBackgroundColor = accent.copy(alpha = 0.30f),
            endBackgroundColor = accent.copy(alpha = 0.12f)
        ),
        modifier = Modifier.width(58.dp)
    ) {
        Column(
            modifier = Modifier.padding(6.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(emoji, fontSize = 15.sp)
            Text(value, style = MaterialTheme.typography.title3, color = accent)
            Text(label, style = MaterialTheme.typography.caption3, color = Color.White.copy(alpha = 0.7f))
        }
    }
}
