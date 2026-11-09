# Rflow for Android: the plan

Written 2026-11-09, before any code. It says what Rflow does on a phone, how each part works on Android, what Android
doesn't allow, and the order of the work. Change it here when a decision changes.

## 1. What Rflow is on a phone

On Windows, Rflow is one app for working in a language that isn't your own: it translates what you hear and read, types
what you say, and writes up your meetings. The phone keeps the same idea, shaped by how a phone is used:

- **You type with your thumbs all day**, so voice typing lives in a keyboard.
- **You meet people face to face**, so translating a conversation in the room matters more than on a laptop.
- **You read Japanese apps, menus and messages**, so translating the screen and any text is one tap away.
- **You join calls on the laptop**, so online meetings stay with Rflow for Windows (Android doesn't allow it, see 3).

The decisions so far (the owner's, 2026-11-09):

| | Decision |
|---|---|
| Repository | `karthi-ai-engineer/rflow-android`, public, MIT; the Rflow name and logo not covered |
| Android | 10 and newer (API 29+), the first version that lets an app hear what the phone plays |
| Focus | **Live translation and Meeting mode first** (the owner's correction, 2026-11-09); the keyboard, Translate and the Screen translator come later |
| Releases | An APK on GitHub Releases first; the Play Store once it's solid |
| AI | The user's own Google Gemini key, as on Windows; no Rflow server, no account |

## 2. Each feature on Android

The first versions are **live translation (2.3, 2.5) and Meeting mode (2.6)**. The other parts are planned here so the
design fits them, but they come later.

### 2.1 Rflow Keyboard: voice typing in every app (later)

The desktop's Ctrl+Win becomes a keyboard (an Android *input method*). It is the only way an app may type into other
apps without the Accessibility API (see 4).

- **The keys:** a normal keyboard (QWERTY, with the phone's language switch) so people can keep Rflow as their only
  keyboard. A big **mic key**: hold and speak, let go and the text is typed; or tap to start and tap to stop for long
  dictation. Japanese input (kana to kanji) is a large job: at first, the globe key switches to the phone's own Japanese
  keyboard.
- **What happens:** the microphone records (16 kHz, as on Windows) → speech to text → the same cleanup as the desktop
  (fillers removed, the user's words, numbers, names kept) → typed at the cursor with `InputConnection.commitText`. The
  pipeline's rule stays: when unsure, keep the user's words; never type a text with a hole in it.
- **Speech models:** Gemini 3.5 Transcribe (cloud, the default, nothing to download) or Parakeet on the phone (an
  optional ~640 MB download, through sherpa-onnx, which runs on Android; the voice then never leaves the phone).
  Cleanup with Gemini 3.5 Flash-Lite, as the Recommended setup on Windows.
- **The pill:** the strip above the keys shows the waveform while you speak and "Writing…" while the text is cleaned
  up, like the desktop's pill.
- **Rflow as the phone's speech service:** Android lets one app answer every "tap the mic" button in other apps
  (`RecognitionService`). Rflow offers to be it, so Google Search, Maps and others use Rflow's recognition too.
- **Voice commands on the keyboard:** select text in the field, hold the mic and say "concise", "Japanese Teams
  message"... the same one-word commands as 2.8.1. The keyboard can read and replace the field's text, so this needs no
  extra permission.

### 2.2 Translate and Text Transform: any text, in any app (later)

- **Select text in any app → the menu shows "Rflow".** Android's text-selection menu lets apps add actions
  (`ACTION_PROCESS_TEXT`). Rflow adds *Translate*, and *Rewrite* (concise, professional, Japanese Teams message,
  Japanese email...). A small sheet shows the result with Copy, Replace (where the app allows editing) and the language
  pickers, like the desktop's popup.
- **Shared to Rflow:** text shared from any app (the Share button) opens the same sheet.
- **The app's own Translate tab:** paste or type, detect, translate, swap, as on the desktop's Text tab.

### 2.3 Live translation in the room, and Conversation (first)

The phone's biggest edge: in a meeting room, at a counter, with a colleague who speaks only Japanese. The live translation
screen is the desktop's Live translation page on a phone: the languages, Start and Stop, the captions growing as people
speak, Speak the translation, the Cost saver. Conversation is its two-way view:

- **One screen, split in two:** the top half upside down for the person across the table. Each side shows what the other
  said, in their own language.
- **Hands-free:** Gemini Live Translate both ways, as the desktop's two-way live translation; a button per side when
  the room is loud or the languages are hard to tell apart. (Phase 4 starts by testing how well one microphone and
  two languages work.)
- **Spoken translation:** each translation read aloud in Gemini's voice (as 2.8.2's "Speak the translation", Gemini's
  own voice only). Off: silent captions.
- **Saved:** the conversation kept on the phone as a transcript, if the user wants (the desktop's "Save transcripts").

### 2.4 Screen translator (later)

- **Start:** a Quick Settings tile ("Translate screen"), or a small floating button over other apps.
- **How it works:** Android asks once per session to allow screen capture (`MediaProjection`; Android requires this
  prompt). Rflow takes one picture, reads the text (on-device OCR, ML Kit's Japanese model), translates it with Gemini
  3.5 Flash-Lite, and draws the English over each line in the same place and colours, in an overlay above the app
  (`SYSTEM_ALERT_WINDOW`, "Display over other apps"), as the desktop's stickers. Tap to dismiss; drag a frame to translate
  only a part.
- **Privacy:** the picture is never saved; only the text is sent to Gemini.

### 2.5 Live translation of what the phone plays (first)

- What the phone plays (YouTube, a lecture, a podcast) is heard by Rflow (`AudioPlaybackCapture`, Android 10+) and
  translated live by Gemini Live Translate into a floating caption bar above any app, moved and resized with a finger.
- Apps can forbid this; some do (often paid streaming). Rflow says so when the sound is silent.
- The same Cost saver as the desktop: silence isn't sent, and it stops by itself when nothing is heard for a while.

### 2.6 Meeting mode (first)

- Start in the app, or from the notification: the microphone records the room, with live translation as in 2.3 and
  Speak the translation if wanted. The phone on the table hears everyone; it also hears a call playing from a laptop's
  speakers in the same room.
- After: the same minutes as the desktop (who said what, summary, decisions, action items with owners and dates) by
  Gemini 3.6 Flash, from the recording kept on the phone.
- **Online meetings on the phone are not possible** (see 3); the app says to use Rflow for Windows for them.

### 2.7 Later

- **Wake word:** an always-listening microphone costs battery and needs a permanent notification; worth it only once the
  rest is solid.
- **Japanese typing in Rflow Keyboard** (kana to kanji).
- **Sync with the desktop** (words, snippets, settings): needs accounts, which come later for both apps.

## 3. What Android doesn't allow, and what Rflow does instead

| Wanted | Android's rule | Rflow does |
|---|---|---|
| Translate a Teams, Zoom or Meet call on the phone | Call audio (voice communication) is given to other apps as silence; only system apps may capture it | Online meetings stay on Rflow for Windows. In-person meetings and conversations work on the phone |
| Record phone calls | Not allowed for apps, by Android and by Play policy | Not offered |
| Type into other apps without a keyboard | Only an input method or the Accessibility API may | Rflow Keyboard (no Accessibility API, see 4) |
| Read the screen silently in the background | Screen capture needs the user's consent each session | One prompt per session, then one tap per translation |
| Listen in the background without a sign | A microphone in the background needs a visible notification (foreground service) | A notification while Conversation, captions or a meeting runs |
| Hear what a video app plays | Allowed from Android 10, unless the app opts out | Captions where allowed; a clear message where not |

## 4. How it is built

- **Kotlin and Jetpack Compose,** native. The keyboard, the overlays, screen capture, foreground services and the
  speech service are all native Android; a cross-platform framework would need all of them written natively anyway.
- **No Accessibility API.** Google Play allows it only for apps made for people with disabilities; everything here
  works without it (keyboard, text-selection menu, overlays, screen capture with consent).
- **Modules** (Gradle):

  | Module | Job |
  |---|---|
  | `app` | the app: Home, Translate, Conversation, Meetings, Settings; Compose UI in Rflow's look (Obsidian Signal, dark and light) |
  | `keyboard` | the input method and the speech service |
  | `core` | settings, the key (Keystore), languages, prompts, costs, logging |
  | `ai` | Gemini: text (REST), Transcribe, Live Translate (websocket), the voice played back |
  | `speech` | microphone, playback capture, Parakeet through sherpa-onnx (optional download) |
  | `screen` | screen capture, OCR, the overlay |

- **Gemini, as on the desktop:** the same models and endpoints (`gemini-3.5-transcribe`, `gemini-3.5-flash-lite`,
  `gemini-3.6-flash`, `gemini-3.5-live-translate-preview` over the Live websocket). When Google renames or reprices a
  model, the desktop and the phone change together.
- **Shared with the desktop:** the prompts (translate, cleanup, transform, minutes), the language list, the voice
  commands and the cost table are copied from rflow-ai (`sst/translate.py`, `sst/transform.py`, `sst/commands.py`,
  `sst/costs.py`) into `core`, with a note of the desktop commit they came from. A check in CI compares them with the
  desktop's latest, so neither drifts without anyone noticing.
- **The key:** typed or pasted once, encrypted with the Android Keystore (the phone's DPAPI), never logged, never
  backed up to the cloud (excluded from Android's auto backup).
- **Costs:** the same usage card as the desktop's Home (what was spent with Gemini this month), from the same rates.
- **Tests:** unit tests for every stage (as the desktop's pipeline tests), Compose UI tests, and fakes for the
  microphone, Gemini and the clipboard. CI on GitHub Actions: lint, tests, a debug APK on every pull request.
- **Releases:** a signed APK (`Rflow.apk`, with its SHA-256) on GitHub Releases for each `vX.Y.Z` tag; the app checks
  for a newer release and offers it, as the desktop does. The signing key never goes into git.

## 5. Phases

Each phase is an issue, a branch and a pull request (smaller parts stacked on it), tried on a real phone before it
counts as done.

| # | Phase | Done when |
|---|---|---|
| 0 | **Plan** (this document), README, CLAUDE.md, HANDOFF.md | The owner agrees with the plan |
| 1 | **Setup:** Gradle project, the modules, CI (lint, tests, APK), Rflow's look in Compose, Settings, the Gemini key, a "Test key" button | The APK installs; a key is saved and tested |
| 2 | **Live translation:** the microphone, Gemini Live Translate, the captions screen, languages, Cost saver, a foreground service with its notification | Japanese said in the room is read in English on the phone as it's spoken |
| 3 | **Speak the translation:** Gemini's voice played as it streams, other sound lowered | The same, heard in English through the speaker or earphones |
| 4 | **What the phone plays:** playback capture (videos, podcasts), the floating caption bar over other apps | A Japanese YouTube video captioned in English |
| 5 | **Meeting mode:** record the room, live translation during it, the meetings list, the minutes after (Gemini 3.6 Flash), share them | A room meeting gives the same minutes as on Windows |
| 6 | **Conversation:** the two-way split screen | A Japanese ⇄ English conversation across a table |
| 7 | **Release 1.0:** updates from GitHub, the website's Android section | `Rflow.apk` on GitHub; the site links it |
| later | Rflow Keyboard, Translate and Rewrite, the Screen translator, Parakeet on the phone, the wake word, the Play Store | |

## 6. Open questions

- **The test phone(s):** model and Android version the owner tests on (and a cheaper phone, to check speed).
- **Play Store:** when, and under whose developer account ($25 once).
- **The keyboard's Japanese input:** the phone's own keyboard via the globe key at first; build our own later or not.
- **Free tier:** Gemini's free quota may cover light use; say so in the app, as the desktop plans to.
