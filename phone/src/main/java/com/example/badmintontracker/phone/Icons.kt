package com.example.badmintontracker.phone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// =============================================================================
// Small hand-drawn icon set for the redesigned phone UI (top bar, stat
// tiles, bottom nav, hero card). Plain Canvas paths on a normalized
// 0f..size.width / 0f..size.height grid — same technique as BrandMark and
// CourtIllustration in PhoneMainActivity.kt — rather than pulling in
// androidx.compose.material:material-icons-extended, which this module
// doesn't otherwise depend on (see phone/build.gradle.kts).
//
// Every icon takes `iconSize` (not `size`) for its Dp parameter name — the
// Canvas draw lambda's own DrawScope.size (pixel Size) would otherwise be
// shadowed by a same-named outer parameter.
// =============================================================================

@Composable
internal fun BellIcon(tint: Color, modifier: Modifier = Modifier, iconSize: Dp = 18.dp) {
    Canvas(modifier = modifier.size(iconSize)) {
        val w = size.width
        val h = size.height
        val body = Path().apply {
            moveTo(w * 0.5f, h * 0.06f)
            cubicTo(w * 0.24f, h * 0.06f, w * 0.22f, h * 0.34f, w * 0.22f, h * 0.5f)
            cubicTo(w * 0.22f, h * 0.66f, w * 0.14f, h * 0.71f, w * 0.09f, h * 0.78f)
            lineTo(w * 0.91f, h * 0.78f)
            cubicTo(w * 0.86f, h * 0.71f, w * 0.78f, h * 0.66f, w * 0.78f, h * 0.5f)
            cubicTo(w * 0.78f, h * 0.34f, w * 0.76f, h * 0.06f, w * 0.5f, h * 0.06f)
            close()
        }
        drawPath(body, color = tint, style = Stroke(width = 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawArc(
            color = tint,
            startAngle = 20f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(w * 0.37f, h * 0.78f),
            size = Size(w * 0.26f, h * 0.16f),
            style = Stroke(width = 1.6f, cap = StrokeCap.Round)
        )
    }
}

@Composable
internal fun FlameIcon(tint: Color, modifier: Modifier = Modifier, iconSize: Dp = 16.dp) {
    Canvas(modifier = modifier.size(iconSize)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.5f, h * 0.02f)
            cubicTo(w * 0.86f, h * 0.34f, w * 0.8f, h * 0.56f, w * 0.63f, h * 0.5f)
            cubicTo(w * 0.72f, h * 0.7f, w * 0.56f, h * 0.98f, w * 0.4f, h * 0.98f)
            cubicTo(w * 0.13f, h * 0.98f, w * 0.06f, h * 0.74f, w * 0.17f, h * 0.55f)
            cubicTo(w * 0.22f, h * 0.65f, w * 0.32f, h * 0.62f, w * 0.29f, h * 0.49f)
            cubicTo(w * 0.2f, h * 0.34f, w * 0.29f, h * 0.14f, w * 0.5f, h * 0.02f)
            close()
        }
        drawPath(path, color = tint)
    }
}

