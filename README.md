# Ride Helper: Hindi voice rides on Rapido

This Android prototype starts from [ScreenSaathi](https://github.com/NITISH-R-G/ScreenSaathi) (MIT license). Say a destination in Hindi. Ride Helper asks whether pickup is at the current location or another place, navigates Rapido, and reads Bike, Auto and Cab fares aloud. After you speak one vehicle choice, it selects that ride and taps Rapido's matching Book button. It never handles payment or OTP.

This version was checked against live Rapido screens on a connected Motorola edge 50 fusion and Galaxy S23 through the fare screen. The final Book tap is implemented and unit tested, but was not exercised on a live ride during development. The app stops if the fare changes after being read or if a required control cannot be verified.

## Build and run

Requirements: JDK 21, Android SDK 36.1, build tools 36.1.0, and a physical Android phone. See [upstream development notes](docs/DEVELOPMENT.md).

1. Copy `local.properties.example` to `local.properties` and set `sdk.dir`. No paid API key is needed.
2. Run `./gradlew testDebugUnitTest assembleDebug`.
3. Install `app/build/outputs/apk/debug/app-debug.apk` on the phone.
4. Open the app and grant microphone, overlay, and accessibility access when Android asks.
5. Sign into Rapido on that phone. Start the assistant; after it asks where to go, say, for example, “राजवाड़ा जाना है”. It automatically listens after each spoken question, including pickup and fare choice. You can tap the mic during a prompt to answer early. After hearing the three fares, say “बाइक”, “ऑटो” or “कैब” to book that option.

The search picks the first result with the strongest title match. Rapido may show Cab Daily or Cab Economy; both are spoken as “कैब”, with the displayed fare. Check the pickup pin and full destination in Rapido. The Stop button cancels the assistant flow.

## Current limits

- Rapido's screen labels and accessibility tree can change. The matcher accepts the observed pickup, search and fare screens, and stops when it cannot verify them.
- Speech recognition uses Android's **on-device** recognition service with Hindi (`hi-IN`). On Android 13 and newer, the app checks whether a Hindi pack is installed and requests its download if available. If the phone does not support it, the app stops; it never falls back to an online recognizer. Speech output uses an installed offline Android TTS voice. If no such voice is installed, the text remains visible without audio and the mic button remains available.
- Rapido receives only a Latin-script place name. The app removes ride words from the spoken request, uses English names for common Indore places, and transliterates other Hindi place names offline. If a clean place name cannot be obtained, it stops before pasting.
- While Ride Helper is guiding Rapido, it closes Rapido's keyboard after entering a location and whenever the keyboard reappears. If the location row cannot be selected automatically, it asks in Hindi for you to tap the correct row in Rapido and resumes when the next screen appears. Common street-word recognition slips such as “roda” are corrected to “Road” before searching.
- With all permissions granted, opening Ride Helper starts the floating assistant. Its translucent card stays at the bottom; the cross in its top right minimizes it to an app-icon bubble. The bubble docks on either screen edge, remembers its edge and height, and opens the Hindi destination prompt when tapped. Reopen the app after an APK update to restore its floating control.
- Pickup and destination text is pasted through Android's clipboard because Rapido's custom fields do not accept accessibility set-text actions. This replaces the phone's current clipboard contents.
- The app does not handle payment, OTP, or any extra confirmation screen that Rapido might show after Book. A Book tap is sent at most once for a spoken choice.

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
