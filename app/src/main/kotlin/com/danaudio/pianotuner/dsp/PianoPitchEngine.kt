package com.danaudio.pianotuner.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

enum class PartialMode(val label: String, val partialNumber: Int?) {
  AUTO("Auto (Smart)", null),
  P1("1st (Fund)", 1),
  P2("2nd (Oct)", 2),
  P3("3rd (12th)", 3),
  P4("4th (2nd Oct)", 4),
  P5("5th", 5),
  P6("6th", 6),
}

enum class UnisonHealth(val label: String) {
  IDLE("Listening"),
  SINGLE_STRING("Single String (Bass)"),
  CLEAN("Unison Clean"),
  SLIGHT_BEAT("Slight Beat"),
  STRINGS_OUT("Strings Out!"),
}

data class UnisonAnalysis(
  val health: UnisonHealth = UnisonHealth.IDLE,
  val beatRateHz: Double = 0.0,
  val modulationDepth: Double = 0.0, // 0..1
  val spreadCents: Double = 0.0,
  val splitPeakCount: Int = 1,
  val detailMessage: String = "Play a note to analyze string unison",
)

data class PitchAnalysisResult(
  val hasSignal: Boolean = false,
  val isTransientHold: Boolean = false,
  val onsetDetected: Boolean = false,
  val detectedKeyIndex: Int = PianoNotes.A4_KEY_INDEX,
  val bestAutoKeyIndex: Int = PianoNotes.A4_KEY_INDEX,
  val detectedHz: Double = 0.0,
  val targetHz: Double = 440.0,
  val centsError: Double = 0.0, // + sharp, - flat
  val smoothedCentsError: Double = 0.0,
  val confidence: Double = 0.0, // 0..1
  val measuredInharmonicityB: Double? = null,
  val partialCentsErrors: DoubleArray = DoubleArray(6), // partials 1..6 relative to target
  val partialStrengths: FloatArray = FloatArray(6), // 0..1 normalized partial bars
  val unison: UnisonAnalysis = UnisonAnalysis(),
  val spectrumBars: FloatArray = FloatArray(72), // 0..1 log-frequency display
)

/**
 * Piano-grade pitch, partial, inharmonicity (B), short high-note transient, and unison-beat analyzer.
 */
class PianoPitchEngine(private val sampleRate: Float = 48_000f) {

  companion object {
    const val LONG_FFT_SIZE = 16384
    const val BASS_FFT_SIZE = 32768 // 2x zero-padding for sub-cent A0..C3 bass resolution
    const val FAST_BUF_LEN = 2048
    const val FAST_FFT_SIZE = 8192 // 4x zero-padding for crisp high-treble resolution
  }

  private val ringBuffer = FloatArray(LONG_FFT_SIZE)
  private var ringWritePos = 0
  private var totalSamplesWritten = 0L

  private val linearLong = FloatArray(LONG_FFT_SIZE)
  private val linearFast = FloatArray(FAST_BUF_LEN)

  private val longWindow = FftMath.blackmanHarrisWindow(LONG_FFT_SIZE)
  private val fastWindow = FftMath.blackmanHarrisWindow(FAST_BUF_LEN)

  private val longReal = DoubleArray(LONG_FFT_SIZE)
  private val longImag = DoubleArray(LONG_FFT_SIZE)
  private val longMag = DoubleArray(LONG_FFT_SIZE / 2 + 1)

  private val bassReal = DoubleArray(BASS_FFT_SIZE)
  private val bassImag = DoubleArray(BASS_FFT_SIZE)
  private val bassMag = DoubleArray(BASS_FFT_SIZE / 2 + 1)

  private val fastReal = DoubleArray(FAST_FFT_SIZE)
  private val fastImag = DoubleArray(FAST_FFT_SIZE)
  private val fastMag = DoubleArray(FAST_FFT_SIZE / 2 + 1)

  // Stabilization state
  private var smoothedCents = 0.0
  private var hasSmoothedInit = false
  private val recentCentsWindow = ArrayDeque<Double>(12)

  // Short high-note transient capture / hold
  private var prevShortRms = 0f
  private var transientHoldFrames = 0
  private var heldResult: PitchAnalysisResult? = null

