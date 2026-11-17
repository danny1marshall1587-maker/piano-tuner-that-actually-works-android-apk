package com.danaudio.pianotuner.data

import com.danaudio.pianotuner.dsp.PianoScaleType
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PianoProfileRepositoryTest {

  @Test
  fun repository_persistsMultipleProfilesAndTuningLogs() {
    val tmpFile = File.createTempFile("piano_store_test", ".json")
    tmpFile.deleteOnExit()
    tmpFile.delete()

    val repo = PianoProfileRepository(tmpFile)
    val initial = repo.loadStore()
    assertEquals(1, initial.profiles.size)

    val second = PianoProfile(
      id = "steinway-b",
      name = "Recital Hall Steinway",
      makeAndModel = "Steinway Model B",
      scaleType = PianoScaleType.CONCERT_GRAND,
      a4TargetHz = 442.0,
      keyRecords = mapOf(
        48 to KeyMeasurementRecord(
          keyIndex = 48,
          status = KeyTuningStatus.IN_TUNE,
          measuredBeforeCents = -14.2,
          lastTunedCents = 0.3,
          measuredInharmonicityB = 0.00018,
          unisonHealthName = "Unison Clean",
        )
      ),
      logs = listOf(
        TuningSessionLog(
          id = "log-1",
          timestampEpochMs = 1_700_000_000_000L,
          a4Hz = 442.0,
          transposeCents = 0.0,
          temperamentName = "Equal Temperament",
          stretchFactor = 1.0,
          overpullUsed = false,
          recordedCount = 0,
          inTuneCount = 1,
          avgInitialDeviationCents = 14.2,
          avgFinalDeviationCents = 0.3,
          notes = "Concert prep",
        )
      ),
    )

    val updated = initial.copy(
      activeProfileId = "steinway-b",
      profiles = listOf(second) + initial.profiles,
    )
    repo.saveStore(updated)

    val reloaded = repo.loadStore()
    assertEquals(2, reloaded.profiles.size)
    assertEquals("steinway-b", reloaded.activeProfileId)
    val loadedSecond = reloaded.profiles.first { it.id == "steinway-b" }
    assertEquals(442.0, loadedSecond.a4TargetHz, 1e-6)
    assertEquals(1, loadedSecond.logs.size)
    assertTrue(repo.formatProfileReport(loadedSecond).contains("Steinway Model B"))
  }
}
