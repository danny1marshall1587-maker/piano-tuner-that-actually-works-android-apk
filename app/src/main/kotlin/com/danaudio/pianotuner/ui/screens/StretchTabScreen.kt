package com.danaudio.pianotuner.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danaudio.pianotuner.dsp.PianoNotes
import com.danaudio.pianotuner.dsp.PianoScaleType
import com.danaudio.pianotuner.dsp.SweepStepMode
import com.danaudio.pianotuner.dsp.TemperamentType
import com.danaudio.pianotuner.ui.TunerUiState
import com.danaudio.pianotuner.ui.TunerViewModel
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun StretchTabScreen(
  state: TunerUiState,
  viewModel: TunerViewModel,
  modifier: Modifier = Modifier,
) {
  val profile = state.activeProfile
  val curve = state.stretchCurve

  Column(
    modifier = modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // --- 1. A4 REFERENCE PITCH & TRANSPOSE ---
    CardSection(title = "REFERENCE PITCH & TRANSPOSITION") {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "A4 = ${String.format(Locale.US, "%.1f", profile.a4TargetHz)} Hz",
          fontSize = 18.sp,
          fontWeight = FontWeight.ExtraBold,
          fontFamily = FontFamily.Monospace,
          color = Color(0xFF38BDF8),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          OutlinedButton(
            onClick = {
              viewModel.updateActiveProfileCalibration {
                it.copy(a4TargetHz = (it.a4TargetHz - 0.1).coerceIn(415.0, 466.0))
              }
            },
            contentPadding = PaddingValues(horizontal = 10.dp),
            modifier = Modifier.height(32.dp),
          ) { Text("-0.1", fontSize = 11.sp, color = Color.White) }

          OutlinedButton(
            onClick = {
              viewModel.updateActiveProfileCalibration {
                it.copy(a4TargetHz = (it.a4TargetHz + 0.1).coerceIn(415.0, 466.0))
              }
            },
            contentPadding = PaddingValues(horizontal = 10.dp),
            modifier = Modifier.height(32.dp),
          ) { Text("+0.1", fontSize = 11.sp, color = Color.White) }
        }
      }

      Slider(
        value = profile.a4TargetHz.toFloat(),
        onValueChange = { hz ->
          val snapped = (hz * 10.0).roundToInt() / 10.0
          viewModel.updateActiveProfileCalibration { it.copy(a4TargetHz = snapped) }
        },
        valueRange = 415f..466f,
        modifier = Modifier.height(28.dp),
      )

      // Quick Pitch Presets
      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        listOf(415.0, 432.0, 435.0, 440.0, 442.0, 443.0, 444.0).forEach { hz ->
          val sel = kotlin.math.abs(profile.a4TargetHz - hz) < 0.05
          ChipButton(
            text = "${hz.toInt()} Hz",
            selected = sel,
            onClick = { viewModel.updateActiveProfileCalibration { it.copy(a4TargetHz = hz) } },
          )
        }
      }

      Text(
        text = "Pitch Shift / Transpose: ${String.format(Locale.US, "%+.0f", profile.transposeCents)}¢",
        fontSize = 11.sp,
        color = Color(0xFFE2E8F0),
      )
      Slider(
        value = profile.transposeCents.toFloat(),
        onValueChange = { c ->
          viewModel.updateActiveProfileCalibration { it.copy(transposeCents = c.roundToInt().toDouble()) }
        },
        valueRange = -200f..200f,
        modifier = Modifier.height(28.dp),
      )
    }

    // --- 2. RAILSBACK STRETCH CURVE VISUALIZER ---
    CardSection(title = "RAILSBACK STRETCH CURVE (A0 – C8)") {
      val measuredBCount = profile.keyRecords.values.count { it.measuredInharmonicityB != null }
      Text(
        text = "Inharmonicity anchors measured: $measuredBCount keys • Active ${PianoNotes.keyInfo(state.activeKeyIndex).noteName} = ${String.format(Locale.US, "%+.1f¢", curve.stretchOffsetsCents[state.activeKeyIndex])} (B=${String.format(Locale.US, "%.5f", curve.inharmonicityCurveB[state.activeKeyIndex])})",
        fontSize = 11.sp,
        color = Color(0xFF94A3B8),
      )

      Canvas(
        modifier = Modifier
          .fillMaxWidth()
          .height(128.dp)
          .clip(RoundedCornerShape(10.dp))
          .background(Color(0xFF070B12))
          .padding(8.dp)
      ) {
        val w = size.width
        val h = size.height
        val zeroY = h * 0.55f // -40..+45 cents range
        val maxCents = 45.0

        // Horizontal grid (-20, 0, +20, +40)
        listOf(-20.0, 0.0, 20.0, 40.0).forEach { c ->
          val y = zeroY - (c / maxCents * (h * 0.45f)).toFloat()
          drawLine(
            color = if (c == 0.0) Color(0xFF475569) else Color(0xFF1E293B),
            start = Offset(0f, y),
            end = Offset(w, y),
            strokeWidth = if (c == 0.0) 1.5.dp.toPx() else 1.dp.toPx(),
          )
        }

        // Stretch curve line
        val path = Path()
        for (k in 0 until PianoNotes.TOTAL_KEYS) {
          val x = (k.toFloat() / (PianoNotes.TOTAL_KEYS - 1)) * w
          val c = curve.stretchOffsetsCents[k].coerceIn(-45.0, 50.0)
          val y = zeroY - (c / maxCents * (h * 0.45f)).toFloat()
          if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = Color(0xFF38BDF8), style = Stroke(width = 2.5.dp.toPx()))

        // Yellow dots for keys with measured inharmonicity B
        profile.keyRecords.forEach { (k, rec) ->
          if (rec.measuredInharmonicityB != null) {
            val x = (k.toFloat() / (PianoNotes.TOTAL_KEYS - 1)) * w
            val c = curve.stretchOffsetsCents[k].coerceIn(-45.0, 50.0)
            val y = zeroY - (c / maxCents * (h * 0.45f)).toFloat()
            drawCircle(color = Color(0xFFFACC15), radius = 4.dp.toPx(), center = Offset(x, y))
          }
        }

        // Red marker for active key
        val ak = state.activeKeyIndex
        val ax = (ak.toFloat() / (PianoNotes.TOTAL_KEYS - 1)) * w
        val ac = curve.stretchOffsetsCents[ak].coerceIn(-45.0, 50.0)
        val ay = zeroY - (ac / maxCents * (h * 0.45f)).toFloat()
        drawCircle(color = Color(0xFFEF4444), radius = 5.5.dp.toPx(), center = Offset(ax, ay))
      }

      Text("Sweep Step (Record Every Nth Note):", fontSize = 11.sp, color = Color(0xFFE2E8F0))
      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        SweepStepMode.entries.forEach { mode ->
          ChipButton(
            text = mode.label,
            selected = profile.sweepStepMode == mode,
            onClick = { viewModel.updateActiveProfileCalibration { it.copy(sweepStepMode = mode) } },
          )
        }
      }

      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("Auto-Advance on Record (Yellow)", fontSize = 11.sp, color = Color.White)
        Switch(checked = state.autoAdvanceSweep, onCheckedChange = { viewModel.toggleAutoAdvanceSweep() })
      }
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("Auto-Register In Tune (Green) when steady", fontSize = 11.sp, color = Color.White)
        Switch(checked = state.autoLockInTune, onCheckedChange = { viewModel.toggleAutoLockInTune() })
      }

      Text(
        text = "In-Tune Tolerance: ±${String.format(Locale.US, "%.1f", profile.inTuneToleranceCents)}¢",
        fontSize = 11.sp,
        color = Color(0xFFE2E8F0),
      )
      Slider(
        value = profile.inTuneToleranceCents.toFloat(),
        onValueChange = { t ->
          val snapped = (t * 10.0).roundToInt() / 10.0
          viewModel.updateActiveProfileCalibration { it.copy(inTuneToleranceCents = snapped) }
        },
        valueRange = 0.5f..5.0f,
        modifier = Modifier.height(28.dp),
      )
    }

    // --- 3. STRETCH AMOUNT, SCALE TYPE & TEMPERAMENT ---
    CardSection(title = "PIANO SCALE, STRETCH & TEMPERAMENT") {
      Text("Piano Scale Model (Default Inharmonicity Prior):", fontSize = 11.sp, color = Color(0xFFE2E8F0))
      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        PianoScaleType.entries.forEach { sc ->
          ChipButton(
            text = sc.label,
            selected = profile.scaleType == sc,
            onClick = { viewModel.updateActiveProfileCalibration { it.copy(scaleType = sc) } },
          )
        }
      }

      Text(
        text = "Stretch Strength: ${(profile.stretchFactor * 100).roundToInt()}%",
        fontSize = 11.sp,
        color = Color(0xFFE2E8F0),
      )
      Slider(
        value = profile.stretchFactor.toFloat(),
        onValueChange = { f -> viewModel.updateActiveProfileCalibration { it.copy(stretchFactor = f.toDouble()) } },
        valueRange = 0f..1.35f,
        modifier = Modifier.height(28.dp),
      )

      Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(modifier = Modifier.weight(1f)) {
          Text("Bass Bias: ${(profile.bassStretchBias * 100).roundToInt()}%", fontSize = 10.sp, color = Color(0xFF94A3B8))
          Slider(
            value = profile.bassStretchBias.toFloat(),
            onValueChange = { b -> viewModel.updateActiveProfileCalibration { it.copy(bassStretchBias = b.toDouble()) } },
            valueRange = 0.5f..1.5f,
            modifier = Modifier.height(26.dp),
          )
        }
        Column(modifier = Modifier.weight(1f)) {
          Text("Treble Bias: ${(profile.trebleStretchBias * 100).roundToInt()}%", fontSize = 10.sp, color = Color(0xFF94A3B8))
          Slider(
            value = profile.trebleStretchBias.toFloat(),
            onValueChange = { b -> viewModel.updateActiveProfileCalibration { it.copy(trebleStretchBias = b.toDouble()) } },
            valueRange = 0.5f..1.5f,
            modifier = Modifier.height(26.dp),
          )
        }
      }

      Text("Temperament:", fontSize = 11.sp, color = Color(0xFFE2E8F0))
      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        TemperamentType.entries.forEach { temp ->
          ChipButton(
            text = temp.label,
            selected = profile.temperament == temp,
            onClick = { viewModel.updateActiveProfileCalibration { it.copy(temperament = temp) } },
          )
        }
      }
    }

    // --- 4. PITCH-RAISE / OVERPULL CALCULATOR ---
    CardSection(title = "PITCH-RAISE / OVERPULL MODE") {
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
          Text("Enable Overpull Compensation", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
          Text("Uses Yellow recorded flat deviations to overpull sharp during pitch raises", fontSize = 10.sp, color = Color(0xFF94A3B8))
        }
        Switch(
          checked = profile.overpullEnabled,
          onCheckedChange = { c -> viewModel.updateActiveProfileCalibration { it.copy(overpullEnabled = c) } },
        )
      }
      Text(
        text = "Overpull Factor: ${(profile.overpullPercent * 100).roundToInt()}% of flat error",
        fontSize = 11.sp,
        color = Color(0xFFE2E8F0),
      )
      Slider(
        value = profile.overpullPercent.toFloat(),
        onValueChange = { p -> viewModel.updateActiveProfileCalibration { it.copy(overpullPercent = p.toDouble()) } },
        valueRange = 0.1f..0.45f,
        modifier = Modifier.height(28.dp),
      )
    }

    // --- 5. INTERVAL BEAT RATE CHECKS FOR ACTIVE KEY ---
    CardSection(title = "INTERVAL BEAT CHECKS FROM ${PianoNotes.keyInfo(state.activeKeyIndex).noteName}") {
      if (state.intervalChecks.isEmpty()) {
        Text("Upper intervals out of 88-key range for this note.", fontSize = 11.sp, color = Color(0xFF94A3B8))
      } else {
        state.intervalChecks.forEach { iv ->
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(6.dp))
              .background(Color(0xFF090D16))
              .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text("${iv.name} → ${iv.partnerNoteName}", fontSize = 11.sp, color = Color(0xFFE2E8F0))
            Text(
              text = "${String.format(Locale.US, "%.2f", iv.beatRateHz)} Hz (${iv.directionLabel})",
              fontSize = 11.sp,
              fontFamily = FontFamily.Monospace,
              color = Color(0xFF38BDF8),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun CardSection(
  title: String,
  content: @Composable () -> Unit,
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(14.dp))
      .background(Color(0xFF111827))
      .border(1.dp, Color(0xFF1F2937), RoundedCornerShape(14.dp))
      .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    Text(title, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF94A3B8))
    content()
  }
}

@Composable
private fun ChipButton(text: String, selected: Boolean, onClick: () -> Unit) {
  Box(
    modifier = Modifier
      .clip(RoundedCornerShape(8.dp))
      .background(if (selected) Color(0xFF0284C7) else Color(0xFF1E293B))
      .clickable(onClick = onClick)
      .padding(horizontal = 10.dp, vertical = 6.dp),
  ) {
    Text(
      text = text,
      fontSize = 11.sp,
      fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
      color = if (selected) Color.White else Color(0xFFCBD5E1),
    )
  }
}
