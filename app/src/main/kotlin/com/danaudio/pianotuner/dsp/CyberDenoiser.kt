package com.danaudio.pianotuner.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

enum class LowCutMode(val label: String, val cutoffHz: Float) {
  OFF("Off", 0f),
  PIANO_SAFE("22 Hz (A0 Safe)", 22f),
  STANDARD_40("40 Hz (Cyber Std)", 40f),
}

data class CyberDenoiserParams(
  val enabled: Boolean = true,
  val masterThresholdDb: Float = -58f, // -100..0 dB
  val bandThresholdsDb: FloatArray = FloatArray(10) { -58f },
  val attackMs: Float = 0.3f, // 0.1..100 ms
  val releaseMs: Float = 90f, // 10..1000 ms
  val floor: Float = 0.04f, // 0..1
  val rmsMode: Boolean = false,
  val lowCutMode: LowCutMode = LowCutMode.PIANO_SAFE,
  val highCutEnabled: Boolean = true,
  val listenNoiseOnly: Boolean = false,
  val learnDurationSec: Float = 3.0f,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is CyberDenoiserParams) return false
    return enabled == other.enabled &&
      masterThresholdDb == other.masterThresholdDb &&
      bandThresholdsDb.contentEquals(other.bandThresholdsDb) &&
      attackMs == other.attackMs &&
      releaseMs == other.releaseMs &&
      floor == other.floor &&
      rmsMode == other.rmsMode &&
      lowCutMode == other.lowCutMode &&
      highCutEnabled == other.highCutEnabled &&
      listenNoiseOnly == other.listenNoiseOnly &&
      learnDurationSec == other.learnDurationSec
  }

  override fun hashCode(): Int {
    var result = enabled.hashCode()
    result = 31 * result + masterThresholdDb.hashCode()
    result = 31 * result + bandThresholdsDb.contentHashCode()
    return result
  }
}

data class CyberDenoiserSnapshot(
  val bandLevels: FloatArray, // linear 0..1
  val bandReductions: FloatArray, // gain 0..1 (1 = no reduction)
  val isLearning: Boolean,
  val learnProgress: Float, // 0..1
  val learnedThresholdsDb: FloatArray?, // non-null on frame learn finishes
) {
  companion object {
    val BAND_LABELS = arrayOf("60", "150", "400", "800", "1.5k", "3k", "5k", "8k", "12k", "16k")
  }
}

/**
 * Faithful Kotlin DSP port of Danny Marshall's Cyber-Denoiser PRO (de-noise-pro-vst).
 *
 * Uses 9 parallel 1st-order low-pass filters subtracted sequentially to form 10 zero-latency
 * phase-coherent bands, dual-rate peak (2ms/60ms) or 50ms RMS level followers, adaptive
 * tail-stabilized release to prevent chirping, and Learn Mode ambient profiling.
 */
class CyberDenoiser(private val sampleRate: Float = 48_000f) {

  companion object {
    val CROSSOVER_FREQS = floatArrayOf(60f, 150f, 400f, 800f, 1500f, 3000f, 5000f, 8000f, 12000f)
  }

  private val lpStates = FloatArray(9)
  private var hpCutState = 0f
  private var lpCutState = 0f

  private val levelStates = FloatArray(10)
  private val currentGains = FloatArray(10) { 1f }
  private val bandBuf = FloatArray(10)

  private val peakLevels = FloatArray(10) { 1e-6f }

  @Volatile
  private var learning = false
  private var learnElapsedSec = 0.0
  private var learnDurationSec = 3.0f
  private var pendingLearnedThresholds: FloatArray? = null

  private val bCoeffs = FloatArray(9) { i ->
    min(0.99f, onePoleCoeff(CROSSOVER_FREQS[i]))
  }
  private val lpCutCoeff = onePoleCoeff(18_000f)
  private val levelAtt = 1f - exp(-1f / (2.0f * sampleRate / 1000f))
  private val levelRel = 1f - exp(-1f / (60.0f * sampleRate / 1000f))
  private val rmsCoeff = 1f - exp(-1f / (0.050f * sampleRate))

  private fun onePoleCoeff(freqHz: Float): Float {
    if (freqHz <= 0f) return 0f
    return (1.0 - exp(-2.0 * Math.PI * freqHz / max(1f, sampleRate))).toFloat()
  }

  fun reset() {
    lpStates.fill(0f)
    hpCutState = 0f
    lpCutState = 0f
    levelStates.fill(0f)
    currentGains.fill(1f)
    peakLevels.fill(1e-6f)
    learning = false
    learnElapsedSec = 0.0
    pendingLearnedThresholds = null
  }

