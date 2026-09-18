package com.example.badmintontracker.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
// internal (not private) so Trends.kt and SessionDetail.kt can reuse the
// same palette and tiles — this file and those are one visual system, not
// three separate ones.
internal object Court {
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
    // Avatar bubble accent from the redesign spec — not used anywhere else
    // in the palette, so it lives here rather than being confused for a
    // data/status color like the ones above.
    val Periwinkle = Color(0xFF8B93F0)
}

/** The four bottom-nav destinations in the redesign. Home and Insights are shortcuts into the Home/Trends pill tabs; Sessions and Profile are their own screens. */
internal enum class BottomNavItem(val label: String) {
    HOME("Home"), SESSIONS("Sessions"), INSIGHTS("Insights"), PROFILE("Profile")
}

class PhoneMainActivity : ComponentActivity() {
    // Held at the activity level (not just inside the composable) so
    // onResume() below can refresh it — a plain `remember {}` inside
    // setContent only loads once when the composable first enters
    // composition, so a new session synced in from the watch while this
    // screen was already open (foregrounded, or resumed rather than fully
    // recreated) would never show up until the app was killed and reopened.
    private val sessions = mutableStateOf(listOf<SessionSummary>())

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
                    AppRoot(sessions.value)
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

/**
 * The whole app shell: top bar, the Home/Trends pill tabs, the bottom nav,
 * and which of the four bottom-nav destinations is currently showing.
 *
 * Home and Insights (bottom nav) are two doors into the same dashboard —
 * picking either just moves [selectedTab] and leaves the bottom nav on
 * Home, since Sessions and Profile are the only nav items with a truly
 * separate screen. That keeps the two navigation rows in sync instead of
 * disagreeing about what's currently open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(sessions: List<SessionSummary>) {
    if (sessions.isEmpty()) {
        Scaffold(containerColor = Court.Bg, topBar = { AppTopBar() }) { padding ->
            EmptyState(Modifier.padding(padding))
        }
        return
    }

    var bottomNav by rememberSaveable { mutableStateOf(BottomNavItem.HOME) }
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    // Storing just the timestamp (not the SessionSummary itself) keeps this
    // rememberSaveable-friendly with no custom Saver — the matching session
    // is looked up from `sessions` below.
    var selectedSessionTimestamp by rememberSaveable { mutableStateOf<Long?>(null) }
    val selectedSession = selectedSessionTimestamp?.let { ts -> sessions.find { it.timestamp == ts } }

    fun openTab(index: Int) {
        selectedTab = index
        bottomNav = BottomNavItem.HOME
    }

    Scaffold(
        containerColor = Court.Bg,
        topBar = { AppTopBar() },
        bottomBar = {
            if (selectedSession == null) {
                BottomNavBar(
                    selected = if (selectedTab == 1) BottomNavItem.INSIGHTS else bottomNav,
                    onSelect = { item ->
                        when (item) {
                            BottomNavItem.HOME -> openTab(0)
                            BottomNavItem.INSIGHTS -> openTab(1)
                            else -> bottomNav = item
                        }
                    }
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                selectedSession != null -> SessionDetailScreen(
                    session = selectedSession,
                    onBack = { selectedSessionTimestamp = null }
                )
                bottomNav == BottomNavItem.SESSIONS -> AllSessionsScreen(
                    sessions = sessions,
                    onSessionClick = { selectedSessionTimestamp = it.timestamp }
                )
                bottomNav == BottomNavItem.PROFILE -> ProfileScreen(sessions = sessions)
                else -> {
                    HomeTrendsTabs(selectedTab = selectedTab, onSelect = { openTab(it) })
                    if (selectedTab == 0) {
                        HomeScreen(
                            sessions = sessions,
                            onSessionClick = { selectedSessionTimestamp = it.timestamp },
                            onViewAllSessions = { bottomNav = BottomNavItem.SESSIONS }
                        )
                    } else {
                        TrendsScreen(sessions)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTopBar() {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandMark()
                Spacer(Modifier.width(9.dp))
                Text(
                    "Badminton Tracker",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = Court.Ink
                )
            }
        },
        actions = {
            BellIcon(tint = Court.Ink, modifier = Modifier.padding(end = 16.dp))
            AvatarBubble(initial = 'M', modifier = Modifier.padding(end = 12.dp))
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Court.Bg)
    )
}

@Composable
private fun HomeTrendsTabs(selectedTab: Int, onSelect: (Int) -> Unit) {
    TabRow(
        selectedTabIndex = selectedTab,
        containerColor = Court.Bg,
        contentColor = Court.Lime,
        divider = { HorizontalDivider(color = Court.Line, thickness = 1.dp) }
    ) {
        Tab(
            selected = selectedTab == 0,
            onClick = { onSelect(0) },
            text = {
                Text(
                    "Home",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selectedTab == 0) Court.Lime else Court.InkFaint
                )
            }
        )
        Tab(
            selected = selectedTab == 1,
            onClick = { onSelect(1) },
            text = {
                Text(
                    "Trends",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (selectedTab == 1) Court.Lime else Court.InkFaint
                )
            }
        )
    }
}

@Composable
private fun BottomNavBar(selected: BottomNavItem, onSelect: (BottomNavItem) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().background(Court.Surface)) {
        HorizontalDivider(color = Court.Line, thickness = 1.dp)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            BottomNavItem.entries.forEach { item ->
                val isSelected = item == selected
                val tint = if (isSelected) Court.Lime else Court.InkFaint
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable { onSelect(item) }
                        .padding(horizontal = 10.dp, vertical = 2.dp)
                ) {
                    when (item) {
                        BottomNavItem.HOME -> HouseIcon(tint)
                        BottomNavItem.SESSIONS -> ClipboardIcon(tint)
                        BottomNavItem.INSIGHTS -> BarsIcon(tint)
                        BottomNavItem.PROFILE -> PersonIcon(tint)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        item.label,
                        fontSize = 10.sp,
                        color = tint,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
private fun AvatarBubble(initial: Char, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(30.dp)
            .clip(RoundedCornerShape(50))
            .background(Court.Periwinkle),
        contentAlignment = Alignment.Center
    ) {
        Text(initial.toString(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

// -------------------------------------------------------------------------
// Small hand-drawn brand mark and empty-state illustration — plain Canvas
// primitives, so this file doesn't pull in an icon library the project
// doesn't already depend on. (More icons for the redesign live in Icons.kt.)
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

// =============================================================================
// Home tab — the redesigned dashboard.
// =============================================================================

/** The "Home" tab. See TrendsScreen (Trends.kt) for the other tab. */
@Composable
private fun HomeScreen(
    sessions: List<SessionSummary>,
    onSessionClick: (SessionSummary) -> Unit,
    onViewAllSessions: () -> Unit
) {
    var showStartDialog by remember { mutableStateOf(false) }
    val latest = sessions.first()
    val previous = sessions.getOrNull(1)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { LatestSessionHero(latest, previous, onClick = { onSessionClick(latest) }) }
        item { HomeMetricRow(latest) }
        item { ShotBreakdownCard(latest) }
        item { CaloriesRecoveryRow(latest) }
        item { StartSessionButton(onClick = { showStartDialog = true }) }
        item { AllTimeStatsHeader(onViewAll = onViewAllSessions) }
        item { AllTimeStatsRow(sessions) }
        if (sessions.size > 1) {
            item {
                Text(
                    "Recent sessions",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = Court.Ink,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            items(
                // A stable key (not just index) lets Compose track each
                // row's identity across recompositions — without it,
                // inserting a new session at the top shifts every row's
                // index by one and Compose has no way to tell "this is
                // still the same session, just moved" from "this is a
                // different session that happens to be at the same
                // index", so it re-binds more than it needs to.
                count = sessions.size - 1,
                key = { index -> sessions[index + 1].timestamp }
            ) { index ->
                val session = sessions[index + 1]
                SessionRow(session, onClick = { onSessionClick(session) })
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }

    if (showStartDialog) {
        AlertDialog(
            onDismissRequest = { showStartDialog = false },
            confirmButton = {
                TextButton(onClick = { showStartDialog = false }) {
                    Text("Got it", color = Court.Lime, fontWeight = FontWeight.SemiBold)
                }
            },
            containerColor = Court.Surface,
            titleContentColor = Court.Ink,
            textContentColor = Court.InkDim,
            title = { Text("Start from your watch") },
            text = {
                Text(
                    "Sessions are started from the watch's Start Session tile, not from here. " +
                        "Rallies, shots and speed will sync to this screen automatically once you finish."
                )
            }
        )
    }
}

@Composable
private fun LatestSessionHero(session: SessionSummary, previous: SessionSummary?, onClick: () -> Unit) {
    // Null (no badge shown) when there's no prior session to compare
    // against, or the prior session had no recorded speed to divide by.
    val percentChange = previous?.takeIf { it.bestSpeedKph > 0.0 }?.let {
        (session.bestSpeedKph - it.bestSpeedKph) / it.bestSpeedKph * 100.0
    }
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(Court.SurfaceAlt, Court.Surface, Court.Bg),
                        start = Offset(0f, 0f),
                        end = Offset(900f, 500f)
                    )
                )
                .border(1.dp, Court.Line, RoundedCornerShape(24.dp))
        ) {
            ShuttlecockArt(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 10.dp, end = 8.dp)
                    .size(width = 92.dp, height = 104.dp)
            )
            Column(Modifier.padding(22.dp)) {
                Text(
                    "Latest Session · " +
                        SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(Date(session.timestamp)),
                    color = Court.InkFaint,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        session.bestSpeedKph.roundToInt().toString(),
                        color = Court.Ink,
                        fontSize = 42.sp,
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
                    if (percentChange != null) {
                        Spacer(Modifier.width(10.dp))
                        DeltaPercentBadge(percentChange)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("Smash Speed", color = Court.InkDim, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun DeltaPercentBadge(percent: Double, modifier: Modifier = Modifier) {
    val isUp = percent >= 0.0
    val color = if (isUp) Court.Lime else Court.Coral
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 9.dp, vertical = 5.dp)
    ) {
        Text(
            "${if (isUp) "▲" else "▼"} ${if (isUp) "+" else ""}${percent.roundToInt()}%",
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun HomeMetricRow(session: SessionSummary) {
    val totalShots = session.smashCount + session.clearCount + session.dropCount + session.serveCount
    val avgShots = if (session.rallyCount > 0) totalShots.toDouble() / session.rallyCount else 0.0
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        CompactMetricTile("Rallies", session.rallyCount.toString(), Modifier.weight(1f))
        CompactMetricTile("Longest", session.longestRally.toString(), Modifier.weight(1f))
        CompactMetricTile("Avg Shots", String.format(Locale.getDefault(), "%.1f", avgShots), Modifier.weight(1f))
        CompactMetricTile(
            "BPM",
            if (session.avgHeartRate > 0.0) session.avgHeartRate.roundToInt().toString() else "—",
            Modifier.weight(1f)
        )
    }
}

@Composable
private fun CompactMetricTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(Court.Surface, RoundedCornerShape(14.dp))
            .border(1.dp, Court.Line, RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 12.dp)
    ) {
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Court.Ink, maxLines = 1)
        Spacer(Modifier.height(3.dp))
        Text(label, fontSize = 10.5.sp, color = Court.InkFaint, maxLines = 1)
    }
}

@Composable
private fun ShotBreakdownCard(session: SessionSummary) {
    val totalShots = session.smashCount + session.clearCount + session.dropCount + session.serveCount
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Court.Surface, RoundedCornerShape(20.dp))
            .border(1.dp, Court.Line, RoundedCornerShape(20.dp))
            .padding(18.dp)
    ) {
        Text("Shot Breakdown", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Court.Ink)
        Spacer(Modifier.height(12.dp))
        if (totalShots > 0) {
            ShotBreakdownBar(session, totalShots)
            Spacer(Modifier.height(10.dp))
            ShotLegend(session)
        } else {
            Text("No shots recorded for this session yet.", color = Court.InkFaint, fontSize = 12.sp)
        }
    }
}

/** Reused by ShotBreakdownCard here and by SessionDetailScreen (SessionDetail.kt). */
@Composable
internal fun ShotBreakdownBar(session: SessionSummary, totalShots: Int) {
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

/** Reused by ShotBreakdownCard here and by SessionDetailScreen (SessionDetail.kt). */
@Composable
internal fun ShotLegend(session: SessionSummary) {
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
private fun CaloriesRecoveryRow(session: SessionSummary) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        IconStatCard(
            modifier = Modifier.weight(1f),
            iconBg = Court.Coral.copy(alpha = 0.16f),
            icon = { FlameIcon(tint = Court.Coral, iconSize = 18.dp) },
            value = "${session.calories.roundToInt()} kcal",
            label = "Calories"
        )
        IconStatCard(
            modifier = Modifier.weight(1f),
            iconBg = Court.Lime.copy(alpha = 0.16f),
            icon = { HeartIcon(tint = Court.Lime, iconSize = 18.dp, pulse = true) },
            value = if (session.avgRecoveryBpm > 0.0) "${session.avgRecoveryBpm.roundToInt()} bpm" else "—",
            label = "Recovery"
        )
    }
}

@Composable
private fun IconStatCard(
    modifier: Modifier = Modifier,
    iconBg: Color,
    icon: @Composable () -> Unit,
    value: String,
    label: String
) {
    Row(
        modifier = modifier
            .background(Court.Surface, RoundedCornerShape(18.dp))
            .border(1.dp, Court.Line, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(iconBg),
            contentAlignment = Alignment.Center
        ) { icon() }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(value, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = Court.Ink, maxLines = 1)
            Text(label, fontSize = 11.sp, color = Court.InkFaint)
        }
    }
}

