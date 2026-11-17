package com.danaudio.pianotuner.dsp

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

enum class PianoRegister(val label: String) {
  SUB_BASS("Sub-Bass"),
  BASS("Bass"),
  TENOR("Tenor"),
  MID("Midrange"),
  TREBLE("Treble"),
  HIGH_TREBLE("High Treble"),
}

data class PianoKeyInfo(
  val keyIndex: Int, // 0..87 (A0..C8)
  val midiNote: Int, // 21..108
  val noteName: String, // e.g. "A0", "C#4", "C8"
  val pitchClass: String, // e.g. "A", "C#"
  val octave: Int,
  val isBlack: Boolean,
  val register: PianoRegister,
  val typicalStringCount: Int, // 1 = monochord, 2 = bichord, 3 = trichord
)

object PianoNotes {
  const val TOTAL_KEYS = 88
  const val A0_MIDI = 21
  const val A4_KEY_INDEX = 48 // MIDI 69 = A4

  private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
  private val BLACK_CLASSES = booleanArrayOf(false, true, false, true, false, false, true, false, true, false, true, false)

  val allKeys: List<PianoKeyInfo> = List(TOTAL_KEYS) { keyIndex ->
    val midi = A0_MIDI + keyIndex
    val pcIndex = midi % 12
    val octave = (midi / 12) - 1
    val pc = NOTE_NAMES[pcIndex]
    val register = when {
      keyIndex < 12 -> PianoRegister.SUB_BASS
      keyIndex < 27 -> PianoRegister.BASS
      keyIndex < 39 -> PianoRegister.TENOR
      keyIndex < 55 -> PianoRegister.MID
      keyIndex < 72 -> PianoRegister.TREBLE
      else -> PianoRegister.HIGH_TREBLE
    }
    val stringCount = when {
      keyIndex < 10 -> 1
      keyIndex < 28 -> 2
      else -> 3
    }
    PianoKeyInfo(
      keyIndex = keyIndex,
      midiNote = midi,
      noteName = "$pc$octave",
      pitchClass = pc,
      octave = octave,
      isBlack = BLACK_CLASSES[pcIndex],
      register = register,
      typicalStringCount = stringCount,
    )
  }

  fun keyInfo(keyIndex: Int): PianoKeyInfo = allKeys[keyIndex.coerceIn(0, TOTAL_KEYS - 1)]

  fun equalTemperamentHz(keyIndex: Int, a4Hz: Double = 440.0, transposeCents: Double = 0.0): Double {
    val semitones = (keyIndex - A4_KEY_INDEX).toDouble() + (transposeCents / 100.0)
    return a4Hz * 2.0.pow(semitones / 12.0)
  }

  fun hzToCents(measuredHz: Double, referenceHz: Double): Double {
    if (measuredHz <= 0.0 || referenceHz <= 0.0) return 0.0
    return 1200.0 * (ln(measuredHz / referenceHz) / ln(2.0))
  }

  fun centsToHz(referenceHz: Double, cents: Double): Double {
    return referenceHz * 2.0.pow(cents / 1200.0)
  }

  fun nearestKeyIndex(freqHz: Double, targetsHz: DoubleArray): Int {
    if (freqHz <= 0.0 || targetsHz.isEmpty()) return A4_KEY_INDEX
    var bestIdx = 0
    var bestAbsCents = Double.MAX_VALUE
    for (i in targetsHz.indices) {
      val absCents = kotlin.math.abs(hzToCents(freqHz, targetsHz[i]))
      if (absCents < bestAbsCents) {
        bestAbsCents = absCents
        bestIdx = i
      }
    }
    return bestIdx
  }

  fun nearestEqualTempKey(freqHz: Double, a4Hz: Double = 440.0): Int {
    if (freqHz <= 0.0) return A4_KEY_INDEX
    val midiFloat = 69.0 + 12.0 * (ln(freqHz / a4Hz) / ln(2.0))
    return (midiFloat.roundToInt() - A0_MIDI).coerceIn(0, TOTAL_KEYS - 1)
  }
}
