# Ride Helper data handling

This describes the active Rapido voice flow. The inherited ScreenSaathi code
contains other prototype features; see their source before enabling them.

## On the phone

- Android's on-device Hindi recognizer turns speech into text. An installed
  offline Android voice speaks prompts and fares. The active ride flow does not
  send microphone audio to Sarvam or OpenAI.
- The accessibility service reads Rapido's visible controls, locations and
  fares so the app can navigate them. It does not send the screen tree or a
  screenshot to OpenAI.
- The app pastes its English place search into Rapido through the clipboard,
  replacing the clipboard's previous contents.
- The floating icon's saved edge and height are kept in app preferences.

## Optional OpenAI place correction

If `ride.openai.apiKey` is set in the gitignored `local.properties` when the
APK is built, the app may send an extracted English place name to the OpenAI
Responses API. It does this only when the offline parser's spelling is outside
the built-in Indore list. It sends no audio, full spoken sentence, screen text,
fare, phone location or ride history in that request. The web search tool is
given Indore as an approximate city. The request sets `store: false`. A
corrected name outside the built-in list can be used only when the API reports
a completed search and returns the model's cited HTTPS source URL. The source
is linked in the floating panel. On failure, the offline place name is used.

The API key is embedded in the personal APK and can be extracted from it. Keep
the APK private and monitor usage in the OpenAI project. Without a configured
key, the active Rapido flow makes no OpenAI request.
