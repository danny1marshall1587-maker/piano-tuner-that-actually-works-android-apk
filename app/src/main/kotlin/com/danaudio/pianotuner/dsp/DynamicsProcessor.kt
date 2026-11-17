package com.danaudio.pianotuner.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

data class DynamicsParams(
  val compressorEnabled: Boolean = true,
  val compThresholdDb: Float = -18f,
  val compRatio: Float = 6.0f, // 1..12
  val compAttackMs: Float = 0.2f, // ultra-fast hammer transient control
  val compReleaseMs: Float = 80f,
  val agcEnabled: Boolean = true,
  val agcTargetDb: Float = -8f,
  val agcMaxBoostDb: Float = 32f, // up to +32 dB for faint/short high treble notes
  val agcAttackMs: Float = 2.0f, // fast enough to lift short C7-C8 notes within a few ms
  val agcReleaseMs: Float = 180f,
  val noiseGateDb: Float = -68f, // prevents boosting room silence
)

data class DynamicsSnapshot(
  val inputPeakDb: Float,
  val outputPeakDb: Float,
  val agcGainDb: Float,
  val compReductionDb: Float,
  val gateOpen: Boolean,
)

/**
 * High-speed transient-ready Compressor + Fast Digital Auto Gain (AGC).
 *
 * Designed specifically for acoustic piano tuning:
 * - Fast compressor (0.2ms attack) prevents harsh hammer clicks from saturating pitch buffers.
 * - Ultra-fast upward AGC (2ms attack) boosts quiet or rapidly decaying high-treble notes
 *   (e.g. C7–C8 lasting only 30–100 ms) into full-resolution pitch detection range, while
 *   a smart noise gate prevents pumping room hiss between strikes.
 */
class DynamicsProcessor(private val sampleRate: Float = 48_000f) {

  private var compEnv = 1e-6f
  private var agcEnv = 1e-6f
  private var agcGainLinear = 1f
  private var gateHoldSamples = 0

  fun reset() {
    compEnv = 1e-6f
    agcEnv = 1e-6f
    agcGainLinear = 1f
    gateHoldSamples = 0
  }

  fun processBlock(
    input: FloatArray,
    output: FloatArray,
    numSamples: Int,
    params: DynamicsParams,
  ): DynamicsSnapshot {
    val compAtt = 1f - exp(-1f / (max(0.05f, params.compAttackMs) * sampleRate / 1000f))
    val compRel = 1f - exp(-1f / (max(5f, params.compReleaseMs) * sampleRate / 1000f))

    val agcEnvAtt = 1f - exp(-1f / (1.5f * sampleRate / 1000f))
    val agcEnvRel = 1f - exp(-1f / (45f * sampleRate / 1000f))

    val agcGainAtt = 1f - exp(-1f / (max(0.5f, params.agcAttackMs) * sampleRate / 1000f))
    val agcGainRel = 1f - exp(-1f / (max(15f, params.agcReleaseMs) * sampleRate / 1000f))

    val gateThreshLin = 10f.pow(params.noiseGateDb / 20f)
    val agcTargetLin = 10f.pow(params.agcTargetDb / 20f)
    val maxBoostLin = 10f.pow(params.agcMaxBoostDb / 20f)
    val holdMaxSamples = (0.140f * sampleRate).toInt()

    var inPeak = 1e-7f
    var outPeak = 1e-7f
    var maxCompGrDb = 0f
    var gateOpenAny = false

    for (i in 0 until numSamples) {
      val x = input[i]
      val absX = abs(x)
      if (absX > inPeak) inPeak = absX

      // 1. Fast Digital AGC with gate hold (runs before or in tandem so faint C8 is lifted fast)
      val envCoeff = if (absX > agcEnv) agcEnvAtt else agcEnvRel
      agcEnv += (absX - agcEnv) * envCoeff

      if (agcEnv >= gateThreshLin) {
        gateHoldSamples = holdMaxSamples
        gateOpenAny = true
      } else if (gateHoldSamples > 0) {
        gateHoldSamples--
        gateOpenAny = true
      }

      val desiredAgcGain = if (!params.agcEnabled) {
        1f
      } else if (gateHoldSamples > 0 && agcEnv > gateThreshLin * 0.5f) {
        (agcTargetLin / (agcEnv + 1e-6f)).coerceIn(0.25f, maxBoostLin)
      } else {
        // Gently relax toward unity when silent so room floor isn't pumped
        1f
      }

      val gCoeff = if (desiredAgcGain > agcGainLinear) agcGainAtt else agcGainRel
      agcGainLinear += (desiredAgcGain - agcGainLinear) * gCoeff

      var y = x * agcGainLinear

      // 2. Fast-attack soft-knee Compressor to control hammer transient spikes
      if (params.compressorEnabled) {
        val absY = abs(y)
        val cCoeff = if (absY > compEnv) compAtt else compRel
        compEnv += (absY - compEnv) * cCoeff

        val envDb = 20f * log10(compEnv + 1e-7f)
        val overDb = envDb - params.compThresholdDb
        if (overDb > 0f) {
          val ratio = max(1f, params.compRatio)
          val grDb = overDb * (1f - 1f / ratio)
          if (grDb > maxCompGrDb) maxCompGrDb = grDb
          val compGain = 10f.pow(-grDb / 20f)
          y *= compGain
        }
      }

      // Soft safety limiter at 0.98
      if (y > 0.98f) y = 0.98f else if (y < -0.98f) y = -0.98f

      val absOut = abs(y)
      if (absOut > outPeak) outPeak = absOut
      output[i] = y
    }

    return DynamicsSnapshot(
      inputPeakDb = 20f * log10(inPeak + 1e-7f),
      outputPeakDb = 20f * log10(outPeak + 1e-7f),
      agcGainDb = 20f * log10(agcGainLinear + 1e-7f),
      compReductionDb = maxCompGrDb,
      gateOpen = gateOpenAny,
    )
  }
}
