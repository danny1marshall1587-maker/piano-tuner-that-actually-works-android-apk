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

  // Guided Auto-Record Map / Check Tuning state
  private var guidedSequence: List<Int> = emptyList()
  private var guidedSeqPos: Int = 0
  private var guidedHoldFrames: Int = 0
  private var guidedCooldownFrames: Int = 0
  private var guidedPrevKey: Int = -1
  private var guidedLastDriftedKey: Int = -1

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
    _uiState.update {
      it.copy(
        autoFollowPitch = next,
        guidedMode = if (next) GuidedScriptMode.NONE else it.guidedMode,
        guidedPromptMessage = if (next) "" else it.guidedPromptMessage,
      )
    }
  }

  fun setManualKey(keyIndex: Int, switchToManual: Boolean = true) {
    val clamped = keyIndex.coerceIn(0, PianoNotes.TOTAL_KEYS - 1)
    audioEngine.manualKeyIndex = clamped
    if (switchToManual) {
      audioEngine.autoFollow = false
    }
    stableInTuneFrames = 0
    guidedHoldFrames = 0
    guidedCooldownFrames = 6
    _uiState.update { state ->
      val intervals = StretchTuningEngine.computeIntervalChecks(clamped, state.stretchCurve)
      var nextStepIdx = state.guidedStepIndex
      var nextPrompt = state.guidedPromptMessage
      if (state.guidedMode != GuidedScriptMode.NONE && guidedSequence.isNotEmpty()) {
        val found = guidedSequence.indexOfFirst { it >= clamped }
        guidedSeqPos = if (found >= 0) found else guidedSequence.lastIndex
        nextStepIdx = guidedSeqPos
        val actualKey = guidedSequence[guidedSeqPos]
        audioEngine.manualKeyIndex = actualKey
        val noteName = PianoNotes.keyInfo(actualKey).noteName
        nextPrompt = if (state.guidedMode == GuidedScriptMode.CHECK_TUNING) {
          "Play $noteName — check settled pitch"
        } else {
          "Play $noteName — listening..."
        }
        return@update state.copy(
          activeKeyIndex = actualKey,
          autoFollowPitch = false,
          intervalChecks = StretchTuningEngine.computeIntervalChecks(actualKey, state.stretchCurve),
          inTuneHoldProgress = 0f,
          guidedStepIndex = nextStepIdx,
          guidedPromptMessage = nextPrompt,
        )
      }
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

  // --- Sine Wave Tuning Fork (Middle C octave for notes below C4) ---
  fun toggleToneFork() {
    val state = _uiState.value
    if (state.toneForkPlaying) {
      toneGenerator.stop()
      audioEngine.suppressDuringTone = false
      _uiState.update { it.copy(toneForkPlaying = false) }
    } else {
      val (freq, _) = state.tuningForkInfo
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
      val (freq, _) = state.tuningForkInfo
      toneGenerator.setFrequency(freq)
    }
  }

  // --- Guided Auto-Record Map & Check Tuning (Settling) Scripts ---
  private fun buildGuidedSequence(allKeys: Boolean): List<Int> {
    if (allKeys) return (0 until PianoNotes.TOTAL_KEYS).toList()
    val step = _uiState.value.activeProfile.sweepStepMode.semitoneStep.coerceAtLeast(1)
    val list = mutableListOf<Int>()
    var k = 0
    while (k < PianoNotes.TOTAL_KEYS) {
      list.add(k)
      k += step
    }
    if (list.last() != PianoNotes.TOTAL_KEYS - 1) {
      list.add(PianoNotes.TOTAL_KEYS - 1)
    }
    return list
  }

  fun startAutoRecordMap() {
    startGuidedScript(GuidedScriptMode.AUTO_RECORD_MAP)
  }

  fun startCheckTuning() {
    startGuidedScript(GuidedScriptMode.CHECK_TUNING)
  }

  private fun startGuidedScript(mode: GuidedScriptMode) {
    val allKeys = _uiState.value.guidedAllKeys
    val seq = buildGuidedSequence(allKeys)
    if (seq.isEmpty()) return

    guidedSequence = seq
    guidedSeqPos = 0
    guidedHoldFrames = 0
    guidedCooldownFrames = 6
    guidedPrevKey = -1
    guidedLastDriftedKey = -1

    val firstKey = seq.first()
    audioEngine.autoFollow = false
    audioEngine.manualKeyIndex = firstKey
    val firstNote = PianoNotes.keyInfo(firstKey).noteName
    val prompt = if (mode == GuidedScriptMode.CHECK_TUNING) {
      "Play $firstNote — check settled pitch"
    } else {
      "Play $firstNote — listening..."
    }

    _uiState.update { state ->
      state.copy(
        guidedMode = mode,
        autoFollowPitch = false,
        activeKeyIndex = firstKey,
        intervalChecks = StretchTuningEngine.computeIntervalChecks(firstKey, state.stretchCurve),
        guidedStepIndex = 0,
        guidedTotalCount = seq.size,
        guidedPromptMessage = prompt,
        inTuneHoldProgress = 0f,
      )
    }
    updateToneGeneratorFreqIfPlaying()
  }

  fun toggleGuidedAllKeys() {
    val nextAll = !_uiState.value.guidedAllKeys
    _uiState.update { it.copy(guidedAllKeys = nextAll) }
    if (_uiState.value.guidedMode != GuidedScriptMode.NONE) {
      val mode = _uiState.value.guidedMode
      val curKey = _uiState.value.activeKeyIndex
      val seq = buildGuidedSequence(nextAll)
      guidedSequence = seq
      val found = seq.indexOfFirst { it >= curKey }
      guidedSeqPos = if (found >= 0) found else 0
      val targetKey = seq[guidedSeqPos]
      audioEngine.manualKeyIndex = targetKey
      val note = PianoNotes.keyInfo(targetKey).noteName
      _uiState.update { state ->
        state.copy(
          activeKeyIndex = targetKey,
          intervalChecks = StretchTuningEngine.computeIntervalChecks(targetKey, state.stretchCurve),
          guidedStepIndex = guidedSeqPos,
          guidedTotalCount = seq.size,
          guidedPromptMessage = if (mode == GuidedScriptMode.CHECK_TUNING) "Play $note — check settled pitch" else "Play $note — listening...",
        )
      }
      updateToneGeneratorFreqIfPlaying()
    }
  }

  fun skipGuidedKey() {
    if (_uiState.value.guidedMode == GuidedScriptMode.NONE) return
    advanceGuidedStep()
  }

  fun stopGuidedScript() {
    guidedSequence = emptyList()
    guidedHoldFrames = 0
    guidedCooldownFrames = 0
    guidedPrevKey = -1
    guidedLastDriftedKey = -1
    _uiState.update {
      it.copy(
        guidedMode = GuidedScriptMode.NONE,
        guidedPromptMessage = "",
        inTuneHoldProgress = 0f,
      )
    }
  }

  private fun advanceGuidedStep() {
    val mode = _uiState.value.guidedMode
    if (guidedSeqPos + 1 >= guidedSequence.size) {
      val total = guidedSequence.size
      val doneMsg = if (mode == GuidedScriptMode.CHECK_TUNING) {
        "Check Tuning complete ($total keys checked) — settled keys marked Gold!"
      } else {
        "Piano Map complete ($total keys mapped)!"
      }
      guidedSequence = emptyList()
      guidedHoldFrames = 0
      guidedCooldownFrames = 0
      guidedPrevKey = -1
      guidedLastDriftedKey = -1
      _uiState.update {
        it.copy(
          guidedMode = GuidedScriptMode.NONE,
          guidedPromptMessage = "",
          inTuneHoldProgress = 0f,
          statusBanner = doneMsg,
        )
      }
      return
    }

    guidedPrevKey = guidedSequence[guidedSeqPos]
    guidedSeqPos++
    guidedHoldFrames = 0
    guidedCooldownFrames = 12 // ~380ms decay guard so previous note tail doesn't falsely trigger next note
    guidedLastDriftedKey = -1

    val nextKey = guidedSequence[guidedSeqPos]
    audioEngine.manualKeyIndex = nextKey
    val nextNote = PianoNotes.keyInfo(nextKey).noteName
    val prompt = if (mode == GuidedScriptMode.CHECK_TUNING) {
      "Play $nextNote — check settled pitch"
    } else {
      "Play $nextNote — listening..."
    }

    _uiState.update { state ->
      state.copy(
        activeKeyIndex = nextKey,
        intervalChecks = StretchTuningEngine.computeIntervalChecks(nextKey, state.stretchCurve),
        guidedStepIndex = guidedSeqPos,
        guidedPromptMessage = prompt,
        inTuneHoldProgress = 0f,
      )
    }
    updateToneGeneratorFreqIfPlaying()
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
    val key = state.activeKeyIndex
    val cents = if (state.pitchResult.hasSignal) state.pitchResult.smoothedCentsError else 0.0
    if (state.guidedMode == GuidedScriptMode.CHECK_TUNING) {
      markKeyStatusInternal(key, KeyTuningStatus.SETTLED_GOLD, cents)
      val noteName = PianoNotes.keyInfo(key).noteName
      _uiState.update { it.copy(statusBanner = "Verified $noteName Settled [Gold]") }
      advanceGuidedStep()
    } else {
      markKeyStatusInternal(key, KeyTuningStatus.IN_TUNE, cents)
      val noteName = PianoNotes.keyInfo(key).noteName
      _uiState.update { it.copy(statusBanner = "Note OK — $noteName Tuned [Green]") }
      if (state.guidedMode == GuidedScriptMode.AUTO_RECORD_MAP) {
        advanceGuidedStep()
      }
    }
  }

  fun cycleKeyStatus(keyIndex: Int) {
    val existing = _uiState.value.activeKeyRecords[keyIndex]
    val nextStatus = when (existing?.status) {
      null, KeyTuningStatus.UNCHECKED -> KeyTuningStatus.RECORDED
      KeyTuningStatus.RECORDED -> KeyTuningStatus.IN_TUNE
      KeyTuningStatus.IN_TUNE -> KeyTuningStatus.SETTLED_GOLD
      KeyTuningStatus.SETTLED_GOLD -> KeyTuningStatus.UNCHECKED
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

  private fun markKeyStatusInternal(
    keyIndex: Int,
    status: KeyTuningStatus,
    currentCents: Double,
    measuredB: Double? = null,
    unisonLabel: String? = null,
  ) {
    val state = _uiState.value
    val existing = state.activeKeyRecords[keyIndex]
    val pitch = state.pitchResult
    val updatedRec = KeyMeasurementRecord(
      keyIndex = keyIndex,
      status = status,
      measuredBeforeCents = when (status) {
        KeyTuningStatus.RECORDED -> currentCents
        else -> existing?.measuredBeforeCents ?: currentCents
      },
      lastTunedCents = if (status == KeyTuningStatus.IN_TUNE || status == KeyTuningStatus.SETTLED_GOLD) currentCents else existing?.lastTunedCents,
      measuredInharmonicityB = measuredB ?: pitch.measuredInharmonicityB ?: existing?.measuredInharmonicityB,
      unisonHealthName = unisonLabel ?: if (pitch.hasSignal) pitch.unison.health.label else existing?.unisonHealthName,
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
    val inTuneCnt = recs.count { it.status == KeyTuningStatus.IN_TUNE || it.status == KeyTuningStatus.SETTLED_GOLD }

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

    val tol = state.activeProfile.inTuneToleranceCents
    var holdProgress = 0f
    var bannerOverride: String? = null

    if (state.guidedMode != GuidedScriptMode.NONE) {
      if (guidedCooldownFrames > 0) {
        guidedCooldownFrames--
      } else {
        val isHighTreble = activeKey >= 68
        val matchesTarget = p.hasSignal && p.confidence >= 0.40 &&
          (!p.isTransientHold || (isHighTreble && guidedHoldFrames > 0)) &&
          (p.bestAutoKeyIndex == activeKey || (activeKey < 12 && abs(p.centsError) <= 50.0 && p.bestAutoKeyIndex != guidedPrevKey))

        if (matchesTarget) {
          guidedHoldFrames++
          val requiredFrames = when (state.guidedMode) {
            GuidedScriptMode.AUTO_RECORD_MAP -> if (isHighTreble) 3 else 6
            GuidedScriptMode.CHECK_TUNING -> if (isHighTreble) 4 else 9
            GuidedScriptMode.NONE -> 6
          }
          holdProgress = (guidedHoldFrames.toFloat() / requiredFrames.toFloat()).coerceIn(0f, 1f)

          if (guidedHoldFrames >= requiredFrames) {
            val noteName = PianoNotes.keyInfo(activeKey).noteName
            val inTune = abs(p.smoothedCentsError) <= tol
            if (state.guidedMode == GuidedScriptMode.AUTO_RECORD_MAP) {
              val status = if (inTune) KeyTuningStatus.IN_TUNE else KeyTuningStatus.RECORDED
              markKeyStatusInternal(
                keyIndex = activeKey,
                status = status,
                currentCents = p.smoothedCentsError,
                measuredB = p.measuredInharmonicityB,
                unisonLabel = p.unison.health.label,
              )
              bannerOverride = if (inTune) {
                "$noteName in tune (${String.format("%+.1f¢", p.smoothedCentsError)}) [Green]"
              } else {
                "Mapped $noteName (${String.format("%+.1f¢", p.smoothedCentsError)}) [Yellow]"
              }
              advanceGuidedStep()
            } else if (state.guidedMode == GuidedScriptMode.CHECK_TUNING) {
              if (inTune) {
                markKeyStatusInternal(
                  keyIndex = activeKey,
                  status = KeyTuningStatus.SETTLED_GOLD,
                  currentCents = p.smoothedCentsError,
                  measuredB = p.measuredInharmonicityB,
                  unisonLabel = p.unison.health.label,
                )
                bannerOverride = "$noteName settled & in tune! [Gold]"
                advanceGuidedStep()
              } else {
                // Note drifted out of tolerance: mark Yellow once and let user tune it into Gold or Skip
                if (guidedLastDriftedKey != activeKey) {
                  guidedLastDriftedKey = activeKey
                  markKeyStatusInternal(
                    keyIndex = activeKey,
                    status = KeyTuningStatus.RECORDED,
                    currentCents = p.smoothedCentsError,
                    measuredB = p.measuredInharmonicityB,
                    unisonLabel = p.unison.health.label,
                  )
                }
                _uiState.update {
                  it.copy(
                    guidedPromptMessage = "$noteName drifted ${String.format("%+.1f¢", p.smoothedCentsError)} — tune to Gold or Skip",
                  )
                }
                guidedHoldFrames = requiredFrames - 2
              }
            }
          }
        } else if (!p.hasSignal) {
          guidedHoldFrames = 0
        }
      }
    } else if (state.autoLockInTune && p.hasSignal && p.confidence >= 0.45) {
      // Fast auto-check-off: when testing or tuning, any note heard in tune turns Green automatically
      val isHighTreble = activeKey >= 64
      val validFrame = !p.isTransientHold || (isHighTreble && stableInTuneFrames > 0)
      if (validFrame && abs(p.smoothedCentsError) <= tol) {
        if (stableInTuneKey != activeKey) {
          stableInTuneKey = activeKey
          stableInTuneFrames = 1
        } else {
          stableInTuneFrames++
        }
        val required = if (isHighTreble) 5 else 10
        holdProgress = (stableInTuneFrames.toFloat() / required.toFloat()).coerceIn(0f, 1f)
        if (stableInTuneFrames == required) {
          val existingStatus = state.activeKeyRecords[activeKey]?.status
          // Keep Gold if already verified settled; otherwise mark Green (IN_TUNE)
          val targetStatus = if (existingStatus == KeyTuningStatus.SETTLED_GOLD) {
            KeyTuningStatus.SETTLED_GOLD
          } else {
            KeyTuningStatus.IN_TUNE
          }
          markKeyStatusInternal(activeKey, targetStatus, p.smoothedCentsError)
        }
      } else if (!p.hasSignal || abs(p.smoothedCentsError) > tol) {
        stableInTuneFrames = 0
      }
    } else {
      stableInTuneFrames = 0
    }

    _uiState.update { cur ->
      cur.copy(
        audioSourceName = telemetry.audioSourceName,
        activeKeyIndex = if (cur.guidedMode != GuidedScriptMode.NONE) cur.activeKeyIndex else activeKey,
        intervalChecks = if (cur.guidedMode != GuidedScriptMode.NONE) cur.intervalChecks else intervals,
        denoiserParams = nextDenoiserParams,
        denoiserSnapshot = telemetry.denoiser,
        dynamicsSnapshot = telemetry.dynamics,
        pitchResult = p,
        inTuneHoldProgress = holdProgress,
        statusBanner = when {
          learned != null -> "Cyber-Denoiser learned room noise floor!"
          bannerOverride != null -> bannerOverride
          else -> cur.statusBanner
        },
      )
    }
  }

  override fun onCleared() {
    super.onCleared()
    audioEngine.stop()
    toneGenerator.stop()
  }
}
