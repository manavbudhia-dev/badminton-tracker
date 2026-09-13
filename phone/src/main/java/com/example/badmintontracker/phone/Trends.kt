package com.example.badmintontracker.phone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// =============================================================================
// Pure logic (no Context, no Compose) — kept separate from the Composables
// below so it can be unit tested on its own, same split as
// HeartRateRecovery.kt on the watch side.
//
// Why this file exists: PhoneMainActivity's dashboard (now "HomeScreen")
// only ever shows *this* session next to a list of *past* ones — nothing
// plots a number across sessions to show whether it's actually trending up
// or down over time. That's what TrendsScreen adds, as a second tab.
// =============================================================================

/** One trackable number per session. Knows its own display label/unit and how to read itself off a [SessionSummary]. */
enum class TrendMetric(val label: String, val unit: String) {
    SPEED("Smash speed", "kph"),
    SHOTS("Shots", "shots"),
    RALLY("Longest rally", "shots"),
    HEART_RATE("Avg heart rate", "bpm"),
    RECOVERY("Recovery", "bpm"),
    CALORIES("Calories", "kcal");

    fun valueOf(session: SessionSummary): Double = when (this) {
        SPEED -> session.bestSpeedKph
        SHOTS -> (session.smashCount + session.clearCount + session.dropCount + session.serveCount).toDouble()
        RALLY -> session.longestRally.toDouble()
        HEART_RATE -> session.avgHeartRate
        RECOVERY -> session.avgRecoveryBpm
        CALORIES -> session.calories
    }
}

data class TrendPoint(val timestampMillis: Long, val value: Double)

data class TrendSummary(
    // Chronological, oldest first — sessions with no data for this metric
    // already excluded (see computeTrend below).
    val points: List<TrendPoint>,
    val latest: Double,
    val previous: Double?,
    val best: Double,
    val average: Double
) {
    val deltaFromPrevious: Double? get() = previous?.let { latest - it }
}

/**
 * Builds a trend for [metric] from up to the most recent [maxSessions]
 * sessions that actually recorded it.
 *
 * "Actually recorded it" matters: a session where the heart-rate strap
 * never got a reading stores 0.0 for avgHeartRate (SessionStore.loadAll's
 * optDouble default — see SessionStore.kt), and a real 0 bpm session isn't
 * a thing, so those get filtered out rather than plotted as a crash to
 * zero that isn't real. Same reasoning covers avgRecoveryBpm, which is 0.0
 * both for "no rest windows completed" and for sessions synced before HRR
 * tracking existed.
 *
 * Returns null if no session has usable data for this metric yet.
 */
fun computeTrend(sessions: List<SessionSummary>, metric: TrendMetric, maxSessions: Int = 20): TrendSummary? {
    val points = sessions
        .sortedBy { it.timestamp }
        .map { TrendPoint(it.timestamp, metric.valueOf(it)) }
        .filter { it.value > 0.0 }
        .takeLast(maxSessions)
    if (points.isEmpty()) return null

    val values = points.map { it.value }
    return TrendSummary(
        points = points,
        latest = values.last(),
        previous = if (values.size >= 2) values[values.size - 2] else null,
        best = values.max(),
        average = values.average()
    )
}

data class WeekBucket(val weekStartMillis: Long, val sessionCount: Int)

/**
 * Buckets sessions into the last [weeks] calendar weeks (Monday–Sunday),
 * oldest first, including weeks with zero sessions — a gap in play should
 * show up as a real gap in the chart, not get silently compressed away by
 * only counting weeks that had a session.
 */
fun computeWeeklyActivity(
    sessions: List<SessionSummary>,
    weeks: Int = 8,
    nowMillis: Long = System.currentTimeMillis()
): List<WeekBucket> {
    // Walking back by a fixed 7*24h in millis (the old approach) silently
    // drifts by an hour across a DST transition, so a week boundary near a
    // clock change could land a session in the wrong bucket or double-count/
    // skip an hour at the edges. Doing the arithmetic in local calendar days
    // via ZonedDateTime.minusWeeks/plusDays sidesteps that: each "day" is
    // however long it actually is in the local zone, DST included.
    val zone = ZoneId.systemDefault()
    val thisWeekStart = startOfWeek(nowMillis, zone)
    // Oldest first: i = weeks-1 (furthest back) down to i = 0 (this week).
    val weekStarts = (weeks - 1 downTo 0).map { i -> thisWeekStart.minusWeeks(i.toLong()) }
    return weekStarts.map { weekStart ->
        val weekStartMillis = weekStart.toInstant().toEpochMilli()
        val weekEndMillis = weekStart.plusWeeks(1).toInstant().toEpochMilli()
        WeekBucket(weekStartMillis, sessions.count { it.timestamp in weekStartMillis until weekEndMillis })
    }
}

