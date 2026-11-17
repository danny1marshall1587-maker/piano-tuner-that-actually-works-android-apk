package com.danaudio.pianotuner.ui

import com.danaudio.pianotuner.data.KeyMeasurementRecord
import com.danaudio.pianotuner.data.PianoProfile
import com.danaudio.pianotuner.data.PianoStoreState
import com.danaudio.pianotuner.dsp.CyberDenoiserParams
import com.danaudio.pianotuner.dsp.CyberDenoiserSnapshot
import com.danaudio.pianotuner.dsp.DynamicsParams
import com.danaudio.pianotuner.dsp.DynamicsSnapshot
import com.danaudio.pianotuner.dsp.IntervalBeatInfo
import com.danaudio.pianotuner.dsp.PartialMode
import com.danaudio.pianotuner.dsp.PianoNotes
import com.danaudio.pianotuner.dsp.PitchAnalysisResult
import com.danaudio.pianotuner.dsp.StretchCurveResult
import com.danaudio.pianotuner.dsp.StretchTuningEngine

enum class TunerTab(val label: String) {
  TUNER("Tuner"),
  DSP("Cyber DSP"),
  STRETCH("Stretch"),
  PROFILES("Pianos & Logs"),
}

enum class GuidedScriptMode {
  NONE,
  AUTO_RECORD_MAP,
  CHECK_TUNING,
}

data class TunerUiState(
  val selectedTab: TunerTab = TunerTab.TUNER,
  val hasMicPermission: Boolean = false,
  val isListening: Boolean = false,
  val audioSourceName: String = "Idle",
  val keepScreenOn: Boolean = true,

  // Mode & Tuner Controls
  val autoFollowPitch: Boolean = true,
  val activeKeyIndex: Int = PianoNotes.A4_KEY_INDEX,
  val stabilization: Float = 0.42f, // 0..1 wide range
  val partialMode: PartialMode = PartialMode.AUTO,
  val autoLockInTune: Boolean = true,
  val autoAdvanceSweep: Boolean = true,

  // Sine wave tuning fork
  val toneForkPlaying: Boolean = false,
  val toneForkVolume: Float = 0.35f,
  val muteMicDuringFork: Boolean = false,

  // Guided Auto-Map & Check-Tuning Script
  val guidedMode: GuidedScriptMode = GuidedScriptMode.NONE,
  val guidedAllKeys: Boolean = true,
  val guidedStepIndex: Int = 0,
  val guidedTotalCount: Int = PianoNotes.TOTAL_KEYS,
  val guidedPromptMessage: String = "",

  // DSP Parameters & Live Telemetry
  val denoiserParams: CyberDenoiserParams = CyberDenoiserParams(),
  val dynamicsParams: DynamicsParams = DynamicsParams(),
  val denoiserSnapshot: CyberDenoiserSnapshot = CyberDenoiserSnapshot(
    bandLevels = FloatArray(10),
    bandReductions = FloatArray(10) { 1f },
    isLearning = false,
    learnProgress = 0f,
    learnedThresholdsDb = null,
  ),
  val dynamicsSnapshot: DynamicsSnapshot = DynamicsSnapshot(
    inputPeakDb = -90f,
    outputPeakDb = -90f,
    agcGainDb = 0f,
    compReductionDb = 0f,
    gateOpen = false,
  ),
  val pitchResult: PitchAnalysisResult = PitchAnalysisResult(),

  // Stretch Curve & Intervals
  val stretchCurve: StretchCurveResult = StretchTuningEngine.computeStretchCurve(
    a4Hz = 440.0,
    transposeCents = 0.0,
    scaleType = com.danaudio.pianotuner.dsp.PianoScaleType.UPRIGHT,
    temperament = com.danaudio.pianotuner.dsp.TemperamentType.EQUAL,
    stretchFactor = 1.0,
  ),
  val intervalChecks: List<IntervalBeatInfo> = emptyList(),

  // Multi-Piano Profiles & Logs
  val store: PianoStoreState = PianoStoreState(),
  val inTuneHoldProgress: Float = 0f, // 0..1 visual feedback for auto-registering Green
  val statusBanner: String? = null,
) {
  val activeProfile: PianoProfile
    get() = store.profiles.firstOrNull { it.id == store.activeProfileId } ?: store.profiles.first()

  val activeKeyRecords: Map<Int, KeyMeasurementRecord>
    get() = activeProfile.keyRecords

  val tuningForkInfo: Pair<Double, Int>
    get() {
      val target = stretchCurve.targetFrequenciesHz[activeKeyIndex.coerceIn(0, PianoNotes.TOTAL_KEYS - 1)]
      return PianoNotes.tuningForkFrequency(activeKeyIndex, target)
    }
}
