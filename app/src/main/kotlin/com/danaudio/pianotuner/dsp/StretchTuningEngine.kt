package com.danaudio.pianotuner.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

enum class PianoScaleType(val label: String, val bassB: Double, val midB: Double, val trebleB: Double) {
  CONCERT_GRAND("Concert Grand (9 ft)", 0.00028, 0.00012, 0.0045),
  BABY_GRAND("Baby Grand (5–6 ft)", 0.00055, 0.00022, 0.0068),
  UPRIGHT("Studio Upright", 0.00080, 0.00032, 0.0085),
  SPINET("Console / Spinet", 0.00130, 0.00048, 0.0110),
}

enum class TemperamentType(val label: String, val offsetsCentsFromC: DoubleArray) {
  EQUAL(
    "Equal Temperament",
    doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
  ),
  WERCKMEISTER_III(
    "Werckmeister III",
    // C, C#, D, D#, E, F, F#, G, G#, A, A#, B (relative to C=0, then normalized so A=0 before stretch)
    doubleArrayOf(0.0, -9.8, -7.8, -5.9, -9.8, -2.0, -9.8, -3.9, -7.8, -11.7, -3.9, -7.8),
  ),
  KIRNBERGER_III(
    "Kirnberger III",
    doubleArrayOf(0.0, -9.8, -6.8, -5.9, -13.7, -2.0, -9.8, -3.4, -7.8, -10.3, -3.9, -11.7),
  ),
  VALLOTTI(
    "Vallotti",
    doubleArrayOf(0.0, -5.9, -3.9, -3.9, -7.8, +2.0, -7.8, -2.0, -5.9, -5.9, -2.0, -9.8),
  ),
  YOUNG(
    "Thomas Young (1799)",
    doubleArrayOf(0.0, -5.9, -4.1, -3.9, -8.2, +2.0, -7.8, -2.1, -5.9, -6.2, -2.0, -10.0),
  );

  fun centsOffsetForKey(keyIndex: Int): Double {
    val midi = PianoNotes.A0_MIDI + keyIndex
    val pc = midi % 12 // 0=C .. 9=A .. 11=B
    // Normalize so A (pc=9) has 0.0 offset from A4 reference
    return offsetsCentsFromC[pc] - offsetsCentsFromC[9]
  }
}

enum class SweepStepMode(val label: String, val semitoneStep: Int) {
  EVERY_THIRD("Every 3rd (m3)", 3),
  EVERY_FOURTH("Every 4th (M3)", 4),
  EVERY_TRITONE("Every 6th (TT)", 6),
  OCTAVES("Octaves (12)", 12),
  CHROMATIC("Every Key (1)", 1),
}

data class IntervalBeatInfo(
  val name: String,
  val partnerKeyIndex: Int,
  val partnerNoteName: String,
  val beatRateHz: Double,
  val directionLabel: String,
)

data class StretchCurveResult(
  val targetFrequenciesHz: DoubleArray,
  val stretchOffsetsCents: DoubleArray, // relative to unstretched temperament
  val overpullOffsetsCents: DoubleArray, // extra pitch-raise cents when overpull enabled
  val inharmonicityCurveB: DoubleArray,
)

object StretchTuningEngine {

  /**
   * Default smooth piano-register inharmonicity curve B(keyIndex) for a given piano scale type.
   */
  fun defaultInharmonicityB(keyIndex: Int, scaleType: PianoScaleType): Double {
    val k = keyIndex.coerceIn(0, PianoNotes.TOTAL_KEYS - 1).toDouble()
    // Bass rise below key 28 (transition from wound bass to plain steel), smooth exponential rise in treble
    val bassComponent = scaleType.bassB * exp(-k / 9.5)
    val midComponent = scaleType.midB
    val trebleComponent = scaleType.trebleB * exp((k - 87.0) / 11.5)
    return (bassComponent + midComponent + trebleComponent).coerceIn(0.00002, 0.025)
  }

