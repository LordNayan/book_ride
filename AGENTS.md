# Book Ride development notes

This repository is a Hindi voice Rapido prototype built from ScreenSaathi.
The active user flow is: destination → ask pickup location → navigate Rapido →
read Bike, Auto and Cab fares → ask for one spoken vehicle choice → tap the
matching Book button once. It never handles Pay or OTP controls. A paid Sarvam
key is not needed; the mic uses Android's on-device Hindi recognizer and TTS
uses an installed offline voice.

## Verify changes

Run `./gradlew testDebugUnitTest assembleDebug`. For emulator iteration, use
`scripts/start-emulator.sh` and `scripts/install-emulator.sh`; passing a
destination to the latter bypasses ASR in debug builds while exercising the
same Rapido executor. The emulator and scripts are described in `README.md`.

The Rapido UI and offline speech service must be checked on the actual Galaxy
S23 before claiming the complete flow works. The emulator is useful for setup,
app crashes and iteration but cannot establish Rapido UI compatibility on the
phone. See `docs/UPSTREAM_README.md` for the original ScreenSaathi project.

## Safety boundaries

- Keep `rapido/RapidoPreview.kt` deterministic and fail closed on unverified
  controls, pickup, destination and fares.
- `ScreenReaderService.tapRapidoLabel` must reject booking and payment labels.
- Only `bookRapidoRide` may tap Book, after an explicit spoken vehicle choice
  and rechecking the selected vehicle, exact Book control and unchanged fare.
- Do not add paid calls or cloud speech to the active mic path.
