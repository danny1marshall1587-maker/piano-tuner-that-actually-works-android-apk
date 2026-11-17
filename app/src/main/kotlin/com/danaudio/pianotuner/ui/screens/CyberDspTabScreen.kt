package com.danaudio.pianotuner.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danaudio.pianotuner.dsp.CyberDenoiserSnapshot
import com.danaudio.pianotuner.dsp.LowCutMode
import com.danaudio.pianotuner.ui.TunerUiState
import com.danaudio.pianotuner.ui.TunerViewModel
import java.util.Locale
import kotlin.math.log10
import kotlin.math.roundToInt

@Composable
fun CyberDspTabScreen(
  state: TunerUiState,
  viewModel: TunerViewModel,
  modifier: Modifier = Modifier,
) {
  val denParams = state.denoiserParams
  val denSnap = state.denoiserSnapshot
  val dynParams = state.dynamicsParams
  val dynSnap = state.dynamicsSnapshot

  Column(
    modifier = modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // --- CYBER-DENOISER PRO SECTION ---
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .background(
          Brush.verticalGradient(
            listOf(Color(0xFF2B1640), Color(0xFF1A102B))
          )
        )
        .border(1.dp, Color(0xFF6B46A8), RoundedCornerShape(16.dp))
        .padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column {
          Text("CYBER-DENOISER PRO", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
          Text("10-Band Zero-Latency Subtractive Core", fontSize = 11.sp, color = Color(0xFFC4B5FD))
        }
        Switch(
          checked = denParams.enabled,
          onCheckedChange = { checked -> viewModel.updateDenoiserParams { it.copy(enabled = checked) } },
        )
      }

      // 10-Band Real-Time Meter + Gain Reduction Overlay (faithful to CyberDenoiserEditor)
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .height(110.dp)
          .clip(RoundedCornerShape(10.dp))
          .background(Color(0xFF120920))
          .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
      ) {
        for (i in 0 until 10) {
          val levelLin = denSnap.bandLevels.getOrElse(i) { 0f }
          val levelDb = 20f * log10(levelLin + 1e-6f)
          val normH = ((levelDb + 100f) / 100f).coerceIn(0f, 1f)
          val red = denSnap.bandReductions.getOrElse(i) { 1f }
          val redFrac = (1f - red).coerceIn(0f, 1f)

          Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Box(
              modifier = Modifier
                .width(16.dp)
                .height(74.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0xFF25153F)),
              contentAlignment = Alignment.BottomCenter,
            ) {
              if (normH > 0.02f) {
                // Signal bar
                Box(
                  modifier = Modifier
                    .fillMaxWidth()
                    .height((74f * normH).dp)
                    .background(Color(0xFF38BDF8).copy(alpha = 0.78f)),
                )
                // Reduction overlay from top of signal bar
                if (redFrac > 0.02f) {
                  Box(
                    modifier = Modifier
                      .align(Alignment.TopCenter)
                      .padding(top = (74f * (1f - normH)).dp)
                      .fillMaxWidth()
                      .height((74f * normH * redFrac).dp)
                      .background(Color(0xFFEF4444).copy(alpha = 0.6f)),
                  )
                }
              }
            }
            Spacer(Modifier.height(3.dp))
            Text(
              text = CyberDenoiserSnapshot.BAND_LABELS[i],
              fontSize = 8.sp,
              color = Color(0xFFDDD6FE),
            )
          }
        }
      }

      // Learn Noise Button + Progress
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Button(
          onClick = { viewModel.startCyberLearn() },
          enabled = !denSnap.isLearning,
          colors = ButtonDefaults.buttonColors(
            containerColor = if (denSnap.isLearning) Color(0xFFEC4899) else Color(0xFF7C3AED),
            contentColor = Color.White,
          ),
          modifier = Modifier.weight(1f),
        ) {
          Text(
            text = if (denSnap.isLearning) "LEARNING NOISE..." else "LEARN ROOM NOISE (${denParams.learnDurationSec.toInt()}s)",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
          )
        }

        Button(
          onClick = { viewModel.setDenoiserMasterThreshold(-58f) },
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF311B54), contentColor = Color.White),
        ) {
          Text("Reset", fontSize = 11.sp)
        }
      }

      if (denSnap.isLearning) {
        LinearProgressIndicator(
          progress = { denSnap.learnProgress },
          modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
          color = Color(0xFFF472B6),
          trackColor = Color(0xFF2E1065),
        )
      }

      // Master Threshold, Release, Floor
      LabeledSlider(
        label = "Master Threshold: ${denParams.masterThresholdDb.roundToInt()} dB",
        value = denParams.masterThresholdDb,
        range = -100f..0f,
        onValueChange = { viewModel.setDenoiserMasterThreshold(it) },
      )
      LabeledSlider(
        label = "Adaptive Release: ${denParams.releaseMs.roundToInt()} ms",
        value = denParams.releaseMs,
        range = 10f..600f,
        onValueChange = { v -> viewModel.updateDenoiserParams { it.copy(releaseMs = v) } },
      )
      LabeledSlider(
        label = "Noise Floor: ${(denParams.floor * 100f).roundToInt()}%",
        value = denParams.floor,
        range = 0f..0.4f,
        onValueChange = { v -> viewModel.updateDenoiserParams { it.copy(floor = v) } },
      )

      // Low Cut & Mode Chips
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        LowCutMode.entries.forEach { mode ->
          val sel = denParams.lowCutMode == mode
          Box(
            modifier = Modifier
              .clip(RoundedCornerShape(6.dp))
              .background(if (sel) Color(0xFF7C3AED) else Color(0xFF2E1B4D))
              .clickable { viewModel.updateDenoiserParams { it.copy(lowCutMode = mode) } }
              .padding(horizontal = 8.dp, vertical = 5.dp),
          ) {
            Text(mode.label, fontSize = 10.sp, color = Color.White, fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal)
          }
        }
        Spacer(Modifier.weight(1f))
        Box(
          modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (denParams.rmsMode) Color(0xFF7C3AED) else Color(0xFF2E1B4D))
            .clickable { viewModel.updateDenoiserParams { it.copy(rmsMode = !it.rmsMode) } }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        ) {
          Text(if (denParams.rmsMode) "RMS" else "Peak", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
        }
      }
    }

    // --- FAST DIGITAL AGC & COMPRESSOR FOR SHORT HIGH NOTES ---
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(16.dp))
        .background(Color(0xFF0F172A))
        .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(16.dp))
        .padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text("FAST DIGITAL AGC & HAMMER COMPRESSOR", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF38BDF8))
      Text("Catches quiet & ultra-short high treble notes (C6–C8) while taming hammer spikes", fontSize = 11.sp, color = Color(0xFF94A3B8))

      // Live Telemetry Row
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(8.dp))
          .background(Color(0xFF090D16))
          .padding(8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        StatChip("IN", String.format(Locale.US, "%.0fdB", dynSnap.inputPeakDb), Color(0xFFCBD5E1))
        StatChip("AGC BOOST", String.format(Locale.US, "+%.1fdB", dynSnap.agcGainDb), Color(0xFF22C55E))
        StatChip("COMP GR", String.format(Locale.US, "-%.1fdB", dynSnap.compReductionDb), Color(0xFFF59E0B))
        StatChip("GATE", if (dynSnap.gateOpen) "OPEN" else "SHUT", if (dynSnap.gateOpen) Color(0xFF38BDF8) else Color(0xFF64748B))
      }

      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("Fast Digital Auto Gain (AGC)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        Switch(checked = dynParams.agcEnabled, onCheckedChange = { c -> viewModel.updateDynamicsParams { it.copy(agcEnabled = c) } })
      }
      LabeledSlider(
        label = "Max AGC Boost: +${dynParams.agcMaxBoostDb.roundToInt()} dB",
        value = dynParams.agcMaxBoostDb,
        range = 0f..40f,
        onValueChange = { v -> viewModel.updateDynamicsParams { it.copy(agcMaxBoostDb = v) } },
      )
      LabeledSlider(
        label = "AGC Attack Speed: ${String.format(Locale.US, "%.1f", dynParams.agcAttackMs)} ms",
        value = dynParams.agcAttackMs,
        range = 0.5f..20f,
        onValueChange = { v -> viewModel.updateDynamicsParams { it.copy(agcAttackMs = v) } },
      )
      LabeledSlider(
        label = "Gate Floor: ${dynParams.noiseGateDb.roundToInt()} dB",
        value = dynParams.noiseGateDb,
        range = -85f..-35f,
        onValueChange = { v -> viewModel.updateDynamicsParams { it.copy(noiseGateDb = v) } },
      )

      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("Transient Compressor", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        Switch(checked = dynParams.compressorEnabled, onCheckedChange = { c -> viewModel.updateDynamicsParams { it.copy(compressorEnabled = c) } })
      }
      LabeledSlider(
        label = "Comp Threshold: ${dynParams.compThresholdDb.roundToInt()} dB | Ratio ${String.format(Locale.US, "%.1f", dynParams.compRatio)}:1",
        value = dynParams.compThresholdDb,
        range = -36f..-4f,
        onValueChange = { v -> viewModel.updateDynamicsParams { it.copy(compThresholdDb = v) } },
      )

      // Tuning Fork Feedback Guard
      Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
          Text("Mute Mic While Sine Fork Plays", fontSize = 12.sp, color = Color.White)
          Text("Prevents speaker tone from looping back into tuner", fontSize = 10.sp, color = Color(0xFF94A3B8))
        }
        Switch(checked = state.muteMicDuringFork, onCheckedChange = { viewModel.toggleMuteMicDuringFork() })
      }
      LabeledSlider(
        label = "Tuning Fork Volume: ${(state.toneForkVolume * 100f).roundToInt()}%",
        value = state.toneForkVolume,
        range = 0.05f..0.85f,
        onValueChange = { viewModel.setToneForkVolume(it) },
      )
    }

    // --- LIVE 72-BAR HARMONIC SPECTRUM ---
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(14.dp))
        .background(Color(0xFF090D16))
        .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
        .padding(10.dp),
    ) {
      Text("LIVE HARMONIC SPECTRUM (26 Hz – 6.5 kHz)", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
      Spacer(Modifier.height(6.dp))
      val bars = state.pitchResult.spectrumBars
      Canvas(modifier = Modifier.fillMaxWidth().height(72.dp)) {
        val barW = size.width / bars.size.coerceAtLeast(1)
        for (i in bars.indices) {
          val h = (bars[i] * size.height).coerceAtLeast(2f)
          drawRoundRect(
            color = Color(0xFF38BDF8),
            topLeft = Offset(i * barW + 1f, size.height - h),
            size = Size((barW - 2f).coerceAtLeast(1f), h),
            cornerRadius = CornerRadius(2f, 2f),
          )
        }
      }
    }
  }
}

@Composable
private fun LabeledSlider(
  label: String,
  value: Float,
  range: ClosedFloatingPointRange<Float>,
  onValueChange: (Float) -> Unit,
) {
  Column {
    Text(label, fontSize = 11.sp, color = Color(0xFFE2E8F0))
    Slider(
      value = value.coerceIn(range.start, range.endInclusive),
      onValueChange = onValueChange,
      valueRange = range,
      modifier = Modifier.height(28.dp),
    )
  }
}

@Composable
private fun StatChip(label: String, value: String, color: Color) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    Text(label, fontSize = 9.sp, color = Color(0xFF64748B))
    Text(value, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = color)
  }
}