  fun reset() {
    ringBuffer.fill(0f)
    ringWritePos = 0
    totalSamplesWritten = 0L
    smoothedCents = 0.0
    hasSmoothedInit = false
    recentCentsWindow.clear()
    prevShortRms = 0f
    transientHoldFrames = 0
    heldResult = null
  }

  fun pushSamples(samples: FloatArray, count: Int) {
    for (i in 0 until count) {
      ringBuffer[ringWritePos] = samples[i]
      ringWritePos = (ringWritePos + 1) % LONG_FFT_SIZE
    }
    totalSamplesWritten += count
  }

  /**
   * Analyzes the current audio ring buffer against the 88-key stretch curve.
   *
   * @param stabilization 0f = raw/ultra-fast transient response, 1f = heavily stabilized needle.
   */
  fun analyze(
    stretchCurve: StretchCurveResult,
    autoFollow: Boolean,
    manualKeyIndex: Int,
    partialMode: PartialMode,
    stabilization: Float,
    gateOpen: Boolean,
  ): PitchAnalysisResult {
    if (!autoFollow && heldResult != null && heldResult!!.detectedKeyIndex != manualKeyIndex.coerceIn(0, PianoNotes.TOTAL_KEYS - 1)) {
      heldResult = null
      transientHoldFrames = 0
    }

    if (totalSamplesWritten < FAST_BUF_LEN) {
      return PitchAnalysisResult(
        detectedKeyIndex = manualKeyIndex,
        targetHz = stretchCurve.targetFrequenciesHz[manualKeyIndex],
      )
    }

    // Copy latest LONG_FFT_SIZE and FAST_BUF_LEN chronologically
    for (i in 0 until LONG_FFT_SIZE) {
      val idx = (ringWritePos + i) % LONG_FFT_SIZE
      linearLong[i] = ringBuffer[idx]
    }
    val fastStart = LONG_FFT_SIZE - FAST_BUF_LEN
    for (i in 0 until FAST_BUF_LEN) {
      linearFast[i] = linearLong[fastStart + i]
    }

    // Measure short-window RMS for silence & onset detection
    var sqSum = 0.0
    for (i in 0 until FAST_BUF_LEN) {
      val v = linearFast[i].toDouble()
      sqSum += v * v
    }
    val fastRms = sqrt(sqSum / FAST_BUF_LEN).toFloat()
    val onsetDetected = fastRms > max(0.012f, prevShortRms * 2.2f)
    prevShortRms = prevShortRms * 0.65f + fastRms * 0.35f

    if (!gateOpen || fastRms < 0.0025f) {
      if (transientHoldFrames > 0 && heldResult != null) {
        transientHoldFrames--
        return heldResult!!.copy(isTransientHold = true)
      }
      hasSmoothedInit = false
      recentCentsWindow.clear()
      val activeKey = manualKeyIndex.coerceIn(0, PianoNotes.TOTAL_KEYS - 1)
      return PitchAnalysisResult(
        hasSignal = false,
        detectedKeyIndex = activeKey,
        targetHz = stretchCurve.targetFrequenciesHz[activeKey],
        unison = UnisonAnalysis(health = UnisonHealth.IDLE),
      )
    }

    // Compute Long FFT (16384 -> 2.93 Hz/bin) and Fast FFT (2048 padded to 8192 -> 5.86 Hz/bin)
    FftMath.computeMagnitudeSpectrum(linearLong, LONG_FFT_SIZE, LONG_FFT_SIZE, longWindow, longReal, longImag, longMag)
    FftMath.computeMagnitudeSpectrum(linearFast, FAST_BUF_LEN, FAST_FFT_SIZE, fastWindow, fastReal, fastImag, fastMag)

    val spectrumBars = buildSpectrumBars(longMag, sampleRate.toDouble(), LONG_FFT_SIZE)

    val targets = stretchCurve.targetFrequenciesHz
    val bCurve = stretchCurve.inharmonicityCurveB

    // Always identify the loudest matching key in the spectrum (for auto-follow & guided script guard)
    val bestAutoKey = findBestKeyAuto(targets, bCurve)
    val selectedKey = if (autoFollow) {
      bestAutoKey
    } else {
      manualKeyIndex.coerceIn(0, PianoNotes.TOTAL_KEYS - 1)
    }

    val targetF1 = targets[selectedKey]
    val keyB = bCurve[selectedKey]

    // Use fast FFT for very high treble (>= 1050 Hz), 32k zero-padded FFT for bass & mid (< 600 Hz), else 16k FFT
    val useFastFft = targetF1 >= 1050.0 || (targetF1 >= 520.0 && onsetDetected)
    val useBassFft = !useFastFft && targetF1 < 600.0
    if (useBassFft) {
      FftMath.computeMagnitudeSpectrum(linearLong, LONG_FFT_SIZE, BASS_FFT_SIZE, longWindow, bassReal, bassImag, bassMag)
    }
    val activeMag = when {
      useFastFft -> fastMag
      useBassFft -> bassMag
      else -> longMag
    }
    val activeFftSize = when {
      useFastFft -> FAST_FFT_SIZE
      useBassFft -> BASS_FFT_SIZE
      else -> LONG_FFT_SIZE
    }
    val binHz = sampleRate.toDouble() / activeFftSize.toDouble()

    // Search window around target (wider in manual mode to catch badly out-of-tune strings)
    val searchCents = if (autoFollow) 75.0 else 160.0

    // Measure partials 1..6 around targetF1
    val partialFreqs = DoubleArray(6)
    val partialSnr = DoubleArray(6)
    val partialCents = DoubleArray(6)
    val partialBars = FloatArray(6)

    val localNoiseFloor = estimateMedianNoiseFloor(activeMag)

    var maxPartialAmp = 1e-9
    for (p in 1..6) {
      val expectedFp = StretchTuningEngine.partialFreq(targetF1, p, keyB)
      if (expectedFp > sampleRate * 0.44 || expectedFp < 24.0) continue

      val fMin = PianoNotes.centsToHz(expectedFp, -searchCents)
      val fMax = PianoNotes.centsToHz(expectedFp, searchCents)
      val bMin = (fMin / binHz).toInt().coerceIn(2, activeMag.size - 3)
      val bMax = (fMax / binHz).toInt().coerceIn(bMin, activeMag.size - 3)

      var bestBin = bMin
      var bestVal = 0.0
      for (b in bMin..bMax) {
        if (activeMag[b] > bestVal) {
          bestVal = activeMag[b]
          bestBin = b
        }
      }

      if (bestVal > localNoiseFloor * 2.4) {
        val exactBin = FftMath.interpolatePeakBin(activeMag, bestBin)
        val exactHz = exactBin * binHz
        partialFreqs[p - 1] = exactHz
        partialSnr[p - 1] = bestVal / (localNoiseFloor + 1e-9)
        partialCents[p - 1] = PianoNotes.hzToCents(exactHz, expectedFp)
        if (bestVal > maxPartialAmp) maxPartialAmp = bestVal
      }
    }

    for (i in 0 until 6) {
      partialBars[i] = if (partialFreqs[i] > 0.0) {
        (partialSnr[i] / (partialSnr.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0)).toFloat().coerceIn(0f, 1f)
      } else 0f
    }

    // Compute detected fundamental f1 (or selected partial) & cents error
    val (detectedF1, centsErr, confidence) = computeWeightedPitch(
      selectedKey = selectedKey,
      targetF1 = targetF1,
      keyB = keyB,
      partialMode = partialMode,
      partialFreqs = partialFreqs,
      partialSnr = partialSnr,
      partialCents = partialCents,
    )

    if (confidence < 0.15 || detectedF1 <= 0.0) {
      if (transientHoldFrames > 0 && heldResult != null) {
        transientHoldFrames--
        return heldResult!!.copy(isTransientHold = true)
      }
      return PitchAnalysisResult(
        hasSignal = false,
        detectedKeyIndex = selectedKey,
        targetHz = targetF1,
        partialStrengths = partialBars,
        spectrumBars = spectrumBars,
        unison = UnisonAnalysis(health = UnisonHealth.IDLE),
      )
    }

    // Stabilization filter (wide range 0..1):
    // 0.0 -> instantaneous (alpha = 0.90, median window 1)
    // 0.5 -> balanced (alpha = 0.16, median window 8)
    // 1.0 -> ultra-steady damping (alpha = 0.03, median window 15)
    val stab = stabilization.coerceIn(0f, 1f).toDouble()
    val medianSize = (1 + (stab * 14.0).roundToInt()).coerceIn(1, 15)
    if (recentCentsWindow.size >= medianSize) {
      while (recentCentsWindow.size >= medianSize) recentCentsWindow.removeFirst()
    }
    recentCentsWindow.addLast(centsErr)
    val sortedCents = recentCentsWindow.sorted()
    val medianCents = sortedCents[sortedCents.size / 2]

    val alpha = (0.90 * exp(-3.4 * stab)).coerceIn(0.03, 0.92)
    smoothedCents = if (!hasSmoothedInit || abs(medianCents - smoothedCents) > 35.0) {
      hasSmoothedInit = true
      medianCents
    } else {
      smoothedCents + (medianCents - smoothedCents) * alpha
    }

    // Estimate Inharmonicity B when >= 3 partials are clean
    val measuredB = estimateInharmonicityB(partialFreqs, partialSnr, selectedKey)

    // Unison / String Check around the strongest partial in longMag (highest frequency resolution!)
    val unison = analyzeUnisonStrings(
      selectedKey = selectedKey,
      detectedF1 = detectedF1,
      keyB = keyB,
      partialSnr = partialSnr,
    )

    val result = PitchAnalysisResult(
      hasSignal = true,
      isTransientHold = false,
      onsetDetected = onsetDetected,
      detectedKeyIndex = selectedKey,
      bestAutoKeyIndex = bestAutoKey,
      detectedHz = detectedF1,
      targetHz = targetF1,
      centsError = centsErr,
      smoothedCentsError = smoothedCents,
      confidence = confidence,
      measuredInharmonicityB = measuredB,
      partialCentsErrors = partialCents,
      partialStrengths = partialBars,
      unison = unison,
      spectrumBars = spectrumBars,
    )

    // High treble note transient hold: keep reading visible for ~1.4s after a crisp C6-C8 hit!
    if (targetF1 >= 1000.0 && confidence >= 0.45) {
      heldResult = result
      transientHoldFrames = 28 // ~1.2-1.5s at UI frame rate
    } else if (confidence >= 0.45) {
      heldResult = result
      transientHoldFrames = 6
    }

    return result
  }

