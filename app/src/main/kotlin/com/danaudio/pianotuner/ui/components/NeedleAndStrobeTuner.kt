package com.danaudio.pianotuner.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danaudio.pianotuner.dsp.PianoNotes
import com.danaudio.pianotuner.dsp.PitchAnalysisResult
import com.danaudio.pianotuner.dsp.UnisonHealth
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun NeedleAndStrobeTuner(
  activeKeyIndex: Int,
  targetHz: Double,
  stretchOffsetCents: Double,
  overpullOffsetCents: Double,
  toleranceCents: Double,
  pitchResult: PitchAnalysisResult,
  inTuneHoldProgress: Float,
  modifier: Modifier = Modifier,
) {
  val keyInfo = PianoNotes.keyInfo(activeKeyIndex)
  val hasSignal = pitchResult.hasSignal
  val cents = if (hasSignal) pitchResult.smoothedCentsError else 0.0
  val isInTune = hasSignal && abs(cents) <= toleranceCents

  val needleAngleDeg by animateFloatAsState(
    targetValue = if (hasSignal) (cents.coerceIn(-50.0, 50.0) / 50.0 * 52.0).toFloat() else 0f,
    animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy),
    label = "needleAngle",
  )

  val statusColor = when {
    !hasSignal -> Color(0xFF64748B)
    isInTune -> Color(0xFF22C55E)
    abs(cents) <= toleranceCents * 3.0 -> Color(0xFFFACC15)
    else -> Color(0xFFEF4444)
  }

  Column(
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(16.dp))
      .background(
        Brush.verticalGradient(
          colors = listOf(Color(0xFF131926), Color(0xFF0C111B))
        )
      )
      .border(1.dp, Color(0xFF243047), RoundedCornerShape(16.dp))
      .padding(horizontal = 12.dp, vertical = 10.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    // Top Note & Hz Readout Row
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = keyInfo.noteName,
            fontSize = 32.sp,
            fontWeight = FontWeight.ExtraBold,
            color = if (hasSignal) statusColor else Color(0xFFE2E8F0),
          )
          Spacer(Modifier.width(8.dp))
          Column {
            Text(
              text = "${keyInfo.register.label} • ${keyInfo.typicalStringCount}str",
              fontSize = 11.sp,
              color = Color(0xFF94A3B8),
            )
            Text(
              text = "Target ${String.format(Locale.US, "%.2f", targetHz)} Hz",
              fontSize = 11.sp,
              fontFamily = FontFamily.Monospace,
              color = Color(0xFF38BDF8),
            )
          }
        }
      }

      Column(horizontalAlignment = Alignment.End) {
        val centsLabel = if (hasSignal) {
          String.format(Locale.US, "%+.1f ¢", cents)
        } else {
          "— ¢"
        }
        Text(
          text = centsLabel,
          fontSize = 26.sp,
          fontWeight = FontWeight.Bold,
          fontFamily = FontFamily.Monospace,
          color = statusColor,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          if (pitchResult.isTransientHold) {
            BadgePill("HOLD", Color(0xFF8B5CF6))
          }
          if (overpullOffsetCents > 0.1) {
            BadgePill(String.format(Locale.US, "OP %+.1f¢", overpullOffsetCents), Color(0xFFF97316))
          }
          Text(
            text = String.format(Locale.US, "Str %+.1f¢", stretchOffsetCents),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFF94A3B8),
          )
        }
      }
    }

    Spacer(Modifier.height(4.dp))

    // 1. ANALOG NEEDLE METER
    Canvas(
      modifier = Modifier
        .fillMaxWidth()
        .height(118.dp)
    ) {
      val w = size.width
      val h = size.height
      val pivot = Offset(w * 0.5f, h * 0.94f)
      val radius = minOf(w * 0.44f, h * 0.84f)

      // Outer arc track
      drawArc(
        color = Color(0xFF1E293B),
        startAngle = 215f,
        sweepAngle = 110f,
        useCenter = false,
        topLeft = Offset(pivot.x - radius, pivot.y - radius),
        size = Size(radius * 2f, radius * 2f),
        style = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round),
      )

      // Tolerance green center arc
      val tolSweep = ((toleranceCents / 50.0) * 52.0).toFloat().coerceIn(2f, 20f)
      drawArc(
        color = Color(0xFF22C55E).copy(alpha = 0.75f),
        startAngle = 270f - tolSweep,
        sweepAngle = tolSweep * 2f,
        useCenter = false,
        topLeft = Offset(pivot.x - radius, pivot.y - radius),
        size = Size(radius * 2f, radius * 2f),
        style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round),
      )

      // Tick marks from -50 to +50 cents
      for (c in -50..50 step 5) {
        val deg = (c / 50.0) * 52.0
        val rad = Math.toRadians(deg - 90.0)
        val isMajor = c % 10 == 0
        val isCenter = c == 0
        val innerR = radius - (if (isCenter) 22.dp.toPx() else if (isMajor) 16.dp.toPx() else 10.dp.toPx())
        val outerR = radius - 3.dp.toPx()
        val p1 = Offset(pivot.x + (cos(rad) * innerR).toFloat(), pivot.y + (sin(rad) * innerR).toFloat())
        val p2 = Offset(pivot.x + (cos(rad) * outerR).toFloat(), pivot.y + (sin(rad) * outerR).toFloat())
        val tickColor = when {
          isCenter -> Color(0xFF22C55E)
          abs(c) <= 10 -> Color(0xFFCBD5E1)
          else -> Color(0xFF64748B)
        }
        drawLine(
          color = tickColor,
          start = p1,
          end = p2,
          strokeWidth = if (isCenter) 3.dp.toPx() else if (isMajor) 2.dp.toPx() else 1.2.dp.toPx(),
          cap = StrokeCap.Round,
        )
      }

      // Fine 1-cent ticks inside +/-10
      for (c in -10..10) {
        if (c % 5 == 0) continue
        val rad = Math.toRadians((c / 50.0) * 52.0 - 90.0)
        val innerR = radius - 8.dp.toPx()
        val outerR = radius - 3.dp.toPx()
        drawLine(
          color = Color(0xFF475569),
          start = Offset(pivot.x + (cos(rad) * innerR).toFloat(), pivot.y + (sin(rad) * innerR).toFloat()),
          end = Offset(pivot.x + (cos(rad) * outerR).toFloat(), pivot.y + (sin(rad) * outerR).toFloat()),
          strokeWidth = 1.dp.toPx(),
        )
      }

      // Needle
      val nRad = Math.toRadians(needleAngleDeg.toDouble() - 90.0)
      val tip = Offset(
        pivot.x + (cos(nRad) * (radius - 6.dp.toPx())).toFloat(),
        pivot.y + (sin(nRad) * (radius - 6.dp.toPx())).toFloat(),
      )
      // Glow shadow behind needle
      drawLine(
        color = statusColor.copy(alpha = if (hasSignal) 0.35f else 0.1f),
        start = pivot,
        end = tip,
        strokeWidth = 8.dp.toPx(),
        cap = StrokeCap.Round,
      )
      drawLine(
        color = if (hasSignal) statusColor else Color(0xFF475569),
        start = pivot,
        end = tip,
        strokeWidth = 3.2.dp.toPx(),
        cap = StrokeCap.Round,
      )
      drawCircle(color = Color(0xFF0F172A), radius = 9.dp.toPx(), center = pivot)
      drawCircle(color = statusColor, radius = 5.dp.toPx(), center = pivot)
    }

    // Scale legend & Detected Hz
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text("-50¢ FLAT", fontSize = 10.sp, color = Color(0xFF64748B))
      Text(
        text = if (hasSignal) "Det ${String.format(Locale.US, "%.2f", pitchResult.detectedHz)} Hz" else "Play any piano key",
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        color = Color(0xFFCBD5E1),
      )
      Text("+50¢ SHARP", fontSize = 10.sp, color = Color(0xFF64748B))
    }

    Spacer(Modifier.height(8.dp))

    // 2. STROBE TUNER STRIP BAR directly under the needle
    StrobeStripBar(
      hasSignal = hasSignal,
      centsError = cents,
      toleranceCents = toleranceCents,
      partialStrengths = pitchResult.partialStrengths,
      modifier = Modifier.fillMaxWidth(),
    )

    if (inTuneHoldProgress > 0.05f) {
      Spacer(Modifier.height(6.dp))
      LinearProgressIndicator(
        progress = { inTuneHoldProgress },
        modifier = Modifier
          .fillMaxWidth()
          .height(4.dp)
          .clip(RoundedCornerShape(2.dp)),
        color = Color(0xFF22C55E),
        trackColor = Color(0xFF1E293B),
      )
    }

    Spacer(Modifier.height(8.dp))

    // 3. UNISON / STRING CHECK BAR + PARTIAL STRENGTHS
    UnisonAndPartialRow(
      pitchResult = pitchResult,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

@Composable
fun StrobeStripBar(
  hasSignal: Boolean,
  centsError: Double,
  toleranceCents: Double,
  partialStrengths: FloatArray,
  modifier: Modifier = Modifier,
) {
  var phase by remember { mutableFloatStateOf(0f) }

  LaunchedEffect(hasSignal, centsError) {
    var lastNanos = 0L
    while (true) {
      withFrameNanos { now ->
        if (lastNanos != 0L) {
          val dt = ((now - lastNanos) / 1_000_000_000.0).toFloat().coerceAtMost(0.05f)
          if (hasSignal) {
            val effCents = if (abs(centsError) <= toleranceCents * 0.4) 0.0 else centsError
            // Scrolls left when flat (-), right when sharp (+), locked stationary at 0
            val cyclesPerSec = (effCents.coerceIn(-60.0, 60.0) * 0.16).toFloat()
            phase = (phase + cyclesPerSec * dt) % 100f
          }
        }
        lastNanos = now
      }
    }
  }

  val isLocked = hasSignal && abs(centsError) <= toleranceCents

  Column(
    modifier = modifier
      .clip(RoundedCornerShape(10.dp))
      .background(Color(0xFF070B12))
      .border(
        width = 1.dp,
        color = if (isLocked) Color(0xFF22C55E) else Color(0xFF1E293B),
        shape = RoundedCornerShape(10.dp),
      )
      .padding(6.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "PHASE STROBE BAR (1x / 2x / 4x)",
        fontSize = 9.sp,
        fontWeight = FontWeight.SemiBold,
        color = Color(0xFF94A3B8),
      )
      Text(
        text = when {
          !hasSignal -> "STANDBY"
          isLocked -> "LOCKED IN TUNE"
          centsError < 0 -> "◄ DRIFTING FLAT"
          else -> "DRIFTING SHARP ►"
        },
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        color = when {
          !hasSignal -> Color(0xFF64748B)
          isLocked -> Color(0xFF22C55E)
          else -> Color(0xFFFACC15)
        },
      )
    }

    Spacer(Modifier.height(4.dp))

    Canvas(
      modifier = Modifier
        .fillMaxWidth()
        .height(34.dp)
        .clip(RoundedCornerShape(6.dp))
        .background(Color(0xFF04060A))
    ) {
      val bandCount = 3
      val bandH = size.height / bandCount
      val harmonics = floatArrayOf(1f, 2f, 4f)
      val segmentsPerBand = intArrayOf(10, 16, 24)
      val baseColor = when {
        !hasSignal -> Color(0xFF334155)
        isLocked -> Color(0xFF22C55E)
        abs(centsError) <= 8.0 -> Color(0xFF38BDF8)
        else -> Color(0xFFF59E0B)
      }

      for (b in 0 until bandCount) {
        val segs = segmentsPerBand[b]
        val period = size.width / segs
        val shift = ((phase * harmonics[b]) % 1f) * period
        val pStrength = if (b == 0) 1f else partialStrengths.getOrElse(b) { 0.4f }.coerceIn(0.25f, 1f)
        val alpha = if (hasSignal) (0.35f + 0.65f * pStrength) else 0.18f

        val yTop = b * bandH + 1.5.dp.toPx()
        val rectH = bandH - 3.dp.toPx()

        for (i in -2..(segs + 2)) {
          val x = i * period + shift
          drawRoundRect(
            color = baseColor.copy(alpha = alpha),
            topLeft = Offset(x, yTop),
            size = Size(period * 0.48f, rectH),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
          )
        }
      }

      // Center alignment reticle
      drawLine(
        color = Color.White.copy(alpha = 0.55f),
        start = Offset(size.width * 0.5f, 0f),
        end = Offset(size.width * 0.5f, size.height),
        strokeWidth = 1.5.dp.toPx(),
      )
    }
  }
}

