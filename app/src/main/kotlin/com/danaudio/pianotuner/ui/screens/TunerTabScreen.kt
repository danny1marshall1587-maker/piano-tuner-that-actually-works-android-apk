package com.danaudio.pianotuner.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danaudio.pianotuner.dsp.PartialMode
import com.danaudio.pianotuner.ui.TunerUiState
import com.danaudio.pianotuner.ui.TunerViewModel
import com.danaudio.pianotuner.ui.components.NeedleAndStrobeTuner
import com.danaudio.pianotuner.ui.components.PianoKeyboardDisplay
import kotlin.math.roundToInt

@Composable
fun TunerTabScreen(
  state: TunerUiState,
  viewModel: TunerViewModel,
  modifier: Modifier = Modifier,
) {
  val activeKey = state.activeKeyIndex
  val curve = state.stretchCurve
  val profile = state.activeProfile

  Column(
    modifier = modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 10.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    // 1. Needle Meter + Strobe Strip Bar + Unison Check
    NeedleAndStrobeTuner(
      activeKeyIndex = activeKey,
      targetHz = curve.targetFrequenciesHz[activeKey],
      stretchOffsetCents = curve.stretchOffsetsCents[activeKey],
      overpullOffsetCents = curve.overpullOffsetsCents[activeKey],
      toleranceCents = profile.inTuneToleranceCents,
      pitchResult = state.pitchResult,
      inTuneHoldProgress = state.inTuneHoldProgress,
    )

    // 2. Auto-Follow, Sine Wave Tuning Fork, Sweep Stepper
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Button(
        onClick = { viewModel.toggleAutoFollow() },
        modifier = Modifier.weight(1.1f).height(42.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
        colors = ButtonDefaults.buttonColors(
          containerColor = if (state.autoFollowPitch) Color(0xFF0284C7) else Color(0xFF334155),
          contentColor = Color.White,
        ),
        shape = RoundedCornerShape(10.dp),
      ) {
        Text(
          text = if (state.autoFollowPitch) "AUTO FOLLOW: ON" else "MANUAL MODE",
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold,
        )
      }

      Button(
        onClick = { viewModel.toggleToneFork() },
        modifier = Modifier.weight(1f).height(42.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
        colors = ButtonDefaults.buttonColors(
          containerColor = if (state.toneForkPlaying) Color(0xFF9333EA) else Color(0xFF1E293B),
          contentColor = Color.White,
        ),
        shape = RoundedCornerShape(10.dp),
      ) {
        Text(
          text = if (state.toneForkPlaying) "FORK: ON" else "SINE FORK",
          fontSize = 11.sp,
          fontWeight = FontWeight.Bold,
        )
      }
    }

    // 3. Record (Yellow) / Mark In-Tune (Green) / Sweep Every-Nth Stepper
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      val step = profile.sweepStepMode.semitoneStep
      OutlinedButton(
        onClick = { viewModel.stepSweepKey(forward = false) },
        modifier = Modifier.height(40.dp),
        contentPadding = PaddingValues(horizontal = 10.dp),
        shape = RoundedCornerShape(10.dp),
      ) {
        Text("◄ -$step", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFCBD5E1))
      }

      Button(
        onClick = { viewModel.recordActiveKeyYellow() },
        modifier = Modifier.weight(1f).height(40.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
        colors = ButtonDefaults.buttonColors(
          containerColor = Color(0xFFEAB308),
          contentColor = Color(0xFF0F172A),
        ),
        shape = RoundedCornerShape(10.dp),
      ) {
        Text("RECORD", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
      }

      Button(
        onClick = { viewModel.markActiveKeyInTuneGreen() },
        modifier = Modifier.weight(1f).height(40.dp),
        contentPadding = PaddingValues(horizontal = 8.dp),
        colors = ButtonDefaults.buttonColors(
          containerColor = Color(0xFF22C55E),
          contentColor = Color(0xFF052E16),
        ),
        shape = RoundedCornerShape(10.dp),
      ) {
        Text("IN TUNE", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
      }

      OutlinedButton(
        onClick = { viewModel.stepSweepKey(forward = true) },
        modifier = Modifier.height(40.dp),
        contentPadding = PaddingValues(horizontal = 10.dp),
        shape = RoundedCornerShape(10.dp),
      ) {
        Text("+$step ►", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFCBD5E1))
      }
    }

    // 4. Wide-Ranged Stabilization Slider + Partial Selector
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(12.dp))
        .background(Color(0xFF111827))
        .border(1.dp, Color(0xFF1F2937), RoundedCornerShape(12.dp))
        .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        val stabPct = (state.stabilization * 100f).roundToInt()
        val stabLabel = when {
          stabPct < 20 -> "Fast Transient (High Notes)"
          stabPct < 60 -> "Balanced Studio"
          else -> "Heavy Stabilization"
        }
        Text(
          text = "Stabilizer: $stabPct% • $stabLabel",
          fontSize = 11.sp,
          fontWeight = FontWeight.SemiBold,
          color = Color(0xFFE2E8F0),
        )
        Text(
          text = "AGC ${String.format("%+.0fdB", state.dynamicsSnapshot.agcGainDb)}",
          fontSize = 11.sp,
          color = Color(0xFF38BDF8),
        )
      }
      Slider(
        value = state.stabilization,
        onValueChange = { viewModel.setStabilization(it) },
        valueRange = 0f..1f,
        modifier = Modifier.height(30.dp),
        colors = SliderDefaults.colors(
          thumbColor = Color(0xFF38BDF8),
          activeTrackColor = Color(0xFF0284C7),
          inactiveTrackColor = Color(0xFF1E293B),
        ),
      )

      // Partial Selector Row
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        PartialMode.entries.forEach { mode ->
          val selected = state.partialMode == mode
          Box(
            modifier = Modifier
              .clip(RoundedCornerShape(6.dp))
              .background(if (selected) Color(0xFF0284C7) else Color(0xFF1E293B))
              .clickable { viewModel.setPartialMode(mode) }
              .padding(horizontal = 9.dp, vertical = 4.dp),
          ) {
            Text(
              text = mode.label,
              fontSize = 10.sp,
              fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
              color = if (selected) Color.White else Color(0xFF94A3B8),
            )
          }
        }
      }
    }

    // 5. Interactive 88-Key Piano Keyboard (Red = Tuning, Yellow = Recorded, Green = In Tune)
    PianoKeyboardDisplay(
      activeKeyIndex = activeKey,
      keyRecords = state.activeKeyRecords,
      onKeySelected = { keyIdx -> viewModel.setManualKey(keyIdx, switchToManual = true) },
      onCycleKeyStatus = { keyIdx -> viewModel.cycleKeyStatus(keyIdx) },
    )
  }
}