  private fun findBestKeyAuto(targets: DoubleArray, bCurve: DoubleArray): Int {
    val longBinHz = sampleRate.toDouble() / LONG_FFT_SIZE.toDouble()
    val fastBinHz = sampleRate.toDouble() / FAST_FFT_SIZE.toDouble()

    var bestKey = PianoNotes.A4_KEY_INDEX
    var bestScore = -1.0

    for (k in 0 until PianoNotes.TOTAL_KEYS) {
      val f1 = targets[k]
      val b = bCurve[k]
      val useFast = f1 >= 1050.0
      val mag = if (useFast) fastMag else longMag
      val bHz = if (useFast) fastBinHz else longBinHz

      var score = 0.0
      val weights = partialWeightsForKey(k)
      for (p in 1..6) {
        val fp = StretchTuningEngine.partialFreq(f1, p, b)
        if (fp > sampleRate * 0.44) break
        // Look in +/- 45 cents around expected partial
        val bMin = (PianoNotes.centsToHz(fp, -45.0) / bHz).toInt().coerceIn(1, mag.size - 2)
        val bMax = (PianoNotes.centsToHz(fp, 45.0) / bHz).toInt().coerceIn(bMin, mag.size - 2)
        var peak = 0.0
        for (bin in bMin..bMax) {
          if (mag[bin] > peak) peak = mag[bin]
        }
        score += peak * weights[p - 1]
      }

      // Octave-error guard: check if sub-octave f1/2 has strong odd partials (1, 3) within +/- 45 cents
      if (k >= 12) {
        val subF1 = targets[k - 12]
        val subOdd3Hz = StretchTuningEngine.partialFreq(subF1, 3, bCurve[k - 12])
        val subPeak1 = windowPeak(mag, bHz, subF1, 45.0)
        val subPeak3 = windowPeak(mag, bHz, subOdd3Hz, 45.0)
        val subEnergy = subPeak1 + subPeak3 * 0.8
        val f1Peak = windowPeak(mag, bHz, f1, 45.0)
        if (subEnergy > f1Peak * 1.35 && k < 55) {
          score *= 0.80
        }
      }

      if (score > bestScore) {
        bestScore = score
        bestKey = k
      }
    }
    return bestKey
  }

