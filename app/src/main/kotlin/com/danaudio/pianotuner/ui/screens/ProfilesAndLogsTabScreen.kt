package com.danaudio.pianotuner.ui.screens

import android.content.Intent
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.danaudio.pianotuner.data.KeyTuningStatus
import com.danaudio.pianotuner.dsp.PianoScaleType
import com.danaudio.pianotuner.ui.TunerUiState
import com.danaudio.pianotuner.ui.TunerViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ProfilesAndLogsTabScreen(
  state: TunerUiState,
  viewModel: TunerViewModel,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val activeProfile = state.activeProfile
  val profiles = state.store.profiles

  var showNewDialog by remember { mutableStateOf(false) }
  var logNotes by remember { mutableStateOf("") }

  val dateFmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US) }

  Column(
    modifier = modifier
      .fillMaxSize()
      .verticalScroll(rememberScrollState())
      .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // --- 1. MULTI-PIANO PROFILE SWITCHER ---
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(14.dp))
        .background(Color(0xFF111827))
        .border(1.dp, Color(0xFF1F2937), RoundedCornerShape(14.dp))
        .padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text("PIANO PROFILES (${profiles.size})", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF38BDF8))
        Button(
          onClick = { showNewDialog = true },
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
          modifier = Modifier.height(34.dp),
        ) {
          Text("+ New Piano", fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
      }

      profiles.forEach { prof ->
        val isActive = prof.id == activeProfile.id
        val inTuneCnt = prof.keyRecords.values.count { it.status == KeyTuningStatus.IN_TUNE }
        val goldCnt = prof.keyRecords.values.count { it.status == KeyTuningStatus.SETTLED_GOLD }
        val recCnt = prof.keyRecords.values.count { it.status == KeyTuningStatus.RECORDED }

        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isActive) Color(0xFF0C4A6E).copy(alpha = 0.55f) else Color(0xFF090D16))
            .border(1.dp, if (isActive) Color(0xFF38BDF8) else Color(0xFF1E293B), RoundedCornerShape(10.dp))
            .clickable { viewModel.switchProfile(prof.id) }
            .padding(10.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = prof.name,
              fontSize = 14.sp,
              fontWeight = FontWeight.Bold,
              color = Color.White,
            )
            Text(
              text = "${prof.makeAndModel.ifBlank { prof.scaleType.label }} • A4=${String.format(Locale.US, "%.1f", prof.a4TargetHz)}Hz",
              fontSize = 11.sp,
              color = Color(0xFFCBD5E1),
            )
            if (prof.locationOrOwner.isNotBlank()) {
              Text(prof.locationOrOwner, fontSize = 10.sp, color = Color(0xFF94A3B8))
            }
            Text(
              text = "Gold: $goldCnt/88 • Green: $inTuneCnt/88 • Yellow: $recCnt/88 • Logs: ${prof.logs.size}",
              fontSize = 10.sp,
              color = Color(0xFF4ADE80),
            )
          }

          if (profiles.size > 1) {
            TextButton(onClick = { viewModel.deleteProfile(prof.id) }) {
              Text("Delete", fontSize = 11.sp, color = Color(0xFFF87171))
            }
          }
        }
      }
    }

    // --- 2. LOG CURRENT TUNING SESSION ---
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(14.dp))
        .background(Color(0xFF111827))
        .border(1.dp, Color(0xFF1F2937), RoundedCornerShape(14.dp))
        .padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text("LOG TUNING SESSION — ${activeProfile.name}", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF94A3B8))

      OutlinedTextField(
        value = logNotes,
        onValueChange = { logNotes = it },
        label = { Text("Technician Notes (humidity, loose pins, pitch raise...)") },
        modifier = Modifier.fillMaxWidth(),
        maxLines = 3,
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Button(
          onClick = {
            viewModel.saveTuningSessionLog(logNotes)
            logNotes = ""
          },
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF22C55E), contentColor = Color(0xFF052E16)),
          modifier = Modifier.weight(1f),
        ) {
          Text("Save Tuning Log", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
        }

        OutlinedButton(
          onClick = {
            val report = viewModel.buildExportReport()
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
              type = "text/plain"
              putExtra(Intent.EXTRA_SUBJECT, "Piano Tuning Report: ${activeProfile.name}")
              putExtra(Intent.EXTRA_TEXT, report)
            }
            context.startActivity(Intent.createChooser(sendIntent, "Share Tuning Report"))
          },
        ) {
          Text("Share Report", fontSize = 12.sp, color = Color(0xFF38BDF8))
        }
      }

      OutlinedButton(
        onClick = { viewModel.clearAllKeyStatuses() },
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text("Clear Keyboard Markers (Reset Red/Yellow/Green for New Pass)", fontSize = 11.sp, color = Color(0xFFFCA5A5))
      }
    }

    // --- 3. TUNING LOG HISTORY ---
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(14.dp))
        .background(Color(0xFF111827))
        .border(1.dp, Color(0xFF1F2937), RoundedCornerShape(14.dp))
        .padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text("TUNING HISTORY (${activeProfile.logs.size})", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF94A3B8))

      if (activeProfile.logs.isEmpty()) {
        Text(
          text = "No saved tuning logs yet for ${activeProfile.name}. Record or tune notes, then tap 'Save Tuning Log'.",
          fontSize = 11.sp,
          color = Color(0xFF64748B),
        )
      } else {
        activeProfile.logs.forEach { log ->
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(10.dp))
              .background(Color(0xFF090D16))
              .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(10.dp))
              .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
          ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Text(dateFmt.format(Date(log.timestampEpochMs)), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
              Text(
                text = "A4 ${String.format(Locale.US, "%.1f", log.a4Hz)}Hz • ${log.inTuneCount}/88 In Tune",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF4ADE80),
              )
            }
            Text(
              text = "${log.temperamentName} • Stretch ${(log.stretchFactor * 100).toInt()}% • " +
                "Before ${String.format(Locale.US, "%.1f", log.avgInitialDeviationCents)}¢ → After ${String.format(Locale.US, "%.1f", log.avgFinalDeviationCents)}¢" +
                if (log.overpullUsed) " • Overpull" else "",
              fontSize = 10.sp,
              color = Color(0xFF94A3B8),
            )
            if (log.notes.isNotBlank()) {
              Text(log.notes, fontSize = 11.sp, color = Color(0xFFE2E8F0))
            }
          }
        }
      }
    }
  }

  if (showNewDialog) {
    NewPianoDialog(
      onDismiss = { showNewDialog = false },
      onCreate = { name, model, loc, serial, scale ->
        viewModel.createProfile(name, model, loc, serial, scale)
        showNewDialog = false
      },
    )
  }
}

