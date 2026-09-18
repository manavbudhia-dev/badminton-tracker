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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// =============================================================================
// Pure logic (no Context, no Compose) — kept separate from the Composables
// below so it can be unit tested on its own, same split as
// HeartRateRecovery.kt on the watch side.
//
// Why this file exists: PhoneMainActivity's dashboard ("HomeScreen") only
// ever shows *this* session next to a list of *past* ones — nothing plots a
// number across sessions to show whether it's actually trending up or down
// over time. That's what TrendsScreen adds, as a second tab.
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
    val weekMillis = 7L * 24 * 60 * 60 * 1000
    val thisWeekStart = startOfWeek(nowMillis)
    val weekStarts = (0 until weeks).map { i -> thisWeekStart - i * weekMillis }.sorted()
    return weekStarts.map { weekStart ->
        val weekEnd = weekStart + weekMillis
        WeekBucket(weekStart, sessions.count { it.timestamp in weekStart until weekEnd })
    }
}

private fun startOfWeek(timestampMillis: Long): Long {
    val cal = Calendar.getInstance()
    cal.timeInMillis = timestampMillis
    cal.firstDayOfWeek = Calendar.MONDAY
    cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

/**
 * The fastest [limit] shots of [type] across every session's shot log,
 * newest-data-required: sessions synced before the Shot Log feature
 * existed have an empty `shots` list and simply don't contribute here.
 *
 * Filtered to a single shot type (default "Smash") rather than reusing
 * SessionSummary.bestSpeedKph, because that field is really "fastest shot
 * of any classified type in the session" (see MainActivity.onSensorChanged
 * on the watch — it updates on every shot, not just smashes) — not what
 * "my fastest smash" should mean here.
 */
fun computeTopShots(sessions: List<SessionSummary>, type: String = "Smash", limit: Int = 5): List<ShotLogEntry> =
    sessions
        .flatMap { it.shots }
        .filter { it.type == type }
        .sortedByDescending { it.speedKph }
        .take(limit)

/** Whole numbers print clean; anything else gets one decimal — used for every metric value shown on this screen. */
private fun formatMetricValue(value: Double): String =
    if (value == value.roundToInt().toDouble()) value.roundToInt().toString()
    else String.format(Locale.getDefault(), "%.1f", value)

/**
 * Rounds [maxValue] up to a "nice" axis ceiling (1/2/2.5/5/10 × a power of
 * ten) with a little headroom, so the Y axis reads like 0/200/400/600/800
 * instead of an arbitrary decimal — and so the highest point never sits
 * exactly on the top gridline.
 */
private fun niceAxisTop(maxValue: Double): Double {
    if (maxValue <= 0.0) return 1.0
    fun niceCeil(v: Double): Double {
        val magnitude = Math.pow(10.0, Math.floor(Math.log10(v)))
        val normalized = v / magnitude
        val niceNormalized = when {
            normalized <= 1.0 -> 1.0
            normalized <= 2.0 -> 2.0
            normalized <= 2.5 -> 2.5
            normalized <= 5.0 -> 5.0
            else -> 10.0
        }
        return niceNormalized * magnitude
    }
    val rough = niceCeil(maxValue)
    return if (rough <= maxValue) niceCeil(maxValue * 1.15) else rough
}

/** Picks up to [maxLabels] evenly-spaced indices out of [count] items, always including the first and last. */
private fun sampleIndices(count: Int, maxLabels: Int = 6): List<Int> {
    if (count <= maxLabels) return (0 until count).toList()
    val step = (count - 1).toDouble() / (maxLabels - 1)
    return (0 until maxLabels).map { i -> (i * step).roundToInt() }.distinct()
}

// =============================================================================
// Composables
// =============================================================================

/** The "Trends" tab — see HomeScreen in PhoneMainActivity.kt for the other tab. */
@Composable
fun TrendsScreen(sessions: List<SessionSummary>) {
    var selectedMetric by rememberSaveable { mutableStateOf(TrendMetric.SPEED) }
    val trend = remember(sessions, selectedMetric) { computeTrend(sessions, selectedMetric) }
    val weeklyActivity = remember(sessions) { computeWeeklyActivity(sessions) }
    val topSmashes = remember(sessions) { computeTopShots(sessions) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { MetricChipRow(selected = selectedMetric, onSelect = { selectedMetric = it }) }
        item {
            if (trend != null) TrendCard(selectedMetric, trend) else EmptyTrendCard(selectedMetric)
        }
        // Only meaningful for the Speed metric — "your top 5 all-time
        // smashes" doesn't map onto heart rate or calories.
        if (selectedMetric == TrendMetric.SPEED && topSmashes.isNotEmpty()) {
            item {
                Text(
                    "Top smashes, all time",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = Court.Ink
                )
            }
            item { TopSmashesCard(topSmashes) }
        }
        item {
            Text(
                "Weekly Activity",
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                color = Court.Ink
            )
        }
        item { WeeklyActivityCard(weeklyActivity) }
        item { MotivationalQuoteCard() }
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
            TrendChartWithAxes(points = trend.points, color = color)
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
            IconMetricTile(
                icon = { TrophyIcon(Court.Gold, iconSize = 16.dp) },
                value = formatMetricValue(trend.best),
                label = "Best",
                modifier = Modifier.weight(1f)
            )
            IconMetricTile(
                icon = { BarsIcon(Court.SkyBlue, iconSize = 16.dp) },
                value = formatMetricValue(trend.average),
                label = "Average",
                modifier = Modifier.weight(1f)
            )
            IconMetricTile(
                icon = { GridIcon(Court.InkDim, iconSize = 16.dp) },
                value = trend.points.size.toString(),
                label = "Sessions",
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun IconMetricTile(icon: @Composable () -> Unit, value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(Court.Surface, RoundedCornerShape(16.dp))
            .border(1.dp, Court.Line, RoundedCornerShape(16.dp))
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        icon()
        Spacer(Modifier.height(6.dp))
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Court.Ink)
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 10.5.sp, color = Court.InkFaint)
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

/**
 * The line chart plus its Y-axis labels (left column) and X-axis date
 * labels (below), with a small tooltip bubble pinned over the latest
 * point — matches the redesign spec's annotated chart rather than the
 * bare line the first cut of this screen had.
 */
@Composable
private fun TrendChartWithAxes(points: List<TrendPoint>, color: Color) {
    val top = remember(points) { niceAxisTop(points.maxOf { it.value }) }
    val dateFormat = remember { SimpleDateFormat("d MMM", Locale.getDefault()) }
    val latest = points.last()

    Column {
        Row(modifier = Modifier.fillMaxWidth().height(130.dp)) {
            Column(
                modifier = Modifier.width(30.dp).fillMaxHeight().padding(end = 6.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatMetricValue(top), fontSize = 9.sp, color = Court.InkFaint)
                Text(formatMetricValue(top / 2.0), fontSize = 9.sp, color = Court.InkFaint)
                Text("0", fontSize = 9.sp, color = Court.InkFaint)
            }
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                TrendLineChart(points = points, color = color, top = top)
                Column(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .background(Court.Bg, RoundedCornerShape(8.dp))
                        .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(formatMetricValue(latest.value), color = Court.Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(dateFormat.format(Date(latest.timestampMillis)), color = Court.InkFaint, fontSize = 9.sp)
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Spacer(modifier = Modifier.width(36.dp))
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                sampleIndices(points.size).forEach { i ->
                    Text(dateFormat.format(Date(points[i].timestampMillis)), fontSize = 9.sp, color = Court.InkFaint)
                }
            }
        }
    }
}

/** Just the plotted line/fill/dots on a 0..[top] Y scale — axes and tooltip live in TrendChartWithAxes above. */
@Composable
private fun TrendLineChart(points: List<TrendPoint>, color: Color, top: Double) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val range = if (top > 0.0) top else 1.0
        val stepX = if (points.size > 1) size.width / (points.size - 1) else 0f
        val topPad = 10f
        val bottomPad = 10f
        val chartHeight = size.height - topPad - bottomPad

        fun yFor(value: Double): Float = topPad + chartHeight - ((value / range) * chartHeight).toFloat()

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
private fun TopSmashesCard(shots: List<ShotLogEntry>) {
    val dateFormat = remember { SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Court.Surface, RoundedCornerShape(20.dp))
            .border(1.dp, Court.Line, RoundedCornerShape(20.dp))
            .padding(vertical = 8.dp)
    ) {
        shots.forEachIndexed { index, shot ->
            if (index > 0) {
                HorizontalDivider(
                    color = Court.Line,
                    thickness = 1.dp,
                    modifier = Modifier.padding(horizontal = 18.dp)
                )
            }
            val isBest = index == 0
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (isBest) Court.Gold.copy(alpha = 0.18f) else Court.Line),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "${index + 1}",
                        color = if (isBest) Court.Gold else Court.InkDim,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${shot.speedKph.roundToInt()} kph",
                            color = Court.Ink,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        if (isBest) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "all-time best",
                                color = Court.Gold,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Text(
                        dateFormat.format(Date(shot.timestampMillis)),
                        color = Court.InkFaint,
                        fontSize = 11.5.sp
                    )
                }
            }
        }
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
    val labelFormat = remember { SimpleDateFormat("d MMM", Locale.getDefault()) }
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
                                .coerceAtLeast(if (week.sessionCount > 0) 6.dp else 5.dp)
                        )
                        .clip(
                            if (week.sessionCount > 0) RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
                            else RoundedCornerShape(50)
                        )
                        .background(if (week.sessionCount > 0) Court.Lime else Court.Line)
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    labelFormat.format(Date(week.weekStartMillis)),
                    fontSize = 9.sp,
                    color = Court.InkFaint,
                    maxLines = 1
                )
            }
        }
    }
}

/** Small rotating motivational line at the bottom of Trends — deterministic per calendar day, not per recomposition. */
private val motivationalQuotes = listOf(
    "Small progress every session leads to big results.",
    "Your only limit is the one you accept.",
    "Every rally is a rep — show up and swing.",
    "Consistency beats intensity over a season.",
    "Track it, trust it, improve it."
)

@Composable
private fun MotivationalQuoteCard() {
    val quote = remember {
        val dayIndex = (System.currentTimeMillis() / (24L * 60 * 60 * 1000)).toInt()
        motivationalQuotes[Math.floorMod(dayIndex, motivationalQuotes.size)]
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Court.Surface, RoundedCornerShape(18.dp))
            .border(1.dp, Court.Line, RoundedCornerShape(18.dp))
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("\u201C", color = Court.Lime, fontWeight = FontWeight.Bold, fontSize = 26.sp)
        Spacer(Modifier.width(10.dp))
        Text(
            "\u201C$quote\u201D",
            color = Court.InkDim,
            fontSize = 13.sp,
            fontStyle = FontStyle.Italic,
            modifier = Modifier.weight(1f)
        )
    }
}