  private fun windowPeak(mag: DoubleArray, binHz: Double, centerHz: Double, centsHalfWidth: Double): Double {
    if (centerHz <= 20.0 || centerHz > sampleRate * 0.44) return 0.0
    val bMin = (PianoNotes.centsToHz(centerHz, -centsHalfWidth) / binHz).toInt().coerceIn(1, mag.size - 2)
    val bMax = (PianoNotes.centsToHz(centerHz, centsHalfWidth) / binHz).toInt().coerceIn(bMin, mag.size - 2)
    var peak = 0.0
    for (b in bMin..bMax) {
      if (mag[b] > peak) peak = mag[b]
    }
    return peak
  }

  private fun partialWeightsForKey(keyIndex: Int): DoubleArray {
    return when {
      keyIndex < 15 -> doubleArrayOf(0.25, 1.0, 1.1, 1.0, 0.85, 0.65) // Sub-bass: weak fundamental
      keyIndex < 32 -> doubleArrayOf(0.75, 1.0, 0.85, 0.65, 0.45, 0.3) // Bass/tenor
      keyIndex < 58 -> doubleArrayOf(1.0, 0.8, 0.5, 0.3, 0.15, 0.08) // Midrange
      else -> doubleArrayOf(1.0, 0.45, 0.15, 0.05, 0.0, 0.0) // Treble
    }
  }