/** Plain outline heart; pass [pulse] to overlay a heart-rate squiggle (used for Recovery vs. Total Smashes). */
@Composable
internal fun HeartIcon(tint: Color, modifier: Modifier = Modifier, iconSize: Dp = 16.dp, pulse: Boolean = false) {
    Canvas(modifier = modifier.size(iconSize)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.5f, h * 0.9f)
            cubicTo(w * 0.08f, h * 0.56f, w * 0.02f, h * 0.24f, w * 0.27f, h * 0.11f)
            cubicTo(w * 0.42f, h * 0.03f, w * 0.5f, h * 0.19f, w * 0.5f, h * 0.3f)
            cubicTo(w * 0.5f, h * 0.19f, w * 0.58f, h * 0.03f, w * 0.73f, h * 0.11f)
            cubicTo(w * 0.98f, h * 0.24f, w * 0.92f, h * 0.56f, w * 0.5f, h * 0.9f)
            close()
        }
        drawPath(path, color = tint, style = Stroke(width = 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        if (pulse) {
            val line = Path().apply {
                moveTo(w * 0.1f, h * 0.48f)
                lineTo(w * 0.32f, h * 0.48f)
                lineTo(w * 0.42f, h * 0.26f)
                lineTo(w * 0.54f, h * 0.68f)
                lineTo(w * 0.64f, h * 0.48f)
                lineTo(w * 0.88f, h * 0.48f)
            }
            drawPath(line, color = tint, style = Stroke(width = 1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
internal fun TrophyIcon(tint: Color, modifier: Modifier = Modifier, iconSize: Dp = 16.dp) {
    Canvas(modifier = modifier.size(iconSize)) {
        val w = size.width
        val h = size.height
        val bowl = Path().apply {
            moveTo(w * 0.24f, h * 0.1f)
            lineTo(w * 0.76f, h * 0.1f)
            lineTo(w * 0.66f, h * 0.56f)
            cubicTo(w * 0.58f, h * 0.68f, w * 0.42f, h * 0.68f, w * 0.34f, h * 0.56f)
            close()
        }
        drawPath(bowl, color = tint)
        drawArc(
            color = tint,
            startAngle = 90f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.0f, h * 0.14f),
            size = Size(w * 0.26f, h * 0.3f),
            style = Stroke(width = 1.6f)
        )
        drawArc(
            color = tint,
            startAngle = -90f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.74f, h * 0.14f),
            size = Size(w * 0.26f, h * 0.3f),
            style = Stroke(width = 1.6f)
        )
        drawLine(tint, Offset(w * 0.5f, h * 0.67f), Offset(w * 0.5f, h * 0.8f), strokeWidth = 1.8f, cap = StrokeCap.Round)
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.26f, h * 0.8f),
            size = Size(w * 0.48f, h * 0.12f),
            cornerRadius = CornerRadius(2f, 2f)
        )
    }
}

@Composable
internal fun BoltIcon(tint: Color, modifier: Modifier = Modifier, iconSize: Dp = 16.dp) {
    Canvas(modifier = modifier.size(iconSize)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.56f, h * 0.02f)
            lineTo(w * 0.14f, h * 0.58f)
            lineTo(w * 0.44f, h * 0.58f)
            lineTo(w * 0.38f, h * 0.98f)
            lineTo(w * 0.86f, h * 0.38f)
            lineTo(w * 0.54f, h * 0.38f)
            close()
        }
        drawPath(path, color = tint)
    }
}

@Composable
internal fun GridIcon(tint: Color, modifier: Modifier = Modifier, iconSize: Dp = 16.dp) {
    Canvas(modifier = modifier.size(iconSize)) {
        val w = size.width
        val h = size.height
        val cell = w * 0.34f
        val gap = w * 0.12f
        for (row in 0..1) {
            for (col in 0..1) {
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.08f + col * (cell + gap), h * 0.08f + row * (cell + gap)),
                    size = Size(cell, cell),
                    cornerRadius = CornerRadius(2f, 2f),
                    style = Stroke(width = 1.4f)
                )
            }
        }
    }
}

@Composable
internal fun PlayTriangleIcon(tint: Color, modifier: Modifier = Modifier, iconSize: Dp = 14.dp) {
    Canvas(modifier = modifier.size(iconSize)) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.24f, h * 0.08f)
            lineTo(w * 0.9f, h * 0.5f)
            lineTo(w * 0.24f, h * 0.92f)
            close()
        }
        drawPath(path, color = tint)
    }
}

@Composable
internal fun HouseIcon(tint: Color, modifier: Modifier = Modifier, iconSize: Dp = 20.dp) {
    Canvas(modifier = modifier.size(iconSize)) {
        val w = size.width
        val h = size.height
        val roof = Path().apply {
            moveTo(w * 0.5f, h * 0.06f)
            lineTo(w * 0.92f, h * 0.42f)
            lineTo(w * 0.79f, h * 0.42f)
            lineTo(w * 0.79f, h * 0.9f)
            lineTo(w * 0.21f, h * 0.9f)
            lineTo(w * 0.21f, h * 0.42f)
            lineTo(w * 0.08f, h * 0.42f)
            close()
        }
        drawPath(roof, color = tint, style = Stroke(width = 1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.41f, h * 0.58f),
            size = Size(w * 0.18f, h * 0.32f),
            cornerRadius = CornerRadius(2f, 2f),
            style = Stroke(width = 1.4f)
        )
    }
}

