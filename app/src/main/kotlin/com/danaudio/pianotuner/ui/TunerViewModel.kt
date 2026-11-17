package com.danaudio.pianotuner.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.danaudio.pianotuner.audio.AudioCaptureEngine
import com.danaudio.pianotuner.audio.AudioFrameTelemetry
import com.danaudio.pianotuner.audio.ToneGenerator
import com.danaudio.pianotuner.data.KeyMeasurementRecord
import com.danaudio.pianotuner.data.KeyTuningStatus
import com.danaudio.pianotuner.data.PianoProfile
import com.danaudio.pianotuner.data.PianoProfileRepository
import com.danaudio.pianotuner.data.PianoStoreState
import com.danaudio.pianotuner.data.TuningSessionLog
import com.danaudio.pianotuner.dsp.CyberDenoiserParams
import com.danaudio.pianotuner.dsp.DynamicsParams
import com.danaudio.pianotuner.dsp.PartialMode
import com.danaudio.pianotuner.dsp.PianoNotes
import com.danaudio.pianotuner.dsp.PianoScaleType
import com.danaudio.pianotuner.dsp.StretchCurveResult
import com.danaudio.pianotuner.dsp.StretchTuningEngine
import com.danaudio.pianotuner.dsp.SweepStepMode
import com.danaudio.pianotuner.dsp.TemperamentType
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TunerViewModel(application: Application) : AndroidViewModel(application) {

  private val repository = PianoProfileRepository(File(application.filesDir, "piano_profiles.json"))
  private val toneGenerator = ToneGenerator(AudioCaptureEngine.SAMPLE_RATE)
  private val audioEngine = AudioCaptureEngine(::handleTelemetry)

  private val _uiState = MutableStateFlow(TunerUiState())
  val uiState: StateFlow<TunerUiState> = _uiState.asStateFlow()

  private var stableInTuneFrames = 0
  private var stableInTuneKey = -1
  private var userPausedMic = false
  private var saveJob: Job? = null

  init {
    val loaded = repository.loadStore()
    _uiState.update { it.copy(store = loaded) }
    recomputeStretchCurve()
  }

  fun onPermissionResult(granted: Boolean) {
    _uiState.update { it.copy(hasMicPermission = granted) }
    if (granted && !userPausedMic) {
      startListening(byUser = false)
    }
  }

  fun startListening(byUser: Boolean = true) {
    if (byUser) userPausedMic = false
    if (!_uiState.value.hasMicPermission) return
    syncEngineParams()
    audioEngine.start()
    _uiState.update { it.copy(isListening = true) }
  }

  fun stopListening(byUser: Boolean = true) {
    if (byUser) userPausedMic = true
    audioEngine.stop()
    _uiState.update { it.copy(isListening = false, audioSourceName = "Stopped") }
  }

  fun onAppForeground() {
    if (_uiState.value.hasMicPermission && !userPausedMic && !_uiState.value.isListening) {
      startListening(byUser = false)
    }
  }

  fun onAppBackground() {
    if (_uiState.value.isListening) {
      stopListening(byUser = false)
    }
    if (_uiState.value.toneForkPlaying) {
      toneGenerator.stop()
      audioEngine.suppressDuringTone = false
      _uiState.update { it.copy(toneForkPlaying = false) }
    }
  }

  fun selectTab(tab: TunerTab) {
    _uiState.update { it.copy(selectedTab = tab) }
  }

  fun toggleKeepScreenOn() {
    _uiState.update { it.copy(keepScreenOn = !it.keepScreenOn) }
  }

  fun toggleAutoFollow() {
    val next = !_uiState.value.autoFollowPitch
    audioEngine.autoFollow = next
    _uiState.update { it.copy(autoFollowPitch = next) }
  }

  fun setManualKey(keyIndex: Int, switchToManual: Boolean = true) {
    val clamped = keyIndex.coerceIn(0, PianoNotes.TOTAL_KEYS - 1)
    audioEngine.manualKeyIndex = clamped
    if (switchToManual) {
      audioEngine.autoFollow = false
    }
    stableInTuneFrames = 0
    _uiState.update { state ->
      val intervals = StretchTuningEngine.computeIntervalChecks(clamped, state.stretchCurve)
      state.copy(
        activeKeyIndex = clamped,
        autoFollowPitch = if (switchToManual) false else state.autoFollowPitch,
        intervalChecks = intervals,
        inTuneHoldProgress = 0f,
      )
    }
    updateToneGeneratorFreqIfPlaying()
  }

  fun stepSweepKey(forward: Boolean) {
    val state = _uiState.value
    val step = state.activeProfile.sweepStepMode.semitoneStep
    val delta = if (forward) step else -step
    val nextKey = (state.activeKeyIndex + delta).coerceIn(0, PianoNotes.TOTAL_KEYS - 1)
    setManualKey(nextKey, switchToManual = true)
  }

  fun setStabilization(value: Float) {
    val v = value.coerceIn(0f, 1f)
    audioEngine.stabilization = v
    _uiState.update { it.copy(stabilization = v) }
  }

  fun setPartialMode(mode: PartialMode) {
    audioEngine.partialMode = mode
    _uiState.update { it.copy(partialMode = mode) }
  }

  fun toggleAutoLockInTune() {
    _uiState.update { it.copy(autoLockInTune = !it.autoLockInTune) }
  }

  fun toggleAutoAdvanceSweep() {
    _uiState.update { it.copy(autoAdvanceSweep = !it.autoAdvanceSweep) }
  }

  // --- Sine Wave Tuning Fork ---
  fun toggleToneFork() {
    val state = _uiState.value
    if (state.toneForkPlaying) {
      toneGenerator.stop()
      audioEngine.suppressDuringTone = false
      _uiState.update { it.copy(toneForkPlaying = false) }
    } else {
      val freq = state.stretchCurve.targetFrequenciesHz[state.activeKeyIndex]
      toneGenerator.setVolume(state.toneForkVolume)
      toneGenerator.start(freq)
      audioEngine.suppressDuringTone = state.muteMicDuringFork
      _uiState.update { it.copy(toneForkPlaying = true) }
    }
  }

  fun setToneForkVolume(vol: Float) {
    val v = vol.coerceIn(0.05f, 0.85f)
    toneGenerator.setVolume(v)
    _uiState.update { it.copy(toneForkVolume = v) }
  }

  fun toggleMuteMicDuringFork() {
    val next = !_uiState.value.muteMicDuringFork
    audioEngine.suppressDuringTone = next && _uiState.value.toneForkPlaying
    _uiState.update { it.copy(muteMicDuringFork = next) }
  }

  private fun updateToneGeneratorFreqIfPlaying() {
    val state = _uiState.value
    if (state.toneForkPlaying) {
      val freq = state.stretchCurve.targetFrequenciesHz[state.activeKeyIndex]
      toneGenerator.setFrequency(freq)
    }
  }

  // --- Key Recording (Yellow) & In-Tune Registration (Green) ---
  fun recordActiveKeyYellow() {
    val state = _uiState.value
    val key = state.activeKeyIndex
    val pitch = state.pitchResult
    val existing = state.activeKeyRecords[key]
    val centsToSave = if (pitch.hasSignal) pitch.smoothedCentsError else existing?.measuredBeforeCents
    val bToSave = pitch.measuredInharmonicityB ?: existing?.measuredInharmonicityB

    val updatedRec = KeyMeasurementRecord(
      keyIndex = key,
      status = KeyTuningStatus.RECORDED,
      measuredBeforeCents = centsToSave,
      lastTunedCents = existing?.lastTunedCents,
      measuredInharmonicityB = bToSave,
      unisonHealthName = if (pitch.hasSignal) pitch.unison.health.label else existing?.unisonHealthName,
      updatedAtEpochMs = System.currentTimeMillis(),
    )

    updateActiveProfile { profile ->
      val newRecords = profile.keyRecords.toMutableMap()
      newRecords[key] = updatedRec
      profile.copy(keyRecords = newRecords, updatedAtEpochMs = System.currentTimeMillis())
    }
    recomputeStretchCurve()

    val noteName = PianoNotes.keyInfo(key).noteName
    val bMsg = bToSave?.let { " | B=${String.format("%.5f", it)}" } ?: ""
    val cMsg = centsToSave?.let { String.format(" (%+.1f¢)", it) } ?: ""
    _uiState.update { it.copy(statusBanner = "Recorded $noteName$cMsg$bMsg [Yellow]") }

    if (state.autoAdvanceSweep && !state.autoFollowPitch) {
      stepSweepKey(forward = true)
    }
  }

  fun markActiveKeyInTuneGreen() {
    val state = _uiState.value
    markKeyInTuneGreenInternal(state.activeKeyIndex, state.pitchResult.smoothedCentsError)
    val noteName = PianoNotes.keyInfo(state.activeKeyIndex).noteName
    _uiState.update { it.copy(statusBanner = "Registered $noteName In Tune [Green]") }
  }

  fun cycleKeyStatus(keyIndex: Int) {
    val existing = _uiState.value.activeKeyRecords[keyIndex]
    val nextStatus = when (existing?.status) {
      null, KeyTuningStatus.UNCHECKED -> KeyTuningStatus.RECORDED
      KeyTuningStatus.RECORDED -> KeyTuningStatus.IN_TUNE
      KeyTuningStatus.IN_TUNE -> KeyTuningStatus.UNCHECKED
    }
    updateActiveProfile { profile ->
      val map = profile.keyRecords.toMutableMap()
      if (nextStatus == KeyTuningStatus.UNCHECKED) {
        map.remove(keyIndex)
      } else {
        map[keyIndex] = (existing ?: KeyMeasurementRecord(keyIndex)).copy(
          status = nextStatus,
          updatedAtEpochMs = System.currentTimeMillis(),
        )
      }
      profile.copy(keyRecords = map, updatedAtEpochMs = System.currentTimeMillis())
    }
    recomputeStretchCurve()
  }

  private fun markKeyInTuneGreenInternal(keyIndex: Int, currentCents: Double) {
    val state = _uiState.value
    val existing = state.activeKeyRecords[keyIndex]
    val pitch = state.pitchResult
    val updatedRec = KeyMeasurementRecord(
      keyIndex = keyIndex,
      status = KeyTuningStatus.IN_TUNE,
      measuredBeforeCents = existing?.measuredBeforeCents ?: currentCents,
      lastTunedCents = currentCents,
      measuredInharmonicityB = pitch.measuredInharmonicityB ?: existing?.measuredInharmonicityB,
      unisonHealthName = if (pitch.hasSignal) pitch.unison.health.label else existing?.unisonHealthName,
      updatedAtEpochMs = System.currentTimeMillis(),
    )
    updateActiveProfile { profile ->
      val newRecords = profile.keyRecords.toMutableMap()
      newRecords[keyIndex] = updatedRec
      profile.copy(keyRecords = newRecords, updatedAtEpochMs = System.currentTimeMillis())
    }
    recomputeStretchCurve()
  }

  fun clearAllKeyStatuses() {
    updateActiveProfile { it.copy(keyRecords = emptyMap(), updatedAtEpochMs = System.currentTimeMillis()) }
    recomputeStretchCurve()
    _uiState.update { it.copy(statusBanner = "Cleared keyboard tuning markers") }
  }

  // --- Cyber-Denoiser & Dynamics Updates ---
  fun updateDenoiserParams(transform: (CyberDenoiserParams) -> CyberDenoiserParams) {
    val next = transform(_uiState.value.denoiserParams)
    audioEngine.denoiserParams = next
    _uiState.update { it.copy(denoiserParams = next) }
  }

  fun setDenoiserMasterThreshold(masterDb: Float) {
    val clamped = masterDb.coerceIn(-100f, 0f)
    updateDenoiserParams { prev ->
      val delta = clamped - prev.masterThresholdDb
      val shifted = FloatArray(10) { i -> (prev.bandThresholdsDb[i] + delta).coerceIn(-100f, 0f) }
      prev.copy(masterThresholdDb = clamped, bandThresholdsDb = shifted)
    }
  }

  fun setDenoiserBandThreshold(band: Int, db: Float) {
    updateDenoiserParams { prev ->
      val arr = prev.bandThresholdsDb.copyOf()
      arr[band] = db.coerceIn(-100f, 0f)
      prev.copy(bandThresholdsDb = arr)
    }
  }

  fun startCyberLearn() {
    val dur = _uiState.value.denoiserParams.learnDurationSec
    audioEngine.startCyberLearn(dur)
    _uiState.update { it.copy(statusBanner = "Cyber-Denoiser learning ambient noise (${dur.toInt()}s)...") }
  }

  fun updateDynamicsParams(transform: (DynamicsParams) -> DynamicsParams) {
    val next = transform(_uiState.value.dynamicsParams)
    audioEngine.dynamicsParams = next
    _uiState.update { it.copy(dynamicsParams = next) }
  }

  // --- Stretch & Pitch Calibration Updates ---
  fun updateActiveProfileCalibration(transform: (PianoProfile) -> PianoProfile) {
    updateActiveProfile { profile ->
      transform(profile).copy(updatedAtEpochMs = System.currentTimeMillis())
    }
    recomputeStretchCurve()
  }

  private fun recomputeStretchCurve() {
    val profile = _uiState.value.activeProfile
    val measuredB = mutableMapOf<Int, Double>()
    val beforeCents = mutableMapOf<Int, Double>()
    profile.keyRecords.forEach { (k, rec) ->
      rec.measuredInharmonicityB?.let { measuredB[k] = it }
      rec.measuredBeforeCents?.let { beforeCents[k] = it }
    }
    val curve = StretchTuningEngine.computeStretchCurve(
      a4Hz = profile.a4TargetHz,
      transposeCents = profile.transposeCents,
      scaleType = profile.scaleType,
      temperament = profile.temperament,
      stretchFactor = profile.stretchFactor,
      bassBias = profile.bassStretchBias,
      trebleBias = profile.trebleStretchBias,
      measuredB = measuredB,
      recordedCentsBefore = beforeCents,
      overpullEnabled = profile.overpullEnabled,
      overpullPercent = profile.overpullPercent,
    )
    audioEngine.stretchCurve = curve
    val intervals = StretchTuningEngine.computeIntervalChecks(_uiState.value.activeKeyIndex, curve)
    _uiState.update { it.copy(stretchCurve = curve, intervalChecks = intervals) }
    updateToneGeneratorFreqIfPlaying()
  }

  private fun saveStoreAsync(store: PianoStoreState) {
    saveJob?.cancel()
    saveJob = viewModelScope.launch(Dispatchers.IO) {
      delay(150)
      repository.saveStore(store)
    }
  }

  // --- Multi-Piano Profiles & Tuning Logs ---
  fun switchProfile(profileId: String) {
    _uiState.update { state ->
      val nextStore = state.store.copy(activeProfileId = profileId)
      saveStoreAsync(nextStore)
      state.copy(store = nextStore, statusBanner = "Loaded piano profile")
    }
    recomputeStretchCurve()
  }

  fun createProfile(
    name: String,
    makeAndModel: String,
    locationOrOwner: String,
    serialNumber: String,
    scaleType: PianoScaleType,
  ) {
    val newId = UUID.randomUUID().toString()
    val profile = PianoProfile(
      id = newId,
      name = name.ifBlank { "Piano ${_uiState.value.store.profiles.size + 1}" },
      makeAndModel = makeAndModel,
      locationOrOwner = locationOrOwner,
      serialNumber = serialNumber,
      scaleType = scaleType,
    )
    _uiState.update { state ->
      val nextStore = state.store.copy(
        activeProfileId = newId,
        profiles = listOf(profile) + state.store.profiles,
      )
      saveStoreAsync(nextStore)
      state.copy(store = nextStore, statusBanner = "Created profile: ${profile.name}")
    }
    recomputeStretchCurve()
  }

  fun deleteProfile(profileId: String) {
    val current = _uiState.value.store.profiles
    if (current.size <= 1) return
    val remaining = current.filterNot { it.id == profileId }
    val nextActive = if (_uiState.value.store.activeProfileId == profileId) remaining.first().id else _uiState.value.store.activeProfileId
    _uiState.update { state ->
      val nextStore = state.store.copy(activeProfileId = nextActive, profiles = remaining)
      saveStoreAsync(nextStore)
      state.copy(store = nextStore, statusBanner = "Deleted piano profile")
    }
    recomputeStretchCurve()
  }

  fun saveTuningSessionLog(notes: String) {
    val profile = _uiState.value.activeProfile
    val recs = profile.keyRecords.values.toList()
    val beforeVals = recs.mapNotNull { it.measuredBeforeCents }
    val afterVals = recs.mapNotNull { it.lastTunedCents ?: it.measuredBeforeCents }
    val recordedCnt = recs.count { it.status == KeyTuningStatus.RECORDED }
    val inTuneCnt = recs.count { it.status == KeyTuningStatus.IN_TUNE }

    val log = TuningSessionLog(
      id = UUID.randomUUID().toString(),
      timestampEpochMs = System.currentTimeMillis(),
      a4Hz = profile.a4TargetHz,
      transposeCents = profile.transposeCents,
      temperamentName = profile.temperament.label,
      stretchFactor = profile.stretchFactor,
      overpullUsed = profile.overpullEnabled,
      recordedCount = recordedCnt,
      inTuneCount = inTuneCnt,
      avgInitialDeviationCents = PianoProfileRepository.averageAbs(beforeVals),
      avgFinalDeviationCents = PianoProfileRepository.averageAbs(afterVals),
      notes = notes,
      keySnapshots = recs.sortedBy { it.keyIndex },
    )

    updateActiveProfile { p ->
      p.copy(
        logs = listOf(log) + p.logs,
        updatedAtEpochMs = System.currentTimeMillis(),
      )
    }
    _uiState.update { it.copy(statusBanner = "Saved tuning log (${inTuneCnt}/88 in tune)") }
  }

  fun buildExportReport(): String = repository.formatProfileReport(_uiState.value.activeProfile)

  fun dismissBanner() {
    _uiState.update { it.copy(statusBanner = null) }
  }

  private fun updateActiveProfile(transform: (PianoProfile) -> PianoProfile) {
    _uiState.update { state ->
      val updatedList = state.store.profiles.map { p ->
        if (p.id == state.store.activeProfileId) transform(p) else p
      }
      val nextStore = state.store.copy(profiles = updatedList)
      saveStoreAsync(nextStore)
      state.copy(store = nextStore)
    }
  }

  private fun syncEngineParams() {
    val s = _uiState.value
    audioEngine.denoiserParams = s.denoiserParams
    audioEngine.dynamicsParams = s.dynamicsParams
    audioEngine.stretchCurve = s.stretchCurve
    audioEngine.autoFollow = s.autoFollowPitch
    audioEngine.manualKeyIndex = s.activeKeyIndex
    audioEngine.partialMode = s.partialMode
    audioEngine.stabilization = s.stabilization
  }

  private fun handleTelemetry(telemetry: AudioFrameTelemetry) {
    val state = _uiState.value

    // If Cyber-Denoiser Learn just finished, persist newly learned 10-band thresholds
    val learned = telemetry.denoiser.learnedThresholdsDb
    val nextDenoiserParams = if (learned != null) {
      val avgDb = learned.average().toFloat().coerceIn(-100f, 0f)
      val updated = state.denoiserParams.copy(masterThresholdDb = avgDb, bandThresholdsDb = learned)
      audioEngine.denoiserParams = updated
      updated
    } else {
      state.denoiserParams
    }

    val p = telemetry.pitch
    val activeKey = if (state.autoFollowPitch && p.hasSignal) p.detectedKeyIndex else state.activeKeyIndex
    val intervals = if (activeKey != state.activeKeyIndex) {
      StretchTuningEngine.computeIntervalChecks(activeKey, state.stretchCurve)
    } else {
      state.intervalChecks
    }

    // Auto-register Green when steady inside tolerance for ~1.1s (~34 frames at 31 Hz)
    var holdProgress = 0f
    if (state.autoLockInTune && p.hasSignal && !p.isTransientHold && p.confidence >= 0.45) {
      val tol = state.activeProfile.inTuneToleranceCents
      if (abs(p.smoothedCentsError) <= tol) {
        if (stableInTuneKey != activeKey) {
          stableInTuneKey = activeKey
          stableInTuneFrames = 1
        } else {
          stableInTuneFrames++
        }
        holdProgress = (stableInTuneFrames / 32f).coerceIn(0f, 1f)
        if (stableInTuneFrames == 32) {
          markKeyInTuneGreenInternal(activeKey, p.smoothedCentsError)
        }
      } else {
        stableInTuneFrames = 0
      }
    } else {
      stableInTuneFrames = 0
    }

    _uiState.update { cur ->
      cur.copy(
        audioSourceName = telemetry.audioSourceName,
        activeKeyIndex = activeKey,
        intervalChecks = intervals,
        denoiserParams = nextDenoiserParams,
        denoiserSnapshot = telemetry.denoiser,
        dynamicsSnapshot = telemetry.dynamics,
        pitchResult = p,
        inTuneHoldProgress = holdProgress,
        statusBanner = if (learned != null) "Cyber-Denoiser learned room noise floor!" else cur.statusBanner,
      )
    }
  }

  override fun onCleared() {
    super.onCleared()
    audioEngine.stop()
    toneGenerator.stop()
  }
}