  private fun computeWeightedPitch(
    selectedKey: Int,
    targetF1: Double,
    keyB: Double,
    partialMode: PartialMode,
    partialFreqs: DoubleArray,
    partialSnr: DoubleArray,
    partialCents: DoubleArray,
  ): Triple<Double, Double, Double> {
    val forcedP = partialMode.partialNumber
    if (forcedP != null) {
      val idx = forcedP - 1
      val fP = partialFreqs[idx]
      if (fP <= 0.0) return Triple(0.0, 0.0, 0.0)
      val impliedF1 = if (forcedP == 1) fP else {
        fP / (forcedP.toDouble() * sqrt((1.0 + keyB * forcedP * forcedP) / (1.0 + keyB)))
      }
      val conf = ((partialSnr[idx] - 2.0) / 14.0).coerceIn(0.0, 1.0)
      return Triple(impliedF1, partialCents[idx], conf)
    }

    val regWeights = partialWeightsForKey(selectedKey)
    var wSum = 0.0
    var centsWeighted = 0.0
    var maxSnr = 0.0

    for (i in 0 until 6) {
      if (partialFreqs[i] > 0.0 && partialSnr[i] > 2.4) {
        val w = regWeights[i] * ln(partialSnr[i]).coerceAtLeast(0.1)
        wSum += w
        centsWeighted += partialCents[i] * w
        if (partialSnr[i] > maxSnr) maxSnr = partialSnr[i]
      }
    }
    if (wSum <= 0.0) return Triple(0.0, 0.0, 0.0)

    val finalCents = centsWeighted / wSum
    val impliedF1 = PianoNotes.centsToHz(targetF1, finalCents)
    val conf = ((maxSnr - 2.4) / 18.0).coerceIn(0.0, 1.0)
    return Triple(impliedF1, finalCents, conf)
  }

  /**
   * Fits B from (f_n / n)^2 = f_1^2 + (f_1^2 * B) * n^2 using weighted linear regression.
   */
  private fun estimateInharmonicityB(
    partialFreqs: DoubleArray,
    partialSnr: DoubleArray,
    keyIndex: Int,
  ): Double? {
    val startP = if (keyIndex < 14) 2 else 1
    val valid = mutableListOf<Triple<Double, Double, Double>>() // x=n^2, y=(f_n/n)^2, weight
    for (p in startP..6) {
      val fp = partialFreqs[p - 1]
      val snr = partialSnr[p - 1]
      if (fp > 0.0 && snr > 4.0) {
        val n = p.toDouble()
        val x = n * n
        val y = (fp / n) * (fp / n)
        valid.add(Triple(x, y, ln(snr)))
      }
    }
    if (valid.size < 3) return null

    var sw = 0.0
    var swx = 0.0
    var swy = 0.0
    var swxx = 0.0
    var swxy = 0.0
    for ((x, y, w) in valid) {
      sw += w
      swx += w * x
      swy += w * y
      swxx += w * x * x
      swxy += w * x * y
    }
    val denom = sw * swxx - swx * swx
    if (abs(denom) < 1e-12) return null
    val slope = (sw * swxy - swx * swy) / denom
    val intercept = (swy - slope * swx) / sw
    if (intercept <= 0.0 || slope <= 0.0) return null
    val bEst = slope / intercept
    return bEst.takeIf { it in 0.000015..0.025 }
  }