  fun startLearn(durationSec: Float = 3.0f) {
    peakLevels.fill(1e-6f)
    learnElapsedSec = 0.0
    learnDurationSec = durationSec.coerceIn(1f, 30f)
    pendingLearnedThresholds = null
    learning = true
  }

  fun isLearning(): Boolean = learning

  fun processBlock(
    input: FloatArray,
    output: FloatArray,
    numSamples: Int,
    params: CyberDenoiserParams,
  ): CyberDenoiserSnapshot {
    if (!params.enabled && !learning) {
      input.copyInto(output, endIndex = numSamples)
      return CyberDenoiserSnapshot(
        bandLevels = levelStates.copyOf(),
        bandReductions = FloatArray(10) { 1f },
        isLearning = false,
        learnProgress = 0f,
        learnedThresholdsDb = null,
      )
    }

    val attCoeff = 1f - exp(-1f / (max(0.1f, params.attackMs) * sampleRate / 1000f))
    val hpCoeff = onePoleCoeff(params.lowCutMode.cutoffHz)
    val useLowCut = params.lowCutMode != LowCutMode.OFF
    val useHighCut = params.highCutEnabled
    val floorV = params.floor.coerceIn(0f, 0.99f)
    val isRms = params.rmsMode
    val listenNoise = params.listenNoiseOnly

    val tLinear = FloatArray(10) { i ->
      10f.pow(params.bandThresholdsDb[i].coerceIn(-100f, 0f) * 0.05f)
    }

    for (s in 0 until numSamples) {
      var s0 = input[s]

      // 1. Low Cut / High Cut 1st-order filters
      if (useLowCut) {
        hpCutState += (s0 - hpCutState) * hpCoeff
        s0 -= hpCutState
      }
      if (useHighCut) {
        lpCutState += (s0 - lpCutState) * lpCutCoeff
        s0 = lpCutState
      }

      // 2. 10-Band Parallel Filter Bank via subtraction
      for (i in 0 until 9) {
        lpStates[i] += (s0 - lpStates[i]) * bCoeffs[i]
        bandBuf[i] = if (i == 0) lpStates[0] else lpStates[i] - lpStates[i - 1]
      }
      bandBuf[9] = s0 - lpStates[8]

      // 3. Level detection + subtractive spectral gain + adaptive tail release
      var outSum = 0f
      for (i in 0 until 10) {
        val bi = bandBuf[i]
        val r: Float
        if (isRms) {
          levelStates[i] += (bi * bi - levelStates[i]) * rmsCoeff
          r = sqrt(max(0f, levelStates[i]))
        } else {
          val absVal = abs(bi)
          val c = if (absVal > levelStates[i]) levelAtt else levelRel
          levelStates[i] += (absVal - levelStates[i]) * c
          r = levelStates[i]
        }

        if (learning && r > peakLevels[i]) {
          peakLevels[i] = r
        }

        var targetG = 1f - (tLinear[i] / (r + 1e-9f))
        targetG = max(floorV, min(1f, targetG))

        val currentCoeff = if (targetG > currentGains[i]) {
          attCoeff
        } else {
          val tailDist = (currentGains[i] - floorV) / (1.01f - floorV)
          val tailStab = 1f + (1f - min(1f, tailDist)) * 8f
          1f - exp(-1f / (max(10f, params.releaseMs * tailStab) * sampleRate / 1000f))
        }
        currentGains[i] += (targetG - currentGains[i]) * currentCoeff

        // 4. Phase cancellation reconstruction
        val noiseComponent = bi * (1f - currentGains[i])
        outSum += bi - noiseComponent
      }

      output[s] = if (!params.enabled) {
        input[s]
      } else if (listenNoise) {
        s0 - outSum
      } else {
        outSum
      }
    }

    var learned: FloatArray? = null
    if (learning) {
      learnElapsedSec += numSamples.toDouble() / sampleRate.toDouble()
      if (learnElapsedSec >= learnDurationSec) {
        learning = false
        learned = FloatArray(10) { i ->
          val db = 20f * log10(peakLevels[i] + 1e-6f) + 3.0f
          db.coerceIn(-100f, 0f)
        }
        pendingLearnedThresholds = learned
      }
    }

    val progress = if (learning) {
      (learnElapsedSec.toFloat() / learnDurationSec).coerceIn(0f, 1f)
    } else {
      0f
    }

    return CyberDenoiserSnapshot(
      bandLevels = levelStates.copyOf(),
      bandReductions = currentGains.copyOf(),
      isLearning = learning,
      learnProgress = progress,
      learnedThresholdsDb = learned,
    )
  }
}
