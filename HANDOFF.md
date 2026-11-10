# HANDOFF

Last updated: 2026-11-10

## Start here

Rflow for Android 0.9.0: live translation (the room or what the phone plays), Speak the translation, Meeting mode in
person with the minutes, Conversation, and updates in the app. Rules for working here are in `CLAUDE.md`; the design in
`docs/plan.md`.

## Where it stands

- 2026-11-09: repository created (public, MIT); the plan (phase 0).
- 2026-11-10: phases 1-6 built: setup (Compose in Obsidian Signal, the key in the Keystore, Test key, updates from
  GitHub), live translation (`ai/LiveTranslate.kt`, the desktop's `sst/live/gemini.py` in Kotlin: setup, frames,
  silence left out, the cost saver's gate, lines, reconnects with session resumption, Google's refusals in plain words),
  Speak the translation (`live/VoicePlayer.kt`: Gemini's voice with a cushion, other apps lowered, the microphone
  silenced while it speaks), what the phone plays (`MediaProjection` + playback capture, the floating bar
  `live/CaptionOverlay.kt`), Meeting mode (`live/LiveService.kt` writes `room.wav`; `ai/Minutes.kt` is the desktop's
  `sst/meeting/minutes.py` for a room: Files API, transcript in 20-minute stretches, minutes, the upload deleted),
  Conversation (two Live Translate sessions on one microphone, each keeping only what it translated).
- Tested: 30 unit tests (fake Live API and Gemini on MockWebServer); on an Android 15 emulator against a fake Gemini
  (`Settings → Test server`, debug builds only, with a made-up voice since the emulator's microphone is silent): key
  saved and tested, live captions, a meeting recorded and its minutes written and shared, Conversation, the phone's
  sound with Android's consent and the floating bar, Stop from the bar.
- **Not yet tested:** a real phone with a real Gemini key (the owner's). Before 1.0: Live Translate's quality from a
  phone on a table, the voice and the echo silencing on a speaker, a 1-hour meeting's minutes, battery.

## Releasing

A `vX.Y.Z` tag on `main` runs `.github/workflows/release.yml`: signed `Rflow.apk` + `Rflow.apk.sha256`. The release key
is in the repository's secrets (`RFLOW_KEYSTORE_B64`, `RFLOW_KEYSTORE_PASSWORD`, `RFLOW_KEY_ALIAS`, `RFLOW_KEY_PASSWORD`)
and, with its passwords, on the owner's laptop outside git (`karthi-tech/.android-tools/release/`). **Keep a backup of
it**: an update installs only over an app signed with the same key. Raise `rflowVersion` in `app/build.gradle.kts`
first; the workflow checks the tag matches.

## Testing on the laptop

JDK 17 and the Android SDK are in `karthi-tech/.android-tools/` (not in git). The emulator `rflow` runs headless with
`-audio none` (no sound in or out). A fake Gemini for it: a small aiohttp server (Live API websocket, Files API,
generateContent) on port 8765; set the debug build's Test server to `http://10.0.2.2:8765`.

## Open questions for the owner

- The test phone(s): model and Android version.
- When to go to the Play Store, and under which developer account.
