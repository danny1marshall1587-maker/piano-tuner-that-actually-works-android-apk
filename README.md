# Piano Tuner Pro (Android)

A full-featured professional piano tuning Android application combining **Cyber-Denoiser PRO** DSP, fast transient Auto-Gain/Compression for short treble notes, inharmonicity-calibrated Railsback stretch tuning, unison string beat analysis, multi-piano profiles, and tuning session logging.

## Key Features

- **Analog Needle Meter + Phase Strobe Strip Bar**: Precision curved cent meter (`-50¢` to `+50¢`) paired directly underneath with a 3-band (`1x / 2x / 4x`) phase-locked strobe strip that scrolls left/right with cent error and locks stationary in emerald green when in tune.
- **Interactive 88-Key Keyboard Display (Red / Yellow / Green)**:
  - **Red**: Active key currently being tuned
  - **Yellow**: Key recorded (measured pitch & inharmonicity $B$ from sweep)
  - **Green**: Key registered as in tune (auto-locks when held steady or marked manually)
  - Full 88-key overview bar for instant navigation + zoomed scrollable keyboard.
- **Cyber-Denoiser PRO Engine** (ported from `de-noise-pro-vst`):
  - 10-band zero-latency parallel 1st-order subtractive filter bank (`60 Hz`–`16 kHz`)
  - Adaptive tail-stabilized release to prevent chirping
  - **Learn Room Noise** mode that profiles ambient room floor and sets per-band thresholds automatically
  - Piano-safe `22 Hz` / `40 Hz` / `Off` low-cut options so sub-bass `A0` (`27.5 Hz`) remains intact.
- **Fast Digital AGC & Hammer Compressor**:
  - Ultra-fast (`2 ms`) digital auto-gain up to `+32 dB` plus high-note transient hold (`HOLD`), lifting quiet or short decaying treble notes (`C6–C8`) into full detection range.
  - Fast-attack (`0.2 ms`) compressor tames close-mic hammer transients without distorting pitch.
- **String Unison Check & Beat Detection**:
  - Complex demodulation envelope beat analysis + high-resolution split-peak detection identifies `Unison Clean`, `Slight Beat`, or `Strings Out!` with estimated beat rate (`Hz`) and cent spread.
- **Professional Stretch Tuning, "Record Every 3rd" & Pitch Calibration**:
  - Adjustable `A4` reference (`415.0–466.0 Hz`), cent transposition, historic temperaments (`Equal`, `Werckmeister III`, `Kirnberger III`, `Vallotti`, `Young`).
  - Configurable sweep step (`Every 3rd`, `Every 4th`, `Every 6th`, `Octaves`, `Chromatic`) to sample keyboard inharmonicity $B$ and fit a custom **Railsback Stretch Curve**.
  - **Pitch-Raise / Overpull Mode** computed automatically from recorded flat deviations.
  - **Interval Beat Rate Checks** (`M3`, `P4`, `P5`, `M6`, `Octave`) for active notes.
- **Sine Wave Tuning Fork & Stabilization Slider**:
  - Click-free continuous sine wave tone generator tuned to the exact stretched target frequency.
  - Wide-range stabilization slider (`0%` raw fast transient to `100%` rock-steady damping).
  - `AUTO FOLLOW` vs `MANUAL MODE` toggle + partial selector (`Auto`, `1st`–`6th`).
- **Multi-Piano Profiles & Tuning Session Logs**:
  - Save separate profiles for multiple pianos (make/model, owner/location, serial, scale type, calibrated stretch curve, key states).
  - Log dated tuning sessions with before/after average deviation, technician notes, and shareable text reports.

## Building & Public Releases

GitHub Actions automatically runs unit tests, builds both `PianoTunerPro-release.apk` and `PianoTunerPro-debug.apk`, and publishes them on GitHub Releases whenever a `v*` tag is pushed.
