# Rflow for Android

**Work in any language, on your phone.** Understand the people around you as they speak, and get your meetings
written up.

Rflow for Android is the phone companion of [Rflow for Windows](https://github.com/karthi-ai-engineer/rflow-ai).
Download `Rflow.apk` from the [latest release](https://github.com/karthi-ai-engineer/rflow-android/releases/latest)
(Android 10 and newer); after that the app updates itself: it checks GitHub when it opens, and an update is two taps.

## What it does

- **Live translation:** the people around you (the microphone), or a video, podcast or lecture playing on the phone
  (captions in a bar over the app), read in your language as it's said. **Speak the translation** says it aloud too,
  in Gemini's own voice. The **cost saver** sends only speech and stops after 15 minutes without words.
- **Meeting mode:** lay the phone on the table. It records the room, translates live, and when you stop writes the
  minutes: who said what, a summary, decisions and action items, in the language of the meeting. Share them as text.
  In person only: Android doesn't let apps hear phone, Teams or Zoom calls; for online meetings use Rflow for Windows.
- **Conversation:** two people, two languages, one phone between you. The top half is upside down for the person
  across the table; each of you reads the other in your own language.

**Later:** the Rflow Keyboard (voice typing in any app), translating and rewriting any text, the Screen translator.

You bring your own Google Gemini key, as on Windows (Settings → Google Gemini key). It stays on the phone, sealed by
the Android Keystore, and is never backed up. The same Gemini models as the desktop: Live Translate for the live
parts, Gemini 3.6 Flash for the minutes.

## Building

```
./gradlew testDebugUnitTest lintDebug assembleDebug   # JDK 17, Android SDK 35
```

How it works and why is in [`docs/plan.md`](docs/plan.md); where the work stands in [`HANDOFF.md`](HANDOFF.md).

## Licence

MIT (`LICENSE`). The Rflow name and logo are not covered by the licence. Third-party parts: `NOTICES.txt`.