@Composable
private fun StartSessionButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Brush.horizontalGradient(listOf(Court.Lime, Color(0xFF9CF46B))))
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(50))
                .background(Court.Bg.copy(alpha = 0.85f)),
            contentAlignment = Alignment.Center
        ) {
            PlayTriangleIcon(tint = Court.Lime, iconSize = 13.dp)
        }
        Spacer(Modifier.width(10.dp))
        Text("Start New Session", color = Court.Bg, fontWeight = FontWeight.Bold, fontSize = 14.5.sp)
    }
}

@Composable
private fun AllTimeStatsHeader(onViewAll: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("All Time Stats", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = Court.Ink)
        Text(
            "View all",
            fontSize = 12.sp,
            color = Court.InkFaint,
            modifier = Modifier.clickable(onClick = onViewAll)
        )
    }
}

@Composable
private fun AllTimeStatsRow(sessions: List<SessionSummary>) {
    val totalSmashes = sessions.sumOf { it.smashCount }
    val bestEver = sessions.maxOfOrNull { it.bestSpeedKph }?.roundToInt() ?: 0
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        IconStatTile(
            icon = { TrophyIcon(Court.Gold, iconSize = 18.dp) },
            value = sessions.size.toString(),
            label = "Sessions",
            modifier = Modifier.weight(1f)
        )
        IconStatTile(
            icon = { HeartIcon(Court.Lime, iconSize = 18.dp) },
            value = totalSmashes.toString(),
            label = "Total Smashes",
            modifier = Modifier.weight(1f)
        )
        IconStatTile(
            icon = { BoltIcon(Court.Gold, iconSize = 18.dp) },
            value = "$bestEver kph",
            label = "Best Speed",
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun IconStatTile(icon: @Composable () -> Unit, value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Court.SurfaceAlt, RoundedCornerShape(16.dp))
            .border(1.dp, Court.Line, RoundedCornerShape(16.dp))
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        icon()
        Spacer(Modifier.height(6.dp))
        Text(value, fontWeight = FontWeight.Bold, color = Court.Ink, fontSize = 15.sp)
        Spacer(Modifier.height(2.dp))
        Text(label, fontSize = 10.5.sp, color = Court.InkFaint, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SessionRow(session: SessionSummary, onClick: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("dd MMM, h:mm a", Locale.getDefault()) }
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
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

// =============================================================================
// Sessions and Profile — the other two bottom-nav destinations.
// =============================================================================

@Composable
private fun AllSessionsScreen(sessions: List<SessionSummary>, onSessionClick: (SessionSummary) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item {
            Text(
                "All sessions",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Court.Ink,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
        items(sessions.size, key = { index -> sessions[index].timestamp }) { index ->
            SessionRow(sessions[index], onClick = { onSessionClick(sessions[index]) })
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun ProfileScreen(sessions: List<SessionSummary>) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        Box(
            modifier = Modifier.size(72.dp).clip(RoundedCornerShape(50)).background(Court.Periwinkle),
            contentAlignment = Alignment.Center
        ) {
            Text("M", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 26.sp)
        }
        Spacer(Modifier.height(14.dp))
        Text("Player", color = Court.Ink, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(4.dp))
        Text(
            "${sessions.size} session${if (sessions.size == 1) "" else "s"} logged",
            color = Court.InkFaint,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(28.dp))
        Text(
            "Profile settings aren't wired up yet — this screen is a placeholder for account details, " +
                "unit preferences and watch pairing.",
            color = Court.InkDim,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
    }
}
