package com.danaudio.pianotuner.dsp

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PianoDspTests {

  private val sr = 48_000f

  @Test
  fun cyberDenoiser_learnsNoiseAndAttenuatesRoomFloor() {
    val denoiser = CyberDenoiser(sr)
    val rng = Random(42)
    val block = FloatArray(512)
    val out = FloatArray(512)

    var params = CyberDenoiserParams(enabled = true, masterThresholdDb = -58f, learnDurationSec = 1.0f)
    denoiser.startLearn(1.0f)

    var learned: FloatArray? = null
    // Feed 1.1 seconds of pink-ish noise
    val blocks = (1.1f * sr / 512f).toInt() + 2
    for (b in 0 until blocks) {
      for (i in block.indices) {
        block[i] = (rng.nextFloat() * 2f - 1f) * 0.02f
      }
      val snap = denoiser.processBlock(block, out, 512, params)
      if (snap.learnedThresholdsDb != null) {
        learned = snap.learnedThresholdsDb
        params = params.copy(bandThresholdsDb = learned)
      }
    }
    assertNotNull("Learn mode should emit 10-band thresholds", learned)

    // Measure noise RMS after learning
    var inSq = 0.0
    var outSq = 0.0
    for (b in 0 until 20) {
      for (i in block.indices) {
        block[i] = (rng.nextFloat() * 2f - 1f) * 0.015f
      }
      denoiser.processBlock(block, out, 512, params)
      if (b >= 10) {
        for (i in block.indices) {
          inSq += block[i] * block[i]
          outSq += out[i] * out[i]
        }
      }
    }
    val inRms = sqrt(inSq / (10 * 512))
    val outRms = sqrt(outSq / (10 * 512))
    assertTrue("CyberDenoiser should suppress stationary noise (in=$inRms, out=$outRms)", outRms < inRms * 0.45)
  }

  @Test
  fun dynamicsProcessor_fastAgcLiftsShortQuietHighNote() {
    val dyn = DynamicsProcessor(sr)
    val params = DynamicsParams(
      agcEnabled = true,
      agcMaxBoostDb = 32f,
      agcAttackMs = 2.0f,
      noiseGateDb = -68f,
    )
    // 45 ms quiet C8 burst (4186 Hz, amp = 0.012)
    val n = 2048 // ~42.6 ms
    val input = FloatArray(n) { i ->
      (0.012 * sin(2.0 * PI * 4186.0 * i / sr)).toFloat()
    }
    val output = FloatArray(n)
    val snap = dyn.processBlock(input, output, n, params)
    assertTrue("Fast AGC should boost quiet C8 burst substantially, got +${snap.agcGainDb} dB", snap.agcGainDb > 12f)
    assertTrue("Gate should open on quiet C8 note", snap.gateOpen)
  }

  @Test
  fun pitchEngine_detectsA4_shortC8_weakBassA0_inharmonicity_and_unisonBeat() {
    val engine = PianoPitchEngine(sr)
    val curve = StretchTuningEngine.computeStretchCurve(
      a4Hz = 440.0,
      transposeCents = 0.0,
      scaleType = PianoScaleType.UPRIGHT,
      temperament = TemperamentType.EQUAL,
      stretchFactor = 0.0, // pure ET for exact Hz check
    )

    // 1. Test stiff-string A4 with known B = 0.00035
    val bTrue = 0.00035
    val buf = FloatArray(16384)
    for (i in buf.indices) {
      var s = 0.0
      for (p in 1..5) {
        val fp = StretchTuningEngine.partialFreq(440.0, p, bTrue)
        s += (0.5 / p) * sin(2.0 * PI * fp * i / sr)
      }
      buf[i] = s.toFloat()
    }
    engine.pushSamples(buf, buf.size)
    val resA4 = engine.analyze(curve, autoFollow = true, manualKeyIndex = 48, PartialMode.AUTO, stabilization = 0f, gateOpen = true)
    assertTrue("Should detect A4 signal", resA4.hasSignal)
    assertEquals(PianoNotes.A4_KEY_INDEX, resA4.detectedKeyIndex)
    assertTrue("A4 cents error should be tiny, got ${resA4.centsError}", kotlin.math.abs(resA4.centsError) < 1.5)
    assertNotNull("Should estimate inharmonicity B", resA4.measuredInharmonicityB)
    assertTrue(
      "Estimated B=${resA4.measuredInharmonicityB} should be close to $bTrue",
      kotlin.math.abs((resA4.measuredInharmonicityB ?: 0.0) - bTrue) < 0.00018,
    )

    // 2. Test short 45ms C8 (4186.01 Hz) high treble note!
    engine.reset()
    val c8Buf = FloatArray(2200) { i ->
      val env = kotlin.math.exp(-i / 1400.0)
      (0.4 * env * sin(2.0 * PI * 4186.01 * i / sr)).toFloat()
    }
    engine.pushSamples(c8Buf, c8Buf.size)
    val resC8 = engine.analyze(curve, autoFollow = true, manualKeyIndex = 48, PartialMode.AUTO, stabilization = 0f, gateOpen = true)
    assertTrue("Should detect short C8 signal", resC8.hasSignal)
    assertEquals("Should identify C8 (key 87)", 87, resC8.detectedKeyIndex)
    assertTrue("C8 cents error should be accurate, got ${resC8.centsError}", kotlin.math.abs(resC8.centsError) < 3.0)

    // 3. Test sub-bass A0 (27.5 Hz) with almost zero fundamental and strong 2..5 partials!
    engine.reset()
    val a0B = curve.inharmonicityCurveB[0]
    val a0Buf = FloatArray(16384) { i ->
      var s = 0.0
      for (p in 2..6) {
        val fp = StretchTuningEngine.partialFreq(27.5, p, a0B)
        s += 0.22 * sin(2.0 * PI * fp * i / sr)
      }
      s.toFloat()
    }
    engine.pushSamples(a0Buf, a0Buf.size)
    val resA0 = engine.analyze(curve, autoFollow = false, manualKeyIndex = 0, PartialMode.AUTO, stabilization = 0f, gateOpen = true)
    assertTrue("Should detect bass A0 from upper partials", resA0.hasSignal)
    assertTrue("A0 cents error should be within 3c, got ${resA0.centsError}", kotlin.math.abs(resA0.centsError) < 3.0)

    // 4. Test out-of-tune unison strings (440 Hz + 444.5 Hz = 4.5 Hz beat)
    engine.reset()
    val beatBuf = FloatArray(16384) { i ->
      (0.35 * sin(2.0 * PI * 440.0 * i / sr) + 0.35 * sin(2.0 * PI * 444.5 * i / sr)).toFloat()
    }
    engine.pushSamples(beatBuf, beatBuf.size)
    val resBeat = engine.analyze(curve, autoFollow = false, manualKeyIndex = 48, PartialMode.AUTO, stabilization = 0f, gateOpen = true)
    assertTrue(
      "Should flag out-of-tune unison strings (got ${resBeat.unison.health})",
      resBeat.unison.health == UnisonHealth.STRINGS_OUT || resBeat.unison.health == UnisonHealth.SLIGHT_BEAT,
    )

    // 5. Test single pure string at 373.0 Hz (F#4 alias trap for 750 Hz chunk demodulation) reports CLEAN!
    engine.reset()
    val pureFSharp = FloatArray(16384) { i ->
      (0.5 * sin(2.0 * PI * 369.99 * i / sr) + 0.2 * sin(2.0 * PI * 740.0 * i / sr)).toFloat()
    }
    engine.pushSamples(pureFSharp, pureFSharp.size)
    val resPure = engine.analyze(curve, autoFollow = false, manualKeyIndex = 45, PartialMode.AUTO, stabilization = 0f, gateOpen = true)
    assertEquals(
      "Pure F#4 without beating should report CLEAN (detail=${resPure.unison.detailMessage})",
      UnisonHealth.CLEAN,
      resPure.unison.health,
    )

    // 6. Test short 42ms C8 burst still wins in Auto Follow even with faint lingering C5 in older buffer!
    engine.reset()
    val preRing = FloatArray(14336) { i ->
      (0.06 * sin(2.0 * PI * 523.25 * i / sr)).toFloat()
    }
    val burstC8 = FloatArray(2048) { i ->
      (0.35 * sin(2.0 * PI * 4186.01 * i / sr)).toFloat()
    }
    engine.pushSamples(preRing, preRing.size)
    engine.pushSamples(burstC8, burstC8.size)
    val resAutoC8 = engine.analyze(curve, autoFollow = true, manualKeyIndex = 48, PartialMode.AUTO, stabilization = 0f, gateOpen = true)
    assertEquals("Auto Follow should pick fresh C8 burst over faint lingering C5", 87, resAutoC8.detectedKeyIndex)
  }

  @Test
  fun stretchCurve_railsbackShapeAndOverpullWork() {
    val measuredB = mapOf(
      0 to 0.0012,
      12 to 0.0005,
      24 to 0.00025,
      36 to 0.00018,
      48 to 0.00022,
      60 to 0.00045,
      72 to 0.0018,
      84 to 0.0065,
    )
    val flatRecorded = mapOf(
      0 to -24.0,
      24 to -20.0,
      48 to -16.0,
      72 to -12.0,
    )
    val res = StretchTuningEngine.computeStretchCurve(
      a4Hz = 440.0,
      transposeCents = 0.0,
      scaleType = PianoScaleType.UPRIGHT,
      temperament = TemperamentType.EQUAL,
      stretchFactor = 1.0,
      measuredB = measuredB,
      recordedCentsBefore = flatRecorded,
      overpullEnabled = true,
      overpullPercent = 0.25,
    )
    assertEquals(0.0, res.stretchOffsetsCents[PianoNotes.A4_KEY_INDEX], 1e-6)
    assertTrue("Bass A0 should stretch negative/flat", res.stretchOffsetsCents[0] < -5.0)
    assertTrue("Treble C8 should stretch sharp", res.stretchOffsetsCents[87] > 10.0)
    assertTrue("Overpull on flat A0 (-24c @ 25%) should be +6c", kotlin.math.abs(res.overpullOffsetsCents[0] - 6.0) < 0.5)
  }
}
