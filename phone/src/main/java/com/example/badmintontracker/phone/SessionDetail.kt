package com.example.badmintontracker.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Shot-by-shot breakdown of a single session — reached by tapping a session
 * in HomeScreen (LatestSessionHero or a SessionRow). This is what actually
 * answers "which smash was fastest and exactly when": the session summary
 * elsewhere only ever shows one aggregate bestSpeedKph number.
 *
 * The "By time" order also answers the fatigue question for free — the
 * gold-highlighted personal-best row sitting near the top of the list vs.
 * near the bottom tells you whether your peak came fresh or late-session,
 * without needing any separate analysis.
 */
@Composable
fun SessionDetailScreen(session: SessionSummary, onBack: () -> Unit) {
    var sortBySpeed by remember { mutableStateOf(false) }
    val totalShots = session.smashCount + session.clearCount + session.dropCount + session.serveCount
    val dateFormat = remember { SimpleDateFormat("EEEE, d MMM yyyy · h:mm a", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(onClick = onBack)
            ) {
                Text("←", color = Court.Lime, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text("Back", color = Court.Lime, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        item {
            Column {
                Text("Session detail", color = Court.InkFaint, fontSize = 11.5.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    dateFormat.format(Date(session.timestamp)),
                    color = Court.Ink,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                if (totalShots > 0) {
                    Spacer(Modifier.height(12.dp))
                    ShotBreakdownBar(session, totalShots)
                    Spacer(Modifier.height(8.dp))
                    ShotLegend(session)
                }
            }
        }

        if (session.shots.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Court.Surface, RoundedCornerShape(20.dp))
                        .border(1.dp, Court.Line, RoundedCornerShape(20.dp))
                        .padding(20.dp)
                ) {
                    Text(
                        "No shot-by-shot log for this session",
                        color = Court.Ink,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "This session was recorded before the Shot Log feature existed, so only the totals above are available.",
                        color = Court.InkFaint,
                        fontSize = 12.sp
                    )
                }
            }
        } else {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SortChip("By time", selected = !sortBySpeed, onClick = { sortBySpeed = false })
                    SortChip("By speed", selected = sortBySpeed, onClick = { sortBySpeed = true })
                }
            }

            val bestSpeed = session.shots.maxOf { it.speedKph }
            val ordered = if (sortBySpeed) {
                session.shots.sortedByDescending { it.speedKph }
            } else {
                session.shots.sortedBy { it.timestampMillis }
            }

            itemsIndexed(ordered) { index, shot ->
                ShotLogRow(
                    shot = shot,
                    // "Shot N of total" is a timeline position, so it only
                    // makes sense in chronological order — in speed order
                    // the same number would be a rank, not a moment.
                    positionLabel = if (!sortBySpeed) "Shot ${index + 1} of ${ordered.size}" else "#${index + 1} fastest",
                    isBest = shot.speedKph == bestSpeed,
                    timeFormat = timeFormat
                )
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun SortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Court.Lime.copy(alpha = 0.16f) else Court.Surface)
            .border(1.dp, if (selected) Court.Lime else Court.Line, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) Court.Lime else Court.InkDim
        )
    }
}

@Composable
private fun ShotLogRow(shot: ShotLogEntry, positionLabel: String, isBest: Boolean, timeFormat: SimpleDateFormat) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isBest) Court.Gold.copy(alpha = 0.10f) else Color.Transparent,
                RoundedCornerShape(14.dp)
            )
            .border(
                1.dp,
                if (isBest) Court.Gold.copy(alpha = 0.5f) else Court.Line,
                RoundedCornerShape(14.dp)
            )
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(50))
                .background(colorForShotType(shot.type))
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(shot.type, color = Court.Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                if (isBest) {
                    Spacer(Modifier.width(6.dp))
                    Text("PB", color = Court.Gold, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                }
            }
            Text(
                "${timeFormat.format(Date(shot.timestampMillis))} · $positionLabel",
                color = Court.InkFaint,
                fontSize = 11.sp
            )
        }
        Text(
            "${shot.speedKph.roundToInt()} kph",
            color = if (isBest) Court.Gold else Court.Ink,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.5.sp
        )
    }
}

private fun colorForShotType(type: String): Color = when (type) {
    "Smash" -> Court.Lime
    "Clear" -> Court.Gold
    "Drop" -> Court.SkyBlue
    "Serve" -> Court.Coral
    else -> Court.InkFaint
}
