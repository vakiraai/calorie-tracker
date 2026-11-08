# LiftCal: weight-training calories for the Fitbit Air

An Android app (Kotlin, Jetpack Compose) that estimates calories burned during resistance
training. It combines heart rate from a **Google Fitbit Air** band with the sets you log, and it
uses a physiological model designed to fix the known failure modes of wrist wearables during
lifting.

## Why wrist wearables get lifting wrong

Apple Watch, Garmin and Fitbit mostly convert heart rate to energy with equations built for
steady cardio. Lifting breaks those equations in three ways:

1. **Heart rate overstates oxygen use.** Under load, heart rate rises from the pressor response,
   breath-holding and sympathetic drive more than from aerobic metabolism.
2. **Heart rate misses anaerobic work.** A 30-second set runs mostly on phosphocreatine and
   glycolysis, so energy is spent before heart rate reacts.
3. **They ignore load and recovery.** They don't know how much weight you moved or how big the
   recovery cost (EPOC) of each set is.

## How LiftCal estimates

The engine in [`engine/`](engine/) is a pure Kotlin library. It works second by second over the
session:

| Component | Model |
|---|---|
| Resting energy | Katch-McArdle when body fat is known, otherwise Mifflin-St Jeor. The resting VO2 is personal, not the 3.5 ml/kg/min constant. |
| Aerobic | VO2-reserve method (%HRR is about %VO2R). It uses measured or estimated HRmax and VO2max, corrects for heart-rate lag, applies a lifting-specific HR-to-VO2 scale, and down-weights heart rate inside sets. |
| Anaerobic | Mechanical work from load x reps x range of motion (per exercise, scaled by height and bodyweight fraction), divided by gross efficiency. It adds eccentric and isometric cost and an effort factor from RPE. |
| EPOC | Two-exponential repayment (fast and slow) after each set, sized by that set's anaerobic cost. Between sets it is reconciled with heart-rate-measured recovery using a smooth maximum. The post-session tail is added analytically. |
| Fusion | Inverse-variance (Kalman-style) blend of the heart-rate and model estimates, weighted by heart-rate signal quality: coverage, sample density, artefacts, and learned wrist reliability. |
| Uncertainty | Every estimate comes with a 90% interval that narrows as you add measured values and calibration sessions. |
| Calibration | Regularised Levenberg-Marquardt fit of personal parameters (HR-to-VO2 scale, in-set HR weight, work cost, EPOC scale) against metabolic-cart sessions. A chest-strap comparison learns how far your wrist heart rate can be trusted during lifts. |

## Where the data comes from

- **Health Connect** is the primary source: heart rate that the Fitbit app shares from the
  Fitbit Air, plus resting HR, VO2max, weight, height and body fat to pre-fill your profile.
  Results are written back as a weightlifting exercise session with active and total calories.
- **Fitbit Web API** is a fallback: second-level intraday heart rate when Health Connect data is
  too sparse, plus Fitbit's own calorie figure for comparison. It needs an app registered at
  [dev.fitbit.com](https://dev.fitbit.com/apps) with type *Personal* (required for intraday data)
  and redirect URI `com.liftcal.app:/oauth2redirect`. Put the client ID in `local.properties`:

  ```properties
  fitbit.clientId=XXXXXX
  ```

- **BLE chest strap** (optional): any standard Bluetooth heart-rate strap, such as a Polar H10.
  It gives live heart rate on screen and serves as a reference for calibration.

Fitbit syncs in batches, so a workout stays **"Waiting for band sync"** with a provisional
estimate. A WorkManager job finalises it once heart rate covers the end of the session.

## Getting the most accurate numbers

1. **Enter measured max HR and VO2max** in your profile, from a lab or a max-effort field test.
2. **Use Start set / Finish set** rather than quick logging. Precise set timing lines your sets
   up with the band's heart rate.
3. **Log RPE** for working sets.
4. **Calibrate once**:
   - Ideal: one or two lifting sessions on a metabolic cart. Export CSV with the columns
     `time_s,vo2_ml_min,vco2_ml_min` and import it under Calibration.
   - Otherwise: one session wearing both the band and a chest strap.

## Proving accuracy: the validation harness

The claim "more accurate than Apple Watch or Garmin" needs data measured against indirect
calorimetry. [`validation/`](validation/) is a command-line tool that replays sessions and
compares several methods against the reference:

- LiftCal with population defaults
- LiftCal with leave-one-out personal calibration (it never sees the session it is scored on)
- an HR-only baseline using Keytel 2005, the family of equations wrist devices build on
- a MET-table baseline
- any device totals you record, such as `apple_watch`, `garmin` or `fitbit`

It reports MAPE, bias, Bland-Altman limits of agreement, Lin's CCC, 90% interval coverage and
5-minute window error.

```bash
./gradlew :validation:run --args="report --fixtures validation/fixtures --out validation/reports/report.md"
./gradlew :validation:run --args="generate --out /tmp/synthetic --subjects 6 --sessions 3"
```

[`validation/fixtures/`](validation/fixtures/) currently holds **synthetic** sessions from an
independent ground-truth simulator. Its assumptions include hidden personal traits, an HR-VO2
gap specific to lifting, and wrist HR errors. These fixtures test the pipeline; they are **not**
evidence of real-world accuracy. On them, LiftCal with defaults has about 27% MAPE (HR-only: 42%,
MET table: 78%), and with leave-one-out personal calibration about 3%.

To add real data, drop a fixture JSON next to its cart CSV. Set times are seconds from the
session start:

```json
{
  "id": "lab-p01-s1", "subject": "P01",
  "profile": {"ageYears": 31, "sex": "female", "weightKg": 62, "heightCm": 168, "maxHr": 186, "vo2Max": 41},
  "session": {"startMs": 1780000000000, "durationSec": 3600, "sets": [
    {"exerciseId": "back_squat", "loadKg": 60, "reps": 8, "startSec": 300, "endSec": 328, "rpe": 8}
  ]},
  "hr": [{"source": "wrist", "samples": [[0, 72], [1, 73]]}],
  "reference": {"cartCsv": "lab-p01-s1.csv", "kind": "cart"},
  "devices": {"apple_watch": 310, "garmin": 265, "fitbit": 290}
}
```

## Project layout

| Module | Contents |
|---|---|
| `engine` | Calorie model, calibration, baselines and metabolic-cart parsing. Pure JVM, fully unit and property tested. |
| `data` | Room database, Health Connect repository, Fitbit Web API client (AppAuth OAuth with PKCE), heart-rate alignment and sync state, session finaliser and worker, BLE heart-rate client. |
| `app` | Compose UI: profile, workout logger with rest timer and live HR, summary with per-set breakdown and interval, history, calibration, connections. |
| `validation` | Fixture format, synthetic generator, metrics and markdown report CLI. |

## Building

Requires JDK 17+ and the Android SDK (compileSdk 36).

```bash
./gradlew test lint assembleDebug
```

## Limitations

- The Fitbit Air's Health Connect sampling rate and sync latency are not documented. The engine
  handles 1-second to multi-second sampling, and the harness reports the effect.
- No raw motion data is available from the band, so rep timing comes from your taps.
- The live chest-strap connection lives in the app process, and the workout screen keeps the
  display on. A foreground service would be needed for screen-off recording.
