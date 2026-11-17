package com.danaudio.pianotuner.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/**
 * Phase-continuous, click-free sine wave tuning fork generator.
 */
class ToneGenerator(private val sampleRate: Int = 48_000) {

  @Volatile
  private var playing = false

  @Volatile
  private var targetFrequencyHz = 440.0

  @Volatile
  private var targetGain = 0.35f

  private var workerThread: Thread? = null

  fun isPlaying(): Boolean = playing

  fun setFrequency(freqHz: Double) {
    targetFrequencyHz = freqHz.coerceIn(20.0, 6000.0)
  }

  fun setVolume(gain: Float) {
    targetGain = gain.coerceIn(0f, 0.85f)
  }

  fun start(initialFreqHz: Double) {
    targetFrequencyHz = initialFreqHz.coerceIn(20.0, 6000.0)
    if (playing) return
    playing = true

    workerThread = thread(start = true, name = "TuningForkAudio", isDaemon = true) {
      val minBufBytes = AudioTrack.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_FLOAT,
      ).coerceAtLeast(2048 * 4)

      val track = AudioTrack.Builder()
        .setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        )
        .setAudioFormat(
          AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        )
        .setBufferSizeInBytes(minBufBytes)
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()

      val blockSize = 512
      val buffer = FloatArray(blockSize)
      var phase = 0.0
      var currentFreq = targetFrequencyHz
      var envGain = 0f

      try {
        track.play()
        while (playing || envGain > 0.0005f) {
          val goalGain = if (playing) targetGain else 0f
          val freqGoal = targetFrequencyHz

          for (i in 0 until blockSize) {
            currentFreq += (freqGoal - currentFreq) * 0.015
            envGain += (goalGain - envGain) * 0.008f
            val inc = 2.0 * PI * currentFreq / sampleRate.toDouble()
            phase += inc
            if (phase >= 2.0 * PI) phase -= 2.0 * PI
            buffer[i] = (sin(phase) * envGain).toFloat()
          }
          track.write(buffer, 0, blockSize, AudioTrack.WRITE_BLOCKING)
        }
      } catch (_: Throwable) {
      } finally {
        try {
          track.stop()
          track.release()
        } catch (_: Throwable) {
        }
      }
    }
  }

  fun stop() {
    playing = false
    workerThread?.join(250)
    workerThread = null
  }
}