private fun startOfWeek(timestampMillis: Long, zone: ZoneId): ZonedDateTime =
    Instant.ofEpochMilli(timestampMillis)
        .atZone(zone)
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        .toLocalDate()
        .atStartOfDay(zone)

/** Whole numbers print clean; anything else gets one decimal — used for every metric value shown on this screen. */
private fun formatMetricValue(value: Double): String =
    if (value == value.roundToInt().toDouble()) value.roundToInt().toString()
    else String.format(Locale.getDefault(), "%.1f", value)

// =============================================================================
// Composables
// =============================================================================

/** The "Trends" tab — see HomeScreen in PhoneMainActivity.kt for the other tab. */
@Composable
fun TrendsScreen(sessions: List<SessionSummary>) {
    var selectedMetric by rememberSaveable { mutableStateOf(TrendMetric.SPEED) }
    val trend = remember(sessions, selectedMetric) { computeTrend(sessions, selectedMetric) }
    val weeklyActivity = remember(sessions) { computeWeeklyActivity(sessions) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                "Progress",
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = Court.Ink,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        item { MetricChipRow(selected = selectedMetric, onSelect = { selectedMetric = it }) }
        item {
            if (trend != null) TrendCard(selectedMetric, trend) else EmptyTrendCard(selectedMetric)
        }
        item {
            Text(
                "Weekly activity",
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = Court.Ink
            )
        }
        item { WeeklyActivityCard(weeklyActivity) }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun MetricChipRow(selected: TrendMetric, onSelect: (TrendMetric) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TrendMetric.entries.forEach { metric ->
            MetricChip(metric = metric, isSelected = metric == selected, onClick = { onSelect(metric) })
        }
    }
}

@Composable
private fun MetricChip(metric: TrendMetric, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (isSelected) Court.Lime.copy(alpha = 0.16f) else Court.Surface)
            .border(1.dp, if (isSelected) Court.Lime else Court.Line, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(
            metric.label,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isSelected) Court.Lime else Court.InkDim
        )
    }
}

/** The metric's chart/accent color — reused for its line chart, delta arrow (where meaningful), and dots. */
private fun TrendMetric.chartColor(): Color = when (this) {
    TrendMetric.SPEED -> Court.Lime
    TrendMetric.SHOTS -> Court.Gold
    TrendMetric.RALLY -> Court.SkyBlue
    TrendMetric.HEART_RATE -> Court.Coral
    TrendMetric.RECOVERY -> Court.Lime
    TrendMetric.CALORIES -> Court.Gold
}

@Composable
private fun TrendCard(metric: TrendMetric, trend: TrendSummary) {
    val color = metric.chartColor()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.linearGradient(listOf(Court.SurfaceAlt, Court.Surface)), RoundedCornerShape(24.dp))
            .border(1.dp, Court.Line, RoundedCornerShape(24.dp))
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                formatMetricValue(trend.latest),
                color = Court.Ink,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(6.dp))
            Text(
                metric.unit,
                color = Court.InkDim,
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 5.dp)
            )
            Spacer(Modifier.weight(1f))
            trend.deltaFromPrevious?.let { delta -> DeltaBadge(delta, metric) }
        }
        Text(
            "Latest session · ${metric.label.lowercase(Locale.getDefault())}",
            color = Court.InkFaint,
            fontSize = 11.5.sp
        )

        if (trend.points.size >= 2) {
            Spacer(Modifier.height(16.dp))
            TrendLineChart(points = trend.points, color = color)
        } else {
            Spacer(Modifier.height(10.dp))
            Text(
                "Log a couple more sessions to see a trend line here.",
                color = Court.InkFaint,
                fontSize = 11.5.sp
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            MetricTile("Best", formatMetricValue(trend.best), Modifier.weight(1f))
            MetricTile("Average", formatMetricValue(trend.average), Modifier.weight(1f))
            MetricTile("Sessions", trend.points.size.toString(), Modifier.weight(1f))
        }
    }
}

