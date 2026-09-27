# Book Ride: Rapido fare preview

This Android prototype starts from [ScreenSaathi](https://github.com/NITISH-R-G/ScreenSaathi) (MIT license). It adds a narrow Rapido flow: speak a destination in Hindi, open Rapido, enter the destination, select one unambiguous result and ride type, then read the visible fare aloud. **It never taps Book, Pay, Confirm, or an OTP control.**

This is a first version for testing on one Android phone. It has not been tested against a live Rapido screen in this workspace. It stops when the screen does not expose one clear control or fare.

## Build and run

Requirements: JDK 21, Android SDK 36.1, build tools 36.1.0, and a physical Android phone. See [upstream development notes](docs/DEVELOPMENT.md).

1. Copy `local.properties.example` to `local.properties` and set `sdk.dir`. No paid API key is needed.
2. Run `./gradlew testDebugUnitTest assembleDebug`.
3. Install `app/build/outputs/apk/debug/app-debug.apk` on the phone.
4. Open the app and grant microphone, overlay, and accessibility access when Android asks.
5. Sign into Rapido on that phone. Start the assistant, tap the floating pill, and say, for example, “राजवाड़ा जाना है” or “Rapido par Rajwada ke liye auto chahiye”. With no vehicle spoken, the prototype chooses Auto.

The app displays and speaks the preview when the exact destination, ride type, one rupee fare, and a Book control are visible. Check the pickup pin and full destination in Rapido yourself. The Stop button cancels the assistant flow. The app does not request a ride.

## Current limits

- Rapido's screen labels and accessibility tree can change. The current matcher intentionally accepts only a small set of destination field labels and uniquely identifiable search results. An ambiguous location or fare stops the flow.
- Speech recognition uses Android's **on-device** recognition service with Hindi (`hi-IN`). On Android 13 and newer, the app checks whether a Hindi pack is installed and requests its download if available. If the phone does not support it, the app stops; it never falls back to an online recognizer. Speech output uses an installed offline Android TTS voice. If no such voice is installed, the text remains visible without audio.
- The result selector currently requires the destination to begin the visible result label, followed by a comma and further address text. This reduces accidental selection of the text still in the search field.
- There is no final booking step, payment handling, OTP handling, or automatic retry of a booking request.

## Galaxy S23 offline speech candidate

The multilingual `ggml-base-q5_1.bin` Whisper model on [Hugging Face](https://huggingface.co/ggerganov/whisper.cpp) is about 57 MiB. [`whisper.cpp` includes an Android example](https://github.com/ggml-org/whisper.cpp/tree/master/examples/whisper.android), and its maintainers recommend tiny or base models for Android. It is a plausible **app-bundled fallback** if the S23 lacks an installed on-device Hindi speech pack, but it is not wired into this version. Hindi place-name accuracy, latency, battery use, and code switching need measurement on the actual phone before switching providers. The deterministic destination parser and Rapido executor do not depend on the speech provider.

[Qwen3-ASR-0.6B](https://huggingface.co/Qwen/Qwen3-ASR-0.6B-hf) also supports Hindi offline, but its published route uses Transformers and is a much larger integration. A general text SLM would not replace speech recognition for this task.

The original ScreenSaathi README is preserved at [docs/UPSTREAM_README.md](docs/UPSTREAM_README.md); its description of a guidance-only assistant describes the upstream project, not this Rapido extension.

## Mac emulator loop

An ARM64 Pixel 7 Android 15 emulator named `book_ride_api35` has been created on this Mac with a Google Play system image. The SDK is at `/private/tmp/saathi-sdk` and the AVD is at `/private/tmp/saathi-avd`. Those temporary directories can be cleaned by macOS; if that happens, reinstall the SDK and create the AVD again.

- Start the emulator: `scripts/start-emulator.sh`
- Build, test and install: `scripts/install-emulator.sh`
- Bypass speech recognition while developing the Rapido executor: `scripts/install-emulator.sh 'Rajwada jaana hai'`. This shortcut is available in debug builds only.

Complete the one-time overlay, microphone and accessibility setup in the emulator. Sign in to Google Play and install Rapido there to test its actual UI. The Play Store image is present, but Rapido is not bundled with this project. If the emulator cannot provide offline Hindi recognition, the debug shortcut still lets you iterate on screen matching; the real Galaxy S23 must validate speech and the final flow.
