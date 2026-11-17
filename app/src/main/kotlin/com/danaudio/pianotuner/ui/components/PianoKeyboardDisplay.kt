package com.danaudio.pianotuner.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danaudio.pianotuner.data.KeyMeasurementRecord
import com.danaudio.pianotuner.data.KeyTuningStatus
import com.danaudio.pianotuner.dsp.PianoNotes
import kotlin.math.roundToInt

private val ACTIVE_RED = Color(0xFFEF4444)
private val RECORDED_YELLOW = Color(0xFFFACC15)
private val IN_TUNE_GREEN = Color(0xFF22C55E)

/**
 * Interactive 88-key Piano Keyboard Display:
 * - Red: active note currently being tuned
 * - Yellow: recorded key (e.g. from "Record Every 3rd" sweep)
 * - Green: registered as in tune
 */
@Composable
fun PianoKeyboardDisplay(
  activeKeyIndex: Int,
  keyRecords: Map<Int, KeyMeasurementRecord>,
  onKeySelected: (Int) -> Unit,
  onCycleKeyStatus: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  val scrollState = rememberScrollState()
  val density = LocalDensity.current
  var viewportWidthPx by remember { mutableIntStateOf(900) }

  val whiteKeys = remember { PianoNotes.allKeys.filter { !it.isBlack } } // 52 white keys
  val whiteSlotMap = remember {
    var wIdx = 0
    IntArray(PianoNotes.TOTAL_KEYS) { k ->
      val info = PianoNotes.keyInfo(k)
      if (!info.isBlack) {
        wIdx++
        wIdx - 1
      } else {
        (wIdx - 1).coerceAtLeast(0)
      }
    }
  }

  val whiteKeyWidthDp = 34.dp
  val whiteKeyWidthPx = with(density) { whiteKeyWidthDp.toPx() }

  // Keep active red key centered smoothly in scrollable view
  LaunchedEffect(activeKeyIndex, viewportWidthPx) {
    val slot = whiteSlotMap[activeKeyIndex.coerceIn(0, PianoNotes.TOTAL_KEYS - 1)]
    val targetPx = (slot * whiteKeyWidthPx - viewportWidthPx * 0.45f).roundToInt().coerceAtLeast(0)
    scrollState.animateScrollTo(targetPx)
  }

  val recordedCount = keyRecords.values.count { it.status == KeyTuningStatus.RECORDED }
  val inTuneCount = keyRecords.values.count { it.status == KeyTuningStatus.IN_TUNE }

  Column(
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(14.dp))
      .background(Color(0xFF0D131F))
      .border(1.dp, Color(0xFF243047), RoundedCornerShape(14.dp))
      .padding(8.dp),
  ) {
    // Legend & Progress Header
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        LegendDot("Tuning", ACTIVE_RED)
        LegendDot("Recorded ($recordedCount)", RECORDED_YELLOW)
        LegendDot("In Tune ($inTuneCount)", IN_TUNE_GREEN)
      }
      Text(
        text = "Tap key = Tune • Long-press = Toggle status",
        fontSize = 9.sp,
        color = Color(0xFF64748B),
      )
    }

    Spacer(Modifier.height(6.dp))

    // Full 88-Key Overview Strip (tap anywhere to jump!)
    Canvas(
      modifier = Modifier
        .fillMaxWidth()
        .height(18.dp)
        .clip(RoundedCornerShape(4.dp))
        .background(Color(0xFF1E293B))
        .pointerInput(Unit) {
          detectTapGestures { offset ->
            val k = ((offset.x / size.width) * PianoNotes.TOTAL_KEYS).toInt().coerceIn(0, PianoNotes.TOTAL_KEYS - 1)
            onKeySelected(k)
          }
        }
    ) {
      val keyW = size.width / PianoNotes.TOTAL_KEYS.toFloat()
      for (k in 0 until PianoNotes.TOTAL_KEYS) {
        val status = keyRecords[k]?.status
        val isActive = k == activeKeyIndex
        val col = when {
          isActive -> ACTIVE_RED
          status == KeyTuningStatus.IN_TUNE -> IN_TUNE_GREEN
          status == KeyTuningStatus.RECORDED -> RECORDED_YELLOW
          PianoNotes.keyInfo(k).isBlack -> Color(0xFF0F172A)
          else -> Color(0xFF475569)
        }
        drawRect(
          color = col,
          topLeft = Offset(k * keyW, 0f),
          size = Size((keyW - 0.5f).coerceAtLeast(1f), size.height),
        )
      }
    }

    Spacer(Modifier.height(6.dp))

    // Scrollable Zoomed Interactive Piano Keyboard
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(114.dp)
        .onSizeChanged { viewportWidthPx = it.width }
        .horizontalScroll(scrollState),
    ) {
      val totalWidthDp = whiteKeyWidthDp * whiteKeys.size

      // White Keys Layer
      Row(modifier = Modifier.width(totalWidthDp).fillMaxHeight()) {
        whiteKeys.forEach { key ->
          val status = keyRecords[key.keyIndex]?.status
          val isActive = key.keyIndex == activeKeyIndex

          val bg = when {
            isActive -> ACTIVE_RED
            status == KeyTuningStatus.IN_TUNE -> Color(0xFF86EFAC)
            status == KeyTuningStatus.RECORDED -> Color(0xFFFDE047)
            else -> Color(0xFFF8FAFC)
          }
          val textColor = if (isActive) Color.White else Color(0xFF0F172A)

          Box(
            modifier = Modifier
              .width(whiteKeyWidthDp)
              .fillMaxHeight()
              .padding(horizontal = 0.75.dp)
              .clip(RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp))
              .background(bg)
              .border(1.dp, if (isActive) Color.White else Color(0xFF334155), RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp))
              .pointerInput(key.keyIndex) {
                detectTapGestures(
                  onTap = { onKeySelected(key.keyIndex) },
                  onLongPress = { onCycleKeyStatus(key.keyIndex) },
                )
              },
            contentAlignment = Alignment.BottomCenter,
          ) {
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              modifier = Modifier.padding(bottom = 4.dp),
            ) {
              if (status != null && status != KeyTuningStatus.UNCHECKED && isActive) {
                Box(
                  modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (status == KeyTuningStatus.IN_TUNE) IN_TUNE_GREEN else RECORDED_YELLOW)
                )
                Spacer(Modifier.height(2.dp))
              }
              if (key.pitchClass == "C" || key.keyIndex == 0 || isActive) {
                Text(
                  text = key.noteName,
                  fontSize = 9.sp,
                  fontWeight = if (isActive || key.pitchClass == "C") FontWeight.Bold else FontWeight.Normal,
                  color = textColor,
                )
              }
            }
          }
        }
      }

      // Black Keys Layer
      val blackKeyWidthDp = 22.dp
      PianoNotes.allKeys.filter { it.isBlack }.forEach { key ->
        val leftWhiteSlot = whiteSlotMap[key.keyIndex]
        val xOffsetDp = whiteKeyWidthDp * (leftWhiteSlot + 1) - (blackKeyWidthDp / 2f)
        val status = keyRecords[key.keyIndex]?.status
        val isActive = key.keyIndex == activeKeyIndex

        val bg = when {
          isActive -> ACTIVE_RED
          status == KeyTuningStatus.IN_TUNE -> IN_TUNE_GREEN
          status == KeyTuningStatus.RECORDED -> RECORDED_YELLOW
          else -> Color(0xFF111827)
        }
        val labelColor = when {
          isActive -> Color.White
          status == KeyTuningStatus.RECORDED || status == KeyTuningStatus.IN_TUNE -> Color(0xFF0F172A)
          else -> Color(0xFF94A3B8)
        }

        Box(
          modifier = Modifier
            .offset(x = xOffsetDp, y = 0.dp)
            .width(blackKeyWidthDp)
            .height(68.dp)
            .clip(RoundedCornerShape(bottomStart = 5.dp, bottomEnd = 5.dp))
            .background(bg)
            .border(1.dp, if (isActive) Color.White else Color(0xFF334155), RoundedCornerShape(bottomStart = 5.dp, bottomEnd = 5.dp))
            .pointerInput(key.keyIndex) {
              detectTapGestures(
                onTap = { onKeySelected(key.keyIndex) },
                onLongPress = { onCycleKeyStatus(key.keyIndex) },
              )
            },
          contentAlignment = Alignment.BottomCenter,
        ) {
          if (isActive) {
            Text(
              text = key.pitchClass,
              fontSize = 8.sp,
              fontWeight = FontWeight.Bold,
              color = labelColor,
              modifier = Modifier.padding(bottom = 4.dp),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun LegendDot(label: String, color: Color) {
  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    Box(
      modifier = Modifier
        .size(9.dp)
        .clip(CircleShape)
        .background(color)
    )
    Text(text = label, fontSize = 10.sp, color = Color(0xFFCBD5E1), fontWeight = FontWeight.Medium)
  }
}