@Composable
private fun DeltaBadge(delta: Double, metric: TrendMetric) {
    // Heart rate during play has no clear "better" direction on its own (a
    // higher number could just mean a harder session, not worse fitness),
    // so it gets a neutral color. Every other metric here is a volume/
    // output number where "up since last session" is a reasonable thing to
    // highlight positively.
    val isNeutral = metric == TrendMetric.HEART_RATE
    val isUp = delta >= 0
    val color = if (isNeutral) Court.SkyBlue else if (isUp) Court.Lime else Court.Coral
    val arrow = if (isUp) "▲" else "▼"
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 9.dp, vertical = 5.dp)
    ) {
        Text(
            "$arrow ${formatMetricValue(abs(delta))}",
            color = color,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun TrendLineChart(points: List<TrendPoint>, color: Color) {
    Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
        val minY = points.minOf { it.value }
        val maxY = points.maxOf { it.value }
        val isFlat = maxY == minY
        // A flat trend (every point identical) would divide by zero below —
        // fall back to a fixed range of 1 so the math stays safe. That alone
        // still plugs value == minY into the normal formula, which evaluates
        // to topPad + chartHeight (the very bottom) for every point, drawing
        // a line hugging the floor of the chart instead of a flat line
        // centered in it — isFlat is checked separately below to fix that.
        val range = (maxY - minY).let { if (it > 0.0) it else 1.0 }
        val stepX = if (points.size > 1) size.width / (points.size - 1) else 0f
        val topPad = 10f
        val bottomPad = 10f
        val chartHeight = size.height - topPad - bottomPad

        fun yFor(value: Double): Float =
            if (isFlat) topPad + (chartHeight / 2f)
            else topPad + chartHeight - ((value - minY) / range * chartHeight).toFloat()

        // Faint dashed guide lines at the bottom, middle, and top of the range.
        listOf(0f, 0.5f, 1f).forEach { frac ->
            val y = topPad + chartHeight * (1f - frac)
            drawLine(
                Court.Line,
                Offset(0f, y),
                Offset(size.width, y),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f))
            )
        }

        val linePath = Path()
        val fillPath = Path()
        points.forEachIndexed { i, p ->
            val x = i * stepX
            val y = yFor(p.value)
            if (i == 0) {
                linePath.moveTo(x, y)
                fillPath.moveTo(x, size.height)
                fillPath.lineTo(x, y)
            } else {
                linePath.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo((points.size - 1) * stepX, size.height)
        fillPath.close()

        drawPath(fillPath, brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.22f), Color.Transparent)))
        drawPath(linePath, color = color, style = Stroke(width = 2.5f, cap = StrokeCap.Round))

        points.forEachIndexed { i, p ->
            val x = i * stepX
            val y = yFor(p.value)
            val isLatest = i == points.size - 1
            drawCircle(color, radius = if (isLatest) 5f else 2.6f, center = Offset(x, y))
            // A small hollow center on the latest point only, so "where am
            // I right now" is unambiguous at a glance on a small screen.
            if (isLatest) drawCircle(Court.Bg, radius = 2f, center = Offset(x, y))
        }
    }
}

@Composable
private fun EmptyTrendCard(metric: TrendMetric) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Court.Surface, RoundedCornerShape(20.dp))
            .border(1.dp, Court.Line, RoundedCornerShape(20.dp))
            .padding(20.dp)
    ) {
        Text(
            "No ${metric.label.lowercase(Locale.getDefault())} data yet",
            color = Court.Ink,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            when (metric) {
                TrendMetric.HEART_RATE, TrendMetric.RECOVERY ->
                    "Wear the watch snugly enough for its heart-rate sensor to get a reading during play."
                else -> "Play a session with your watch to start building this trend."
            },
            color = Court.InkFaint,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun WeeklyActivityCard(weeks: List<WeekBucket>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Court.Surface, RoundedCornerShape(20.dp))
            .border(1.dp, Court.Line, RoundedCornerShape(20.dp))
            .padding(18.dp)
    ) {
        Text(
            "Sessions per week, last ${weeks.size} weeks",
            color = Court.InkFaint,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(14.dp))
        WeeklyActivityChart(weeks)
    }
}

@Composable
private fun WeeklyActivityChart(weeks: List<WeekBucket>) {
    val maxCount = (weeks.maxOfOrNull { it.sessionCount } ?: 0).coerceAtLeast(1)
    val labelFormat = remember { DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()) }
    val zone = remember { ZoneId.systemDefault() }
    Row(
        modifier = Modifier.fillMaxWidth().height(90.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        weeks.forEach { week ->
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                Text(
                    if (week.sessionCount > 0) week.sessionCount.toString() else "",
                    fontSize = 10.sp,
                    color = Court.InkFaint
                )
                Spacer(Modifier.height(3.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(
                            (week.sessionCount.toFloat() / maxCount * 48f).dp
                                .coerceAtLeast(if (week.sessionCount > 0) 6.dp else 3.dp)
                        )
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .background(if (week.sessionCount > 0) Court.Lime else Court.Line)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    Instant.ofEpochMilli(week.weekStartMillis).atZone(zone).format(labelFormat),
                    fontSize = 9.sp,
                    color = Court.InkFaint,
                    maxLines = 1
                )
            }
        }
    }
}