  /**
   * Interpolates full 88-key inharmonicity B(k) from sparse measured recordings (e.g. every 3rd note)
   * blended smoothly with the piano's physical scale prior so unmeasured ends behave naturally.
   */
  fun interpolateInharmonicityCurve(
    measuredB: Map<Int, Double>,
    scaleType: PianoScaleType,
  ): DoubleArray {
    val curve = DoubleArray(PianoNotes.TOTAL_KEYS) { k -> defaultInharmonicityB(k, scaleType) }
    if (measuredB.isEmpty()) return curve

    val sorted = measuredB.entries
      .filter { it.key in 0 until PianoNotes.TOTAL_KEYS && it.value > 0.0 && it.value < 0.05 }
      .sortedBy { it.key }
    if (sorted.isEmpty()) return curve

    // Compute ratio adjustments at measured anchor points relative to default model,
    // then interpolate log-ratio smoothly across all 88 keys!
    val anchors = sorted.map { (k, bMeas) ->
      val bPrior = defaultInharmonicityB(k, scaleType).coerceAtLeast(1e-6)
      k to ln((bMeas / bPrior).coerceIn(0.25, 4.0))
    }

    for (k in 0 until PianoNotes.TOTAL_KEYS) {
      val logScale = when {
        k <= anchors.first().first -> {
          val dist = (anchors.first().first - k).toDouble()
          anchors.first().second * exp(-dist / 10.0)
        }
        k >= anchors.last().first -> {
          val dist = (k - anchors.last().first).toDouble()
          anchors.last().second * exp(-dist / 10.0)
        }
        else -> {
          var i = 0
          while (i < anchors.size - 1 && anchors[i + 1].first < k) i++
          val (k0, v0) = anchors[i]
          val (k1, v1) = anchors[i + 1]
          if (k1 == k0) v0 else {
            val t = (k - k0).toDouble() / (k1 - k0).toDouble()
            // Smoothstep interpolation
            val s = t * t * (3.0 - 2.0 * t)
            v0 + (v1 - v0) * s
          }
        }
      }
      curve[k] = (curve[k] * exp(logScale)).coerceIn(0.00002, 0.03)
    }
    return curve
  }

  /**
   * Computes partial frequency relative to the actual fundamental f_1:
   * f_n = n * f_1 * sqrt((1 + B * n^2) / (1 + B)) so that f_1 (n=1) == f_1 identically.
   */
  fun partialFreq(f1: Double, partialNumber: Int, bCoeff: Double): Double {
    if (partialNumber == 1) return f1
    val n = partialNumber.toDouble()
    return n * f1 * sqrt((1.0 + bCoeff * n * n) / (1.0 + bCoeff))
  }

  /**
   * Computes the full 88-key Railsback stretch curve + overpull targets.
   *
   * Uses iterative octave/twelfth partial matching:
   * - Above A4: blends 2:1 octave match and 4:2 octave match
   * - Below A4: blends 4:2 octave match and 6:3 / 3:1 twelfth match for realistic bass stretch
   * Anchored at A4 = 0 stretch offset, scaled by [stretchFactor] (0 = unstretched, 1 = standard).
   */
  fun computeStretchCurve(
    a4Hz: Double,
    transposeCents: Double,
    scaleType: PianoScaleType,
    temperament: TemperamentType,
    stretchFactor: Double, // 0.0 .. 1.3
    bassBias: Double = 1.0, // 0.5 .. 1.5
    trebleBias: Double = 1.0, // 0.5 .. 1.5
    measuredB: Map<Int, Double> = emptyMap(),
    recordedCentsBefore: Map<Int, Double> = emptyMap(),
    overpullEnabled: Boolean = false,
    overpullPercent: Double = 0.25, // e.g. 25% of flat deviation
  ): StretchCurveResult {
    val bCurve = interpolateInharmonicityCurve(measuredB, scaleType)
    val stretchCents = DoubleArray(PianoNotes.TOTAL_KEYS)

    // First anchor C4..B4 temperament around A4=0, then propagate octaves upward & downward
    // by matching partials f_p(lower) == f_q(upper)
    val sf = stretchFactor.coerceIn(0.0, 1.5)

    // Compute per-octave stretch step Δ(k -> k+12) in cents using 4:2 vs 2:1 + treble twelfth/double-octave blend
    fun octaveStretchCents(lowerKey: Int): Double {
      val upperKey = lowerKey + 12
      if (lowerKey < 0 || upperKey >= PianoNotes.TOTAL_KEYS) return 0.0
      val bLow = bCurve[lowerKey]
      val bHigh = bCurve[upperKey]
      // 2:1 octave match: match f_2(low) == f_1(high)
      val c21 = 600.0 * ln((1.0 + 4.0 * bLow) / (1.0 + bLow)) / ln(2.0)
      // 4:2 octave match: match f_4(low) == f_2(high)
      val ratio42 = ((1.0 + 16.0 * bLow) / (1.0 + bLow)) * ((1.0 + bHigh) / (1.0 + 4.0 * bHigh))
      val c42 = 600.0 * ln(ratio42.coerceAtLeast(0.5)) / ln(2.0)
      // Perceptual 3:1 twelfth / 4:1 double-octave influence in upper treble where B_high rises steeply
      val cTreble = 600.0 * ln((1.0 + 6.0 * bHigh) / (1.0 + bHigh)) / ln(2.0) * 0.65
      val w21 = 1.0 / (1.0 + exp(-(lowerKey - 45.0) / 7.0))
      val wTreble = 1.0 / (1.0 + exp(-(lowerKey - 58.0) / 5.0))
      return (w21 * c21 + (1.0 - w21) * c42) + wTreble * cTreble
    }

    // Build cumulative stretch relative to A4 (key 48)
    // First fill keys 37..48 (A#3..A4) proportionally, then propagate octaves both ways
    val rawStretch = DoubleArray(PianoNotes.TOTAL_KEYS)
    // Estimate local slope near A4
    val octBelowA4 = octaveStretchCents(36)
    val octAboveA4 = octaveStretchCents(48)
    val localRateAroundA4 = (octBelowA4 + octAboveA4) / 24.0
    for (k in 37..59) {
      rawStretch[k] = (k - PianoNotes.A4_KEY_INDEX) * localRateAroundA4
    }
    // Propagate downward from 48..0
    for (k in 36 downTo 0) {
      rawStretch[k] = rawStretch[k + 12] - octaveStretchCents(k)
    }
    // Propagate upward from 60..87
    for (k in 60 until PianoNotes.TOTAL_KEYS) {
      rawStretch[k] = rawStretch[k - 12] + octaveStretchCents(k - 12)
    }

    // Apply stretchFactor & bass/treble bias, keeping A4 exactly 0 stretch
    val anchor = rawStretch[PianoNotes.A4_KEY_INDEX]
    for (k in 0 until PianoNotes.TOTAL_KEYS) {
      val s = rawStretch[k] - anchor
      val bias = if (k < PianoNotes.A4_KEY_INDEX) bassBias else trebleBias
      stretchCents[k] = s * sf * bias
    }

    // Compute overpull offsets if Pitch-Raise / Overpull mode is active
    val overpullCents = DoubleArray(PianoNotes.TOTAL_KEYS)
    if (overpullEnabled && recordedCentsBefore.isNotEmpty()) {
      val sortedDev = recordedCentsBefore.entries.sortedBy { it.key }
      for (k in 0 until PianoNotes.TOTAL_KEYS) {
        val dev = interpolateScalar(k, sortedDev)
        // If flat (dev < -3 cents), pull sharp by overpullPercent * (-dev), clamped sensibly (+35 cents max)
        overpullCents[k] = if (dev < -3.0) {
          ((-dev) * overpullPercent).coerceIn(0.0, 35.0)
        } else {
          0.0
        }
      }
    }

    val targetsHz = DoubleArray(PianoNotes.TOTAL_KEYS) { k ->
      val etHz = PianoNotes.equalTemperamentHz(k, a4Hz, transposeCents)
      val tempCents = temperament.centsOffsetForKey(k)
      val totalCents = tempCents + stretchCents[k] + overpullCents[k]
      PianoNotes.centsToHz(etHz, totalCents)
    }

    return StretchCurveResult(
      targetFrequenciesHz = targetsHz,
      stretchOffsetsCents = stretchCents,
      overpullOffsetsCents = overpullCents,
      inharmonicityCurveB = bCurve,
    )
  }