@Composable
private fun NewPianoDialog(
  onDismiss: () -> Unit,
  onCreate: (String, String, String, String, PianoScaleType) -> Unit,
) {
  var name by remember { mutableStateOf("") }
  var model by remember { mutableStateOf("") }
  var location by remember { mutableStateOf("") }
  var serial by remember { mutableStateOf("") }
  var scaleType by remember { mutableStateOf(PianoScaleType.UPRIGHT) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("New Piano Profile") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Piano Name (e.g. Living Room Steinway)") })
        OutlinedTextField(value = model, onValueChange = { model = it }, label = { Text("Make & Model (e.g. Steinway B)") })
        OutlinedTextField(value = location, onValueChange = { location = it }, label = { Text("Owner / Room Location") })
        OutlinedTextField(value = serial, onValueChange = { serial = it }, label = { Text("Serial Number (Optional)") })
        Text("Scale Type:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Row(
          modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          PianoScaleType.entries.forEach { sc ->
            val sel = sc == scaleType
            Box(
              modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (sel) Color(0xFF0284C7) else Color(0xFF1E293B))
                .clickable { scaleType = sc }
                .padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
              Text(sc.label, fontSize = 10.sp, color = Color.White)
            }
          }
        }
      }
    },
    confirmButton = {
      Button(onClick = { onCreate(name, model, location, serial, scaleType) }) {
        Text("Create")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    },
  )
}
