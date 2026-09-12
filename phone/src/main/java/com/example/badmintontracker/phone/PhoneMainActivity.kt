package com.example.badmintontracker.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

// ---- Court-toned dark palette (matches the redesign spec) --------------
private object Court {
    val Bg = Color(0xFF0B1512)
    val Surface = Color(0xFF13211C)
    val SurfaceAlt = Color(0xFF1A2B25)
    val Line = Color(0xFF22362F)
    val Ink = Color(0xFFEEF4F0)
    val InkDim = Color(0xFF8FA89F)
    val InkFaint = Color(0xFF5D7871)
    val Lime = Color(0xFFC6FF6B)
    val Coral = Color(0xFFFF6F61)
    val Gold = Color(0xFFFFCF6B)
    val SkyBlue = Color(0xFF6BAEFF)
}

class PhoneMainActivity : ComponentActivity() {
    // Held at the activity level (not just inside the composable) so
    // onResume() below can refresh it — a plain `remember {}` inside
    // setContent only loads once when the composable first enters
    // composition, so a new session synced in from the watch while this
    // screen was already open (foregrounded, or resumed rather than fully
    // recreated) would never show up until the app was killed and reopened.
    private val sessions = mutableStateOf(listOf<SessionSummary>())

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessions.value = SessionStore.loadAll(this)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Court.Lime,
                    secondary = Court.Coral,
                    background = Court.Bg,
                    surface = Court.Surface,
                    onBackground = Court.Ink,
                    onSurface = Court.Ink
                )
            ) {
                Surface(color = Court.Bg, modifier = Modifier.fillMaxSize()) {
                    Scaffold(
                        containerColor = Court.Bg,
                        topBar = {
                            TopAppBar(
                                title = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        BrandMark()
                                        Spacer(Modifier.width(9.dp))
                                        Text(
                                            "Badminton Tracker",
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 15.sp,
                                            color = Court.InkDim
                                        )
                                    }
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = Court.Bg
                                )
                            )
                        }
                    ) { padding ->
                        if (sessions.value.isEmpty()) {
                            EmptyState(Modifier.padding(padding))
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize().padding(padding),
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                item { LatestSessionHero(sessions.value.first()) }
                                item { ThisSessionGrid(sessions.value.first()) }
                                item { AllTimeSummaryRow(sessions.value) }
                                item {
                                    Text(
                                        "Recent sessions",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        color = Court.Ink,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                                items(sessions.value.drop(1)) { session -> SessionRow(session) }
                                item { Spacer(Modifier.height(12.dp)) }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-read from storage every time the screen comes back to the
        // foreground, so a session synced in while this activity was merely
        // backgrounded (not destroyed) shows up without needing a restart.
        sessions.value = SessionStore.loadAll(this)
    }
}

// -------------------------------------------------------------------------
// Small hand-drawn brand mark and empty-state illustration — plain Canvas
// primitives, so this file doesn't pull in an icon library the project
// doesn't already depend on.
// -------------------------------------------------------------------------

@Composable
private fun BrandMark() {
    Canvas(modifier = Modifier.size(20.dp)) {
        val r = size.minDimension / 2f
        drawCircle(Court.Lime, radius = r, center = center, style = Stroke(width = 1.6f))
        drawLine(Court.Lime, Offset(0f, center.y), Offset(size.width, center.y), strokeWidth = 1.6f)
        drawLine(Court.Lime, Offset(center.x, 0f), Offset(center.x, size.height), strokeWidth = 1.6f)
        drawCircle(Court.Lime, radius = r * 0.24f, center = center)
    }
}

@Composable
private fun CourtIllustration() {
    Canvas(modifier = Modifier.size(width = 180.dp, height = 140.dp)) {
        val w = size.width
        val h = size.height
        // court outline
        drawRoundRect(
            color = Court.Line,
            size = Size(w, h),
            cornerRadius = CornerRadius(10f, 10f),
            style = Stroke(width = 2f)
        )
        // net
        drawLine(Court.Line, Offset(w / 2f, 0f), Offset(w / 2f, h), strokeWidth = 2f)
        // dashed service line
        drawLine(
            Court.Line,
            Offset(0f, h * 0.62f),
            Offset(w, h * 0.62f),
            strokeWidth = 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 7f))
        )
        // shuttle trajectory
        val path = Path().apply {
            moveTo(w * 0.16f, h * 0.86f)
            quadraticBezierTo(w * 0.42f, h * 0.05f, w * 0.9f, h * 0.18f)
        }
        drawPath(
            path,
            color = Court.Lime,
            style = Stroke(width = 2f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 7f)))
        )
        drawCircle(Court.Lime, radius = 4f, center = Offset(w * 0.16f, h * 0.86f))
        // shuttle at the end of the trail
        val tip = Offset(w * 0.9f, h * 0.18f)
        val cone = Path().apply {
            moveTo(tip.x, tip.y - 12f)
            lineTo(tip.x - 7f, tip.y + 5f)
            lineTo(tip.x + 7f, tip.y + 5f)
            close()
        }
        drawPath(cone, color = Court.Ink.copy(alpha = 0.9f))
        drawCircle(Court.Coral, radius = 3f, center = Offset(tip.x, tip.y + 5f))
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CourtIllustration()
            Spacer(Modifier.height(18.dp))
            Text(
                "No sessions yet",
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = Court.Ink
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Wear your watch during a match. Rallies, shots and speed sync here automatically when you're done.",
                fontSize = 13.sp,
                color = Court.InkDim,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 260.dp)
            )
            Spacer(Modifier.height(28.dp))
            Text(
                "Waiting on your first session",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Court.InkFaint,
                modifier = Modifier.align(Alignment.Start).padding(bottom = 10.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostTile("Rallies", Modifier.weight(1f))
                GhostTile("Best speed", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun GhostTile(label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Court.Surface, RoundedCornerShape(16.dp))
            .border(1.dp, Court.Line, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(label, fontSize = 11.sp, color = Court.InkFaint)
        Text("—", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Court.InkFaint)
    }
}

@Composable
private fun LatestSessionHero(session: SessionSummary) {
    val totalShots = session.smashCount + session.clearCount + session.dropCount + session.serveCount
    Card(
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(
            Modifier
                .background(Brush.linearGradient(listOf(Court.SurfaceAlt, Court.Surface)))
                .border(1.dp, Court.Line, RoundedCornerShape(24.dp))
                .padding(22.dp)
        ) {
            Text("Latest session · top smash speed", color = Court.InkFaint, fontSize = 12.sp)
            Row {
                Text(
                    session.bestSpeedKph.roundToInt().toString(),
                    color = Court.Ink,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.alignByBaseline()
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "kph",
                    color = Court.InkDim,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.alignByBaseline()
                )
            }
            Text(
                SimpleDateFormat("dd MMM, h:mm a", Locale.getDefault()).format(Date(session.timestamp)),
                color = Court.InkFaint,
                fontSize = 12.sp
            )

            if (totalShots > 0) {
                Spacer(Modifier.height(18.dp))
                Text("Shot breakdown", color = Court.InkFaint, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                ShotBreakdownBar(session, totalShots)
                Spacer(Modifier.height(10.dp))
                ShotLegend(session)
            }
        }
    }
}

@Composable
private fun ShotBreakdownBar(session: SessionSummary, totalShots: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
    ) {
        val segments = listOf(
            session.smashCount to Court.Lime,
            session.clearCount to Court.Gold,
            session.dropCount to Court.SkyBlue,
            session.serveCount to Court.Coral
        )
        var counted = 0
        segments.forEach { (count, color) ->
            if (count > 0) {
                counted += count
                Box(
                    Modifier
                        .weight(count.toFloat())
                        .fillMaxHeight()
                        .background(color)
                )
            }
        }
        // Keep the bar visually complete even if some categories are 0.
        if (counted < totalShots) {
            Box(Modifier.weight((totalShots - counted).toFloat()).fillMaxHeight().background(Court.Line))
        }
    }
}

@Composable
private fun ShotLegend(session: SessionSummary) {
    val items = listOf(
        Triple("Smash", session.smashCount, Court.Lime),
        Triple("Clear", session.clearCount, Court.Gold),
        Triple("Drop", session.dropCount, Court.SkyBlue),
        Triple("Serve", session.serveCount, Court.Coral)
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        items.forEach { (label, count, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(color)
                )
                Spacer(Modifier.width(5.dp))
                Text("$count $label", color = Court.InkDim, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ThisSessionGrid(session: SessionSummary) {
    val totalShots = session.smashCount + session.clearCount + session.dropCount + session.serveCount
    val avgRally = if (session.rallyCount > 0) totalShots.toDouble() / session.rallyCount else 0.0

    Column {
        Text(
            "This session",
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            color = Court.Ink,
            modifier = Modifier.padding(bottom = 10.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile("Rallies", session.rallyCount.toString(), Modifier.weight(1f))
            MetricTile("Longest rally", session.longestRally.toString(), Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile("Avg rally", String.format(Locale.getDefault(), "%.1f shots", avgRally), Modifier.weight(1f))
            MetricTile("Avg heart rate", "${session.avgHeartRate.roundToInt()} bpm", Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile("Calories", "${session.calories.roundToInt()} kcal", Modifier.weight(1f))
            MetricTile(
                "Recovery",
                if (session.avgRecoveryBpm > 0.0) "${session.avgRecoveryBpm.roundToInt()} bpm" else "—",
                Modifier.weight(1f)
            )
        }
        if (session.avgRecoveryBpm > 0.0) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Recovery = avg heart-rate drop between a rally ending and the next serve",
                fontSize = 10.5.sp,
                color = Court.InkFaint
            )
        }
    }
}

@Composable
private fun MetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(Court.Surface, RoundedCornerShape(16.dp))
            .border(1.dp, Court.Line, RoundedCornerShape(16.dp))
            .padding(horizontal = 15.dp, vertical = 13.dp)
    ) {
        Text(label, fontSize = 11.5.sp, color = Court.InkFaint)
        Spacer(Modifier.height(3.dp))
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Court.Ink)
    }
}

@Composable
private fun AllTimeSummaryRow(sessions: List<SessionSummary>) {
    val totalSmashes = sessions.sumOf { it.smashCount }
    val bestEver = sessions.maxOfOrNull { it.bestSpeedKph }?.roundToInt() ?: 0
    Column {
        Text(
            "All time",
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            color = Court.Ink,
            modifier = Modifier.padding(bottom = 10.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SummaryPill(Modifier.weight(1f), sessions.size.toString(), "Sessions")
            SummaryPill(Modifier.weight(1f), totalSmashes.toString(), "Total smashes")
            SummaryPill(Modifier.weight(1f), "$bestEver kph", "Best ever")
        }
    }
}

@Composable
private fun SummaryPill(modifier: Modifier, value: String, label: String) {
    Column(
        modifier = modifier
            .background(Court.SurfaceAlt, RoundedCornerShape(16.dp))
            .border(1.dp, Court.Line, RoundedCornerShape(16.dp))
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, fontWeight = FontWeight.Bold, color = Court.Lime, fontSize = 16.sp)
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 11.sp, color = Court.InkFaint, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SessionRow(session: SessionSummary) {
    val dateFormat = remember { SimpleDateFormat("dd MMM, h:mm a", Locale.getDefault()) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    dateFormat.format(Date(session.timestamp)),
                    fontWeight = FontWeight.SemiBold,
                    color = Court.Ink,
                    fontSize = 13.5.sp
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "${session.rallyCount} rallies · longest ${session.longestRally}",
                    color = Court.InkFaint,
                    fontSize = 11.5.sp
                )
                if (session.avgRecoveryBpm > 0.0) {
                    Text(
                        "recovered ${session.avgRecoveryBpm.roundToInt()} bpm avg between rallies",
                        color = Court.Coral,
                        fontSize = 11.sp
                    )
                }
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Court.Lime.copy(alpha = 0.12f))
                    .padding(horizontal = 11.dp, vertical = 7.dp)
            ) {
                Text(
                    "${session.bestSpeedKph.roundToInt()} kph",
                    fontWeight = FontWeight.SemiBold,
                    color = Court.Lime,
                    fontSize = 12.5.sp
                )
            }
        }
        Spacer(Modifier.height(9.dp))
        val totalShots = session.smashCount + session.clearCount + session.dropCount + session.serveCount
        if (totalShots > 0) ShotBreakdownBar(session, totalShots)
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = Court.Line, thickness = 1.dp)
    }
}
