package com.example.badmintontracker.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val SamsungBlue = Color(0xFF1B63D6)
private val SamsungBlueDark = Color(0xFF0E3E8F)
private val SamsungOrange = Color(0xFFFF7A1A)
private val SamsungTeal = Color(0xFF00BFA6)
private val SamsungPurple = Color(0xFF8E5CE0)
private val SurfaceLight = Color(0xFFF4F6FB)

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
                colorScheme = lightColorScheme(
                    primary = SamsungBlue,
                    secondary = SamsungOrange,
                    background = SurfaceLight,
                    surface = Color.White
                )
            ) {
                Surface(color = SurfaceLight, modifier = Modifier.fillMaxSize()) {
                    Scaffold(
                        containerColor = SurfaceLight,
                        topBar = {
                            TopAppBar(
                                title = {
                                    Text(
                                        "🏸 Badminton Tracker",
                                        fontWeight = FontWeight.Bold
                                    )
                                },
                                colors = TopAppBarDefaults.topAppBarColors(
                                    containerColor = SurfaceLight
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
                                item { OverallSummaryRow(sessions.value) }
                                item {
                                    Text(
                                        "History",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(top = 6.dp)
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

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🏸", fontSize = 56.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                "No sessions yet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Play a match with your watch on — it'll show up here automatically.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )
        }
    }
}

@Composable
private fun LatestSessionHero(session: SessionSummary) {
    Card(
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(
            Modifier
                .background(Brush.linearGradient(listOf(SamsungBlue, SamsungBlueDark)))
                .padding(22.dp)
        ) {
            Text("Latest session", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
            Text(
                "${session.bestSpeedKph.roundToInt()} kph",
                color = Color.White,
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                SimpleDateFormat("dd MMM, h:mm a", Locale.getDefault()).format(Date(session.timestamp)),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp
            )
            Spacer(Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                StatColumn("💥", session.smashCount.toString(), "Smash")
                StatColumn("↗\uFE0F", session.clearCount.toString(), "Clear")
                StatColumn("🪶", session.dropCount.toString(), "Drop")
                StatColumn("❤\uFE0F", session.avgHeartRate.roundToInt().toString(), "bpm")
                StatColumn("🔥", session.calories.roundToInt().toString(), "kcal")
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatColumn("🎯", session.serveCount.toString(), "Serve")
                StatColumn("🔁", session.rallyCount.toString(), "Rallies")
                StatColumn("📈", session.longestRally.toString(), "Longest")
            }
        }
    }
}

@Composable
private fun OverallSummaryRow(sessions: List<SessionSummary>) {
    val totalSmashes = sessions.sumOf { it.smashCount }
    val bestEver = sessions.maxOfOrNull { it.bestSpeedKph }?.roundToInt() ?: 0
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SummaryPill(
            modifier = Modifier.weight(1f),
            emoji = "📅",
            value = sessions.size.toString(),
            label = "Sessions",
            accent = SamsungTeal
        )
        SummaryPill(
            modifier = Modifier.weight(1f),
            emoji = "💥",
            value = totalSmashes.toString(),
            label = "Total smashes",
            accent = SamsungOrange
        )
        SummaryPill(
            modifier = Modifier.weight(1f),
            emoji = "🏆",
            value = "$bestEver kph",
            label = "Best ever",
            accent = SamsungPurple
        )
    }
}

@Composable
private fun SummaryPill(modifier: Modifier, emoji: String, value: String, label: String, accent: Color) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 14.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(emoji, fontSize = 18.sp)
            Text(value, fontWeight = FontWeight.Bold, color = accent, fontSize = 16.sp)
            Text(label, fontSize = 11.sp, color = Color.Gray)
        }
    }
}

@Composable
private fun StatColumn(emoji: String, value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(emoji, fontSize = 18.sp)
        Text(value, color = Color.White, fontWeight = FontWeight.Bold)
        Text(label, color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp)
    }
}

@Composable
private fun SessionRow(session: SessionSummary) {
    val dateFormat = remember { SimpleDateFormat("dd MMM, h:mm a", Locale.getDefault()) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(dateFormat.format(Date(session.timestamp)), fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(2.dp))
                Text("💥${session.smashCount}  ↗\uFE0F${session.clearCount}  🪶${session.dropCount}")
                Text(
                    "🎯${session.serveCount} serves  •  🔁${session.rallyCount} rallies  •  " +
                        "📈longest ${session.longestRally}",
                    color = SamsungPurple,
                    fontSize = 12.sp
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(SamsungBlue.copy(alpha = 0.1f))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    "${session.bestSpeedKph.roundToInt()} kph",
                    fontWeight = FontWeight.Bold,
                    color = SamsungBlue
                )
            }
        }
    }
}