  private fun interpolateScalar(k: Int, sorted: List<Map.Entry<Int, Double>>): Double {
    if (sorted.isEmpty()) return 0.0
    if (k <= sorted.first().key) return sorted.first().value
    if (k >= sorted.last().key) return sorted.last().value
    var i = 0
    while (i < sorted.size - 1 && sorted[i + 1].key < k) i++
    val (k0, v0) = sorted[i]
    val (k1, v1) = sorted[i + 1]
    if (k1 == k0) return v0
    val t = (k - k0).toDouble() / (k1 - k0).toDouble()
    return v0 + (v1 - v0) * t
  }

  /**
   * Computes expected beat rates (Hz) for standard piano tuning check intervals from [keyIndex].
   */
  fun computeIntervalChecks(keyIndex: Int, curve: StretchCurveResult): List<IntervalBeatInfo> {
    val list = mutableListOf<IntervalBeatInfo>()
    val fLow = curve.targetFrequenciesHz[keyIndex]
    val bLow = curve.inharmonicityCurveB[keyIndex]

    fun addInterval(name: String, semitones: Int, lowPartial: Int, highPartial: Int) {
      val partner = keyIndex + semitones
      if (partner !in 0 until PianoNotes.TOTAL_KEYS) return
      val fHigh = curve.targetFrequenciesHz[partner]
      val bHigh = curve.inharmonicityCurveB[partner]
      val pLow = partialFreq(fLow, lowPartial, bLow)
      val pHigh = partialFreq(fHigh, highPartial, bHigh)
      val beat = abs(pHigh - pLow)
      val dir = if (pHigh >= pLow) "wide" else "narrow"
      list.add(
        IntervalBeatInfo(
          name = name,
          partnerKeyIndex = partner,
          partnerNoteName = PianoNotes.keyInfo(partner).noteName,
          beatRateHz = beat,
          directionLabel = dir,
        )
      )
    }

    addInterval("Major 3rd (5:4)", 4, 5, 4)
    addInterval("Fourth (4:3)", 5, 4, 3)
    addInterval("Fifth (3:2)", 7, 3, 2)
    addInterval("Major 6th (5:3)", 9, 5, 3)
    addInterval("Octave (2:1)", 12, 2, 1)
    return list
  }
}
