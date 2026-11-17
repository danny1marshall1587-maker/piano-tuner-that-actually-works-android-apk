package com.danaudio.pianotuner.data

import com.danaudio.pianotuner.dsp.PianoNotes
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlinx.serialization.json.Json

class PianoProfileRepository(private val storageFile: File) {

  private val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  fun loadStore(): PianoStoreState {
    return try {
      if (!storageFile.exists()) {
        val initial = PianoStoreState()
        saveStore(initial)
        initial
      } else {
        val parsed = json.decodeFromString<PianoStoreState>(storageFile.readText())
        if (parsed.profiles.isEmpty()) PianoStoreState() else parsed
      }
    } catch (_: Throwable) {
      PianoStoreState()
    }
  }

  fun saveStore(state: PianoStoreState) {
    try {
      storageFile.parentFile?.mkdirs()
      storageFile.writeText(json.encodeToString(state))
    } catch (_: Throwable) {
    }
  }

  fun formatProfileReport(profile: PianoProfile): String {
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    val sb = StringBuilder()
    sb.appendLine("PIANO TUNER PRO — TUNING REPORT")
    sb.appendLine("Piano: ${profile.name} (${profile.makeAndModel.ifBlank { profile.scaleType.label }})")
    if (profile.locationOrOwner.isNotBlank()) sb.appendLine("Owner/Location: ${profile.locationOrOwner}")
    if (profile.serialNumber.isNotBlank()) sb.appendLine("Serial: ${profile.serialNumber}")
    sb.appendLine("Target A4: ${String.format(Locale.US, "%.1f", profile.a4TargetHz)} Hz | Shift: ${String.format(Locale.US, "%+.1f", profile.transposeCents)}¢")
    sb.appendLine("Temperament: ${profile.temperament.label} | Stretch: ${(profile.stretchFactor * 100).toInt()}%")
    sb.appendLine()

    val recorded = profile.keyRecords.values.filter { it.status == KeyTuningStatus.RECORDED }
    val inTune = profile.keyRecords.values.filter { it.status == KeyTuningStatus.IN_TUNE }
    sb.appendLine("Current Key Board: ${inTune.size}/88 In Tune (Green) | ${recorded.size}/88 Recorded (Yellow)")
    sb.appendLine()

    if (profile.keyRecords.isNotEmpty()) {
      sb.appendLine("Key | Status | Before(¢) | After(¢) | Inharm(B) | Unison")
      profile.keyRecords.values.sortedBy { it.keyIndex }.forEach { rec ->
        val name = PianoNotes.keyInfo(rec.keyIndex).noteName
        val before = rec.measuredBeforeCents?.let { String.format(Locale.US, "%+.1f", it) } ?: "-"
        val after = rec.lastTunedCents?.let { String.format(Locale.US, "%+.1f", it) } ?: "-"
        val bStr = rec.measuredInharmonicityB?.let { String.format(Locale.US, "%.5f", it) } ?: "-"
        sb.appendLine("$name | ${rec.status.name} | $before | $after | $bStr | ${rec.unisonHealthName ?: "-"}")
      }
      sb.appendLine()
    }

    if (profile.logs.isNotEmpty()) {
      sb.appendLine("TUNING SESSION HISTORY (${profile.logs.size})")
      profile.logs.sortedByDescending { it.timestampEpochMs }.forEach { log ->
        sb.appendLine("- ${fmt.format(Date(log.timestampEpochMs))}: A4=${String.format(Locale.US, "%.1f", log.a4Hz)}Hz, " +
          "InTune=${log.inTuneCount}/88, AvgBefore=${String.format(Locale.US, "%.1f", log.avgInitialDeviationCents)}¢ -> " +
          "AvgAfter=${String.format(Locale.US, "%.1f", log.avgFinalDeviationCents)}¢" +
          if (log.notes.isNotBlank()) " | Notes: ${log.notes}" else ""
        )
      }
    }
    return sb.toString()
  }

  companion object {
    fun averageAbs(values: List<Double>): Double {
      if (values.isEmpty()) return 0.0
      return values.sumOf { abs(it) } / values.size
    }
  }
}