  /**
   * Detects unison beating / out-of-tune strings within a single bichord or trichord note!
   */
  private fun analyzeUnisonStrings(
    selectedKey: Int,
    detectedF1: Double,
    keyB: Double,
    partialSnr: DoubleArray,
  ): UnisonAnalysis {
    val strCount = PianoNotes.keyInfo(selectedKey).typicalStringCount
    if (strCount <= 1) {
      return UnisonAnalysis(
        health = UnisonHealth.SINGLE_STRING,
        detailMessage = "Single-string bass note (no unison)",
      )
    }

    // Choose strongest available partial (prefer p=1..3 for cleanest beat observation)
    var bestP = 1
    var bestSnr = 0.0
    for (p in 1..4) {
      if (partialSnr[p - 1] > bestSnr) {
        bestSnr = partialSnr[p - 1]
        bestP = p
      }
    }
    if (bestSnr < 5.0) {
      return UnisonAnalysis(health = UnisonHealth.IDLE, detailMessage = "Hold note steady for unison check")
    }

    val fp = StretchTuningEngine.partialFreq(detectedF1, bestP, keyB)

    // 1. Quadrature complex demodulation around fp with 2-stage 24 Hz LPF before decimation
    //    (eliminates 2*fp aliasing into the 1-14 Hz beat band on single strings near 370-385 Hz)
    val settleSamples = 1800
    val decimation = 128
    val numPoints = (LONG_FFT_SIZE - settleSamples) / decimation
    val env = DoubleArray(numPoints)
    val omega = 2.0 * PI * fp / sampleRate.toDouble()
    val lpfAlpha = 1.0 - exp(-2.0 * PI * 24.0 / sampleRate.toDouble())
    var re1 = 0.0
    var im1 = 0.0
    var re2 = 0.0
    var im2 = 0.0
    var sumEnv = 0.0
    var ptIdx = 0

    for (n in 0 until LONG_FFT_SIZE) {
      val s = linearLong[n].toDouble()
      val ph = omega * n
      val rawRe = s * cos(ph)
      val rawIm = -s * sin(ph)
      re1 += (rawRe - re1) * lpfAlpha
      im1 += (rawIm - im1) * lpfAlpha
      re2 += (re1 - re2) * lpfAlpha
      im2 += (im1 - im2) * lpfAlpha

      if (n >= settleSamples && (n - settleSamples) % decimation == 0 && ptIdx < numPoints) {
        val mag = hypot(re2, im2)
        env[ptIdx++] = mag
        sumEnv += mag
      }
    }
    val actualPoints = ptIdx.coerceAtLeast(1)
    val meanEnv = sumEnv / actualPoints
    if (meanEnv <= 1e-7) {
      return UnisonAnalysis(health = UnisonHealth.IDLE)
    }

    // Remove linear trend (piano natural decay!) so natural decay isn't mistaken for beating
    var sx = 0.0
    var sy = 0.0
    var sxx = 0.0
    var sxy = 0.0
    for (i in 0 until actualPoints) {
      sx += i
      sy += env[i]
      sxx += i * i.toDouble()
      sxy += i * env[i]
    }
    val denom = actualPoints * sxx - sx * sx
    val slope = if (abs(denom) > 1e-9) (actualPoints * sxy - sx * sy) / denom else 0.0
    val intercept = (sy - slope * sx) / actualPoints

    val detrended = DoubleArray(actualPoints)
    var varSum = 0.0
    for (i in 0 until actualPoints) {
      val d = env[i] - (intercept + slope * i)
      detrended[i] = d
      varSum += d * d
    }
    val modDepth = (sqrt(varSum / actualPoints) / meanEnv).coerceIn(0.0, 1.0)

    // Find dominant beat frequency between 0.8 Hz and 14 Hz via DFT on detrended envelope
    val envRate = sampleRate.toDouble() / decimation
    var bestBeatHz = 0.0
    var bestBeatAmp = 0.0
    var fHz = 0.8
    while (fHz <= 14.0) {
      var r = 0.0
      var im = 0.0
      for (i in 0 until actualPoints) {
        val ang = 2.0 * PI * fHz * i / envRate
        r += detrended[i] * cos(ang)
        im -= detrended[i] * sin(ang)
      }
      val amp = hypot(r, im) / actualPoints
      if (amp > bestBeatAmp) {
        bestBeatAmp = amp
        bestBeatHz = fHz
      }
      fHz += 0.35
    }

    // 2. Check high-res longMag around fp (+/- 35 cents) for split spectral peaks
    val binHz = sampleRate.toDouble() / LONG_FFT_SIZE.toDouble()
    val bMin = (PianoNotes.centsToHz(fp, -35.0) / binHz).toInt().coerceIn(2, longMag.size - 3)
    val bMax = (PianoNotes.centsToHz(fp, 35.0) / binHz).toInt().coerceIn(bMin, longMag.size - 3)
    var mainPeak = 0.0
    for (b in bMin..bMax) if (longMag[b] > mainPeak) mainPeak = longMag[b]

    var splitPeaks = 0
    for (b in (bMin + 1) until bMax) {
      if (longMag[b] > longMag[b - 1] && longMag[b] >= longMag[b + 1] && longMag[b] > mainPeak * 0.24) {
        splitPeaks++
      }
    }

    val impliedSpreadCents = if (fp > 0.0 && bestBeatHz > 0.0) {
      abs(PianoNotes.hzToCents(fp + bestBeatHz, fp))
    } else 0.0

    val health = when {
      splitPeaks >= 2 || (modDepth > 0.22 && bestBeatAmp > meanEnv * 0.14) -> UnisonHealth.STRINGS_OUT
      modDepth > 0.11 && bestBeatAmp > meanEnv * 0.075 -> UnisonHealth.SLIGHT_BEAT
      else -> UnisonHealth.CLEAN
    }

    val msg = when (health) {
      UnisonHealth.STRINGS_OUT -> String.format(
        "%d-string unison out! %.1f Hz beat (~%.1f¢ spread) — mute & align strings",
        strCount, bestBeatHz, impliedSpreadCents,
      )
      UnisonHealth.SLIGHT_BEAT -> String.format(
        "Mild unison beat (%.1f Hz, ~%.1f¢) — fine touch needed",
        bestBeatHz, impliedSpreadCents,
      )
      UnisonHealth.CLEAN -> String.format(
        "%d-string unison clean & locked",
        strCount,
      )
      else -> ""
    }

    return UnisonAnalysis(
      health = health,
      beatRateHz = bestBeatHz,
      modulationDepth = modDepth,
      spreadCents = impliedSpreadCents,
      splitPeakCount = splitPeaks.coerceAtLeast(1),
      detailMessage = msg,
    )
  }

