package com.danaudio.pianotuner.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.danaudio.pianotuner.dsp.CyberDenoiser
import com.danaudio.pianotuner.dsp.CyberDenoiserParams
import com.danaudio.pianotuner.dsp.CyberDenoiserSnapshot
import com.danaudio.pianotuner.dsp.DynamicsParams
import com.danaudio.pianotuner.dsp.DynamicsProcessor
import com.danaudio.pianotuner.dsp.DynamicsSnapshot
import com.danaudio.pianotuner.dsp.PartialMode
import com.danaudio.pianotuner.dsp.PianoPitchEngine
import com.danaudio.pianotuner.dsp.PitchAnalysisResult
import com.danaudio.pianotuner.dsp.StretchCurveResult
import kotlin.concurrent.thread

data class AudioFrameTelemetry(
  val pitch: PitchAnalysisResult,
  val denoiser: CyberDenoiserSnapshot,
  val dynamics: DynamicsSnapshot,
  val sampleRate: Int,
  val audioSourceName: String,
)

/**
 * Low-latency real-time audio capture & DSP pipeline:
 * Microphone -> Cyber-Denoiser PRO -> Fast Compressor & Digital AGC -> PianoPitchEngine
 */
class AudioCaptureEngine(
  private val onTelemetry: (AudioFrameTelemetry) -> Unit,
) {
  companion object {
    const val SAMPLE_RATE = 48_000
    const val HOP_SAMPLES = 512 // ~10.67 ms per block
  }

  @Volatile
  private var running = false
  private var captureThread: Thread? = null

  private val denoiser = CyberDenoiser(SAMPLE_RATE.toFloat())
  private val dynamics = DynamicsProcessor(SAMPLE_RATE.toFloat())
  private val pitchEngine = PianoPitchEngine(SAMPLE_RATE.toFloat())

  @Volatile
  var denoiserParams: CyberDenoiserParams = CyberDenoiserParams()

  @Volatile
  var dynamicsParams: DynamicsParams = DynamicsParams()

  @Volatile
  var stretchCurve: StretchCurveResult? = null

  @Volatile
  var autoFollow: Boolean = true

  @Volatile
  var manualKeyIndex: Int = 48

  @Volatile
  var partialMode: PartialMode = PartialMode.AUTO

  @Volatile
  var stabilization: Float = 0.45f

  @Volatile
  var suppressDuringTone: Boolean = false

  fun isRunning(): Boolean = running

  fun startCyberLearn(durationSec: Float) {
    denoiser.startLearn(durationSec)
  }

  @SuppressLint("MissingPermission")
  fun start() {
    if (running) return
    running = true

    captureThread = thread(start = true, name = "PianoTunerDsp", isDaemon = true) {
      android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
      val (recorder, sourceName) = createBestRecorder()
      if (recorder == null) {
        running = false
        return@thread
      }

      denoiser.reset()
      dynamics.reset()
      pitchEngine.reset()

      val rawBuf = FloatArray(HOP_SAMPLES)
      val denoisedBuf = FloatArray(HOP_SAMPLES)
      val conditionedBuf = FloatArray(HOP_SAMPLES)
      var hopCounter = 0

      try {
        recorder.startRecording()
        while (running) {
          val read = recorder.read(rawBuf, 0, HOP_SAMPLES, AudioRecord.READ_BLOCKING)
          if (read <= 0) continue

          if (suppressDuringTone) {
            rawBuf.fill(0f, 0, read)
          }

          val denoiseSnap = denoiser.processBlock(rawBuf, denoisedBuf, read, denoiserParams)
          val dynSnap = dynamics.processBlock(denoisedBuf, conditionedBuf, read, dynamicsParams)
          pitchEngine.pushSamples(conditionedBuf, read)

          hopCounter++
          // Emit UI analysis at ~31 Hz (every 3 hops = 32 ms)
          if (hopCounter % 3 == 0) {
            val curve = stretchCurve
            if (curve != null) {
              val pitchRes = pitchEngine.analyze(
                stretchCurve = curve,
                autoFollow = autoFollow,
                manualKeyIndex = manualKeyIndex,
                partialMode = partialMode,
                stabilization = stabilization,
                gateOpen = dynSnap.gateOpen,
              )
              onTelemetry(
                AudioFrameTelemetry(
                  pitch = pitchRes,
                  denoiser = denoiseSnap,
                  dynamics = dynSnap,
                  sampleRate = SAMPLE_RATE,
                  audioSourceName = sourceName,
                )
              )
            }
          }
        }
      } catch (_: Throwable) {
      } finally {
        try {
          recorder.stop()
          recorder.release()
        } catch (_: Throwable) {
        }
      }
    }
  }

  fun stop() {
    running = false
    captureThread?.join(350)
    captureThread = null
  }

  @SuppressLint("MissingPermission")
  private fun createBestRecorder(): Pair<AudioRecord?, String> {
    val candidates = listOf(
      MediaRecorder.AudioSource.UNPROCESSED to "Unprocessed",
      MediaRecorder.AudioSource.VOICE_RECOGNITION to "VoiceRec (Flat)",
      MediaRecorder.AudioSource.MIC to "Default Mic",
    )
    val minBytes = AudioRecord.getMinBufferSize(
      SAMPLE_RATE,
      AudioFormat.CHANNEL_IN_MONO,
      AudioFormat.ENCODING_PCM_FLOAT,
    ).coerceAtLeast(HOP_SAMPLES * 8 * 4)

    for ((src, name) in candidates) {
      try {
        val rec = AudioRecord.Builder()
          .setAudioSource(src)
          .setAudioFormat(
            AudioFormat.Builder()
              .setSampleRate(SAMPLE_RATE)
              .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
              .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
              .build()
          )
          .setBufferSizeInBytes(minBytes)
          .build()
        if (rec.state == AudioRecord.STATE_INITIALIZED) {
          return rec to name
        }
        rec.release()
      } catch (_: Throwable) {
      }
    }
    return null to "Unavailable"
  }
}
