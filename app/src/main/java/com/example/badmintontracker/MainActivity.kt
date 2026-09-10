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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.material.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

    private lateinit var sensorManager: SensorManager
    private var accelSensor: Sensor? = null
    private var gyroSensor: Sensor? = null
    private val detector = SmashDetector()
    private val classifier = ShotClassifier()
    private val rallyTracker = RallyTracker()
    private val serveDetector = ServeDetector()
    private var mlClassifier: MLShotClassifier? = null

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

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as ExerciseSessionService.LocalBinder
            exerciseService = localBinder.getService()
            isBound = true
            lifecycleScope.launch {
                exerciseService?.metrics?.collect { metrics ->
                    heartRate = metrics.heartRateBpm
                    calories = metrics.caloriesKcal
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
        gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        requestNeededPermissions()

        // Null if shot_classifier.tflite isn't in assets/ yet — onSensorChanged
        // falls back to the rule-based ShotClassifier in that case.
        mlClassifier = MLShotClassifier.tryLoad(this)

        history = SessionHistoryStore.loadAll(this)

        Intent(this, ExerciseSessionService::class.java).also {
            bindService(it, connection, Context.BIND_AUTO_CREATE)
        }

        setContent {
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
                usingMlModel = mlClassifier != null,
                onStartStop = ::toggleTracking,
                onReset = ::resetSession,
                onToggleHistory = { showHistory = !showHistory }
            )
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
            // Stopping: unregister sensors, end the Health Services session,
            // save this session locally so it survives closing the app, and
            // push a summary over to the phone app too.
            sensorManager.unregisterListener(this)
            exerciseService?.stopExercise()
            if (smashCount + clearCount + dropCount > 0) {
                val record = SessionRecord(
                    timestamp = System.currentTimeMillis(),
                    smashCount = smashCount,
                    clearCount = clearCount,
                    dropCount = dropCount,
                    serveCount = serveCount,
                    bestSpeedKph = bestSpeedKph,
                    rallyCount = rallyCount,
                    longestRally = longestRally,
                    avgHeartRate = heartRate,
                    calories = calories
                )
                SessionHistoryStore.save(this, record)
                history = listOf(record) + history
            }
            lifecycleScope.launch {
                WatchToPhoneSync.sendSessionSummary(
                    context = this@MainActivity,
                    smashCount = smashCount,
                    clearCount = clearCount,
                    dropCount = dropCount,
                    serveCount = serveCount,
                    bestSpeedKph = bestSpeedKph,
                    rallyCount = rallyCount,
                    longestRally = longestRally,
                    avgHeartRate = heartRate,
                    calories = calories
                )
            }
        } else {
            // Every new session starts from zero — otherwise counts left
            // over from a previous Start/Stop cycle (if the user never hit
            // "Reset") would keep accumulating and get saved/synced as part
            // of what looks like a brand-new session.
            resetSession()
            accelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            gyroSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            exerciseService?.startExercise()
        }
        isTracking = !isTracking
    }

    private fun resetSession() {
        smashCount = 0
        clearCount = 0
        dropCount = 0
        serveCount = 0
        bestSpeedKph = 0f
        lastSpeedKph = 0f
        rallyTracker.reset()
        rallyCount = 0
        longestRally = 0
        // Also clear stale heart-rate/calories from a previous session so
        // the live tiles don't show old numbers until Health Services
        // reports fresh values for the new session.
        heartRate = 0.0
        calories = 0.0
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE ->
                detector.onGyroSample(event.values[0], event.values[1], event.values[2])

            Sensor.TYPE_LINEAR_ACCELERATION -> {
                val shot = detector.onAccelSample(
                    System.currentTimeMillis(), event.values[0], event.values[1], event.values[2]
                ) ?: return

                when (mlClassifier?.classify(shot) ?: classifier.classify(shot)) {
                    ShotType.SMASH -> smashCount++
                    ShotType.CLEAR -> clearCount++
                    ShotType.DROP -> dropCount++
                    ShotType.UNKNOWN -> { /* not confident enough to bucket it */ }
                }
                lastSpeedKph = shot.estimatedSpeedKph
                if (shot.estimatedSpeedKph > bestSpeedKph) bestSpeedKph = shot.estimatedSpeedKph

                // Serve is orthogonal to SMASH/CLEAR/DROP/UNKNOWN (see
                // ServeDetector.kt) — a shot can be classified as, say, DROP
                // *and* flagged as a serve at the same time.
                val isServe = serveDetector.isServe(shot)
                if (isServe) serveCount++

                // Every detected shot counts as rally activity. Whether it
                // starts a *new* rally is decided by RallyTracker: an actual
                // serve always does, and otherwise the gap since the last
                // shot is the fallback signal.
                rallyTracker.onShot(shot.timestampMillis, isServe)
                rallyCount = rallyTracker.rallyCount
                longestRally = rallyTracker.longestRallyShots
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onResume() {
        super.onResume()
        if (isTracking) {
            accelSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
            gyroSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mlClassifier?.close()
        if (isBound) {
            unbindService(connection)
            isBound = false
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
    onStartStop: () -> Unit,
    onReset: () -> Unit,
    onToggleHistory: () -> Unit
) {
    val listState = rememberScalingLazyListState()

    MaterialTheme(colors = BadmintonWearColors) {
        Scaffold(
            timeText = { TimeText() },
            vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
            positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
        ) {
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
