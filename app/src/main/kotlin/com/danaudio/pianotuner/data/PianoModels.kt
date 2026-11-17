package com.danaudio.pianotuner.data

import com.danaudio.pianotuner.dsp.PianoScaleType
import com.danaudio.pianotuner.dsp.SweepStepMode
import com.danaudio.pianotuner.dsp.TemperamentType
import kotlinx.serialization.Serializable

@Serializable
enum class KeyTuningStatus {
  UNCHECKED,
  RECORDED, // Yellow: recorded measurement / sampled in sweep / drifted during check
  IN_TUNE, // Green: registered as in tune
  SETTLED_GOLD, // Gold: checked during Check Tuning pass and verified in tune & settled
}

@Serializable
data class KeyMeasurementRecord(
  val keyIndex: Int,
  val status: KeyTuningStatus = KeyTuningStatus.UNCHECKED,
  val measuredBeforeCents: Double? = null,
  val lastTunedCents: Double? = null,
  val measuredInharmonicityB: Double? = null,
  val unisonHealthName: String? = null,
  val updatedAtEpochMs: Long = 0L,
)

@Serializable
data class TuningSessionLog(
  val id: String,
  val timestampEpochMs: Long,
  val a4Hz: Double,
  val transposeCents: Double,
  val temperamentName: String,
  val stretchFactor: Double,
  val overpullUsed: Boolean,
  val recordedCount: Int,
  val inTuneCount: Int,
  val avgInitialDeviationCents: Double,
  val avgFinalDeviationCents: Double,
  val notes: String = "",
  val keySnapshots: List<KeyMeasurementRecord> = emptyList(),
)

@Serializable
data class PianoProfile(
  val id: String,
  val name: String,
  val makeAndModel: String = "",
  val locationOrOwner: String = "",
  val serialNumber: String = "",
  val scaleType: PianoScaleType = PianoScaleType.UPRIGHT,
  val a4TargetHz: Double = 440.0,
  val transposeCents: Double = 0.0,
  val temperament: TemperamentType = TemperamentType.EQUAL,
  val stretchFactor: Double = 1.0,
  val bassStretchBias: Double = 1.0,
  val trebleStretchBias: Double = 1.0,
  val inTuneToleranceCents: Double = 1.8,
  val sweepStepMode: SweepStepMode = SweepStepMode.EVERY_THIRD,
  val overpullEnabled: Boolean = false,
  val overpullPercent: Double = 0.25,
  val keyRecords: Map<Int, KeyMeasurementRecord> = emptyMap(),
  val logs: List<TuningSessionLog> = emptyList(),
  val createdAtEpochMs: Long = System.currentTimeMillis(),
  val updatedAtEpochMs: Long = System.currentTimeMillis(),
)

@Serializable
data class PianoStoreState(
  val activeProfileId: String = "default-piano",
  val profiles: List<PianoProfile> = listOf(
    PianoProfile(
      id = "default-piano",
      name = "Studio Upright #1",
      makeAndModel = "Yamaha U1",
      locationOrOwner = "Main Studio",
      scaleType = PianoScaleType.UPRIGHT,
    )
  ),
)