@Composable
internal fun ClipboardIcon(tint: Color, modifier: Modifier = Modifier, iconSize: Dp = 20.dp) {
    Canvas(modifier = modifier.size(iconSize)) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.18f, h * 0.12f),
            size = Size(w * 0.64f, h * 0.8f),
            cornerRadius = CornerRadius(w * 0.06f, w * 0.06f),
            style = Stroke(width = 1.6f)
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.36f, h * 0.04f),
            size = Size(w * 0.28f, h * 0.14f),
            cornerRadius = CornerRadius(w * 0.03f, w * 0.03f)
        )
        listOf(0.36f, 0.52f, 0.68f).forEach { fy ->
            drawLine(tint, Offset(w * 0.3f, h * fy), Offset(w * 0.7f, h * fy), strokeWidth = 1.3f, cap = StrokeCap.Round)
        }
    }
}

@Composable
internal fun BarsIcon(tint: Color, modifier: Modifier = Modifier, iconSize: Dp = 20.dp) {
    Canvas(modifier = modifier.size(iconSize)) {
        val w = size.width
        val h = size.height
        val barW = w * 0.16f
        drawRoundRect(tint, topLeft = Offset(w * 0.12f, h * 0.55f), size = Size(barW, h * 0.35f), cornerRadius = CornerRadius(2f, 2f))
        drawRoundRect(tint, topLeft = Offset(w * 0.42f, h * 0.3f), size = Size(barW, h * 0.6f), cornerRadius = CornerRadius(2f, 2f))
        drawRoundRect(tint, topLeft = Offset(w * 0.72f, h * 0.12f), size = Size(barW, h * 0.78f), cornerRadius = CornerRadius(2f, 2f))
    }
}

@Composable
internal fun PersonIcon(tint: Color, modifier: Modifier = Modifier, iconSize: Dp = 20.dp) {
    Canvas(modifier = modifier.size(iconSize)) {
        val w = size.width
        val h = size.height
        drawCircle(tint, radius = w * 0.16f, center = Offset(w * 0.5f, h * 0.28f), style = Stroke(width = 1.6f))
        val shoulders = Path().apply {
            moveTo(w * 0.18f, h * 0.92f)
            cubicTo(w * 0.18f, h * 0.6f, w * 0.82f, h * 0.6f, w * 0.82f, h * 0.92f)
        }
        drawPath(shoulders, color = tint, style = Stroke(width = 1.6f, cap = StrokeCap.Round))
    }
}

/**
 * Stylized shuttlecock for the hero card — a dark cork base with a fan of
 * feather strokes, drawn from fixed fractional coordinates (no trig) to
 * match the hand-drawn look of CourtIllustration elsewhere in this file.
 */
@Composable
internal fun ShuttlecockArt(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val baseX = w * 0.46f
        val baseY = h * 0.86f
        val featherTint = Color(0xFFF2F2F0)

        // Fan of feathers, back-to-front so the front ones sit on top.
        val feathers = listOf(
            0.20f to 0.14f,
            0.28f to 0.05f,
            0.38f to 0.0f,
            0.5f to 0.02f,
            0.62f to 0.1f,
            0.7f to 0.22f
        )
        feathers.forEach { (tipXFrac, tipYFrac) ->
            val tipX = w * tipXFrac
            val tipY = h * tipYFrac
            val ctrlX = (baseX + tipX) / 2f + w * 0.07f
            val ctrlY = (baseY + tipY) / 2f
            val path = Path().apply {
                moveTo(baseX, baseY)
                quadraticBezierTo(ctrlX, ctrlY, tipX, tipY)
            }
            drawPath(path, color = featherTint.copy(alpha = 0.92f), style = Stroke(width = 3.2f, cap = StrokeCap.Round))
        }
        // A couple of thin cross-ribs for texture.
        drawLine(
            featherTint.copy(alpha = 0.5f),
            Offset(baseX - w * 0.02f, baseY - h * 0.18f),
            Offset(baseX + w * 0.28f, baseY - h * 0.3f),
            strokeWidth = 1.2f
        )
        drawLine(
            featherTint.copy(alpha = 0.5f),
            Offset(baseX - w * 0.04f, baseY - h * 0.34f),
            Offset(baseX + w * 0.22f, baseY - h * 0.5f),
            strokeWidth = 1.2f
        )
        // Cork base.
        drawOval(
            color = Color(0xFFECECEA),
            topLeft = Offset(baseX - w * 0.13f, baseY - h * 0.05f),
            size = Size(w * 0.26f, h * 0.13f)
        )
        drawArc(
            color = Color(0xFF1C1C1C),
            startAngle = 10f,
            sweepAngle = 160f,
            useCenter = false,
            topLeft = Offset(baseX - w * 0.13f, baseY - h * 0.01f),
            size = Size(w * 0.26f, h * 0.1f),
            style = Stroke(width = 2f)
        )
    }
}