@Composable
private fun UnisonAndPartialRow(
  pitchResult: PitchAnalysisResult,
  modifier: Modifier = Modifier,
) {
  val unison = pitchResult.unison
  val unisonBg = when (unison.health) {
    UnisonHealth.CLEAN, UnisonHealth.SINGLE_STRING -> Color(0xFF064E3B).copy(alpha = 0.6f)
    UnisonHealth.SLIGHT_BEAT -> Color(0xFF78350F).copy(alpha = 0.6f)
    UnisonHealth.STRINGS_OUT -> Color(0xFF7F1D1D).copy(alpha = 0.75f)
    UnisonHealth.IDLE -> Color(0xFF1E293B).copy(alpha = 0.6f)
  }
  val unisonFg = when (unison.health) {
    UnisonHealth.CLEAN, UnisonHealth.SINGLE_STRING -> Color(0xFF4ADE80)
    UnisonHealth.SLIGHT_BEAT -> Color(0xFFFACC15)
    UnisonHealth.STRINGS_OUT -> Color(0xFFFCA5A5)
    UnisonHealth.IDLE -> Color(0xFF94A3B8)
  }

  Row(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // Unison String Check Pill
    Column(
      modifier = Modifier
        .weight(1.5f)
        .clip(RoundedCornerShape(8.dp))
        .background(unisonBg)
        .border(1.dp, unisonFg.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
        .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
      Text(
        text = "STRING UNISON: ${unison.health.label.uppercase()}",
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = unisonFg,
      )
      Text(
        text = unison.detailMessage,
        fontSize = 10.sp,
        color = Color(0xFFE2E8F0),
        maxLines = 1,
      )
    }

    // Partials 1..6 Mini Meters
    Row(
      modifier = Modifier
        .weight(1f)
        .clip(RoundedCornerShape(8.dp))
        .background(Color(0xFF111827))
        .padding(horizontal = 6.dp, vertical = 4.dp),
      horizontalArrangement = Arrangement.SpaceEvenly,
      verticalAlignment = Alignment.Bottom,
    ) {
      for (i in 0 until 6) {
        val h = pitchResult.partialStrengths.getOrElse(i) { 0f }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
          Box(
            modifier = Modifier
              .width(7.dp)
              .height(20.dp)
              .clip(RoundedCornerShape(2.dp))
              .background(Color(0xFF1E293B)),
            contentAlignment = Alignment.BottomCenter,
          ) {
            Box(
              modifier = Modifier
                .width(7.dp)
                .height((20f * h).dp)
                .background(Color(0xFF38BDF8)),
            )
          }
          Text("${i + 1}", fontSize = 8.sp, color = Color(0xFF94A3B8))
        }
      }
    }
  }
}

@Composable
private fun BadgePill(text: String, color: Color) {
  Text(
    text = text,
    fontSize = 9.sp,
    fontWeight = FontWeight.Bold,
    color = Color.White,
    modifier = Modifier
      .clip(RoundedCornerShape(4.dp))
      .background(color.copy(alpha = 0.85f))
      .padding(horizontal = 5.dp, vertical = 1.dp),
  )
}
