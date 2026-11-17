package com.danaudio.pianotuner.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

object FftMath {

  fun hannWindow(size: Int): DoubleArray {
    val w = DoubleArray(size)
    val denom = (size - 1).coerceAtLeast(1).toDouble()
    for (i in 0 until size) {
      w[i] = 0.5 * (1.0 - cos(2.0 * PI * i / denom))
    }
    return w
  }

  fun blackmanHarrisWindow(size: Int): DoubleArray {
    val w = DoubleArray(size)
    val denom = (size - 1).coerceAtLeast(1).toDouble()
    val a0 = 0.35875
    val a1 = 0.48829
    val a2 = 0.14128
    val a3 = 0.01168
    for (i in 0 until size) {
      val t = 2.0 * PI * i / denom
      w[i] = a0 - a1 * cos(t) + a2 * cos(2.0 * t) - a3 * cos(3.0 * t)
    }
    return w
  }

  /**
   * In-place radix-2 Cooley-Tukey complex FFT.
   * [real] and [imag] must have equal power-of-two lengths.
   */
  fun fft(real: DoubleArray, imag: DoubleArray) {
    val n = real.size
    require(n > 0 && (n and (n - 1)) == 0) { "FFT size must be a power of 2, got $n" }

    var j = 0
    for (i in 1 until n) {
      var bit = n shr 1
      while (j and bit != 0) {
        j = j xor bit
        bit = bit shr 1
      }
      j = j xor bit
      if (i < j) {
        val tr = real[i]
        real[i] = real[j]
        real[j] = tr
        val ti = imag[i]
        imag[i] = imag[j]
        imag[j] = ti
      }
    }

    var len = 2
    while (len <= n) {
      val half = len shr 1
      val ang = -2.0 * PI / len
      val wLenCos = cos(ang)
      val wLenSin = sin(ang)
      var i = 0
      while (i < n) {
        var wCos = 1.0
        var wSin = 0.0
        for (k in 0 until half) {
          val uR = real[i + k]
          val uI = imag[i + k]
          val vR = real[i + k + half] * wCos - imag[i + k + half] * wSin
          val vI = real[i + k + half] * wSin + imag[i + k + half] * wCos
          real[i + k] = uR + vR
          imag[i + k] = uI + vI
          real[i + k + half] = uR - vR
          imag[i + k + half] = uI - vI
          val nextWCos = wCos * wLenCos - wSin * wLenSin
          wSin = wCos * wLenSin + wSin * wLenCos
          wCos = nextWCos
        }
        i += len
      }
      len = len shl 1
    }
  }

  fun computeMagnitudeSpectrum(
    samples: FloatArray,
    sampleCount: Int,
    fftSize: Int,
    window: DoubleArray,
    realOut: DoubleArray,
    imagOut: DoubleArray,
    magOut: DoubleArray,
  ) {
    realOut.fill(0.0)
    imagOut.fill(0.0)
    val copyLen = minOf(sampleCount, samples.size, window.size, fftSize)
    for (i in 0 until copyLen) {
      realOut[i] = samples[i].toDouble() * window[i]
    }
    fft(realOut, imagOut)
    val half = fftSize / 2
    for (i in 0..half) {
      magOut[i] = hypot(realOut[i], imagOut[i])
    }
  }

  /**
   * Parabolic interpolation on log-magnitude spectrum for sub-bin peak frequency accuracy.
   */
  fun interpolatePeakBin(mag: DoubleArray, peakBin: Int): Double {
    if (peakBin <= 1 || peakBin >= mag.size - 2) return peakBin.toDouble()
    val a = kotlin.math.ln(mag[peakBin - 1] + 1e-12)
    val b = kotlin.math.ln(mag[peakBin] + 1e-12)
    val c = kotlin.math.ln(mag[peakBin + 1] + 1e-12)
    val denom = a - 2.0 * b + c
    if (kotlin.math.abs(denom) < 1e-12) return peakBin.toDouble()
    val delta = (0.5 * (a - c) / denom).coerceIn(-0.5, 0.5)
    return peakBin + delta
  }
}