  private fun estimateMedianNoiseFloor(mag: DoubleArray): Double {
    // Sample 64 bins evenly across 100..6000 Hz for fast noise floor estimation
    val samples = DoubleArray(64)
    val step = max(1, (mag.size / 4) / 64)
    for (i in 0 until 64) {
      samples[i] = mag[(4 + i * step).coerceAtMost(mag.size - 1)]
    }
    samples.sort()
    return samples[24].coerceAtLeast(1e-7)
  }

  private fun buildSpectrumBars(mag: DoubleArray, sr: Double, fftSize: Int): FloatArray {
    val bars = FloatArray(72)
    val binHz = sr / fftSize
    val minF = 26.0
    val maxF = 6500.0
    var maxBar = 1e-6
    for (i in 0 until 72) {
      val f0 = minF * (maxF / minF).pow(i / 72.0)
      val f1 = minF * (maxF / minF).pow((i + 1) / 72.0)
      val b0 = (f0 / binHz).toInt().coerceIn(1, mag.size - 1)
      val b1 = (f1 / binHz).toInt().coerceIn(b0, mag.size - 1)
      var peak = 0.0
      for (b in b0..b1) if (mag[b] > peak) peak = mag[b]
      bars[i] = peak.toFloat()
      if (peak > maxBar) maxBar = peak
    }
    val logScale = ln(1.0 + maxBar * 20.0).coerceAtLeast(1e-4)
    for (i in 0 until 72) {
      bars[i] = (ln(1.0 + bars[i] * 20.0) / logScale).toFloat().coerceIn(0f, 1f)
    }
    return bars
  }
}
