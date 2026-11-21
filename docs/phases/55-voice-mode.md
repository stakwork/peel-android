# Phase 55 — Voice Mode

Status (2026-11-21): **built; compiled, lint-clean, JVM/Robolectric-tested (setting, routing, flecks, text); not run on a device or emulator (none here), so the speech recognizer, permission prompt and the spring are unseen.** The iOS side is `peel`'s `docs/voice-mode.md`.

## What it is

An alternative first screen behind a setting (You › Account › Voice Mode, default off): a mic in the middle of a linen canvas, a small
"Type a message…" capsule at the bottom that springs up into a multi-line field with a send button, and a small grid button at the top
right that opens the standard view for the rest of the launch (the setting stays on).

## Decided

- The setting is `voiceMode` in its own SharedPreferences file (`peel.voicemode`), like `TalkVoice`'s: device-local, not synced, not per account. `VoiceModePrefs`.
- Routing is `VoiceModeRoute.initial(voiceModeOn, showedStandard)` in `MainActivity.PeelApp`, only once unlocked (lock/onboarding first).
  `showedStandard` is `rememberSaveable` (survives rotation, not a new launch). A tapped link or notification (`deepLink`) goes to the tabs.
- What is said or typed goes to the agent's DM through `Engine.sendMessage(engine.agentPubkey, …)`, the composer's own call. No agent contact → says so.
  The reply is read in the standard view (not shown in Voice Mode; open question).
- Speech is `SpeechRecognizer` (`ACTION_RECOGNIZE_SPEECH`, free-form), `RECORD_AUDIO` asked on the first tap, `<queries>` for `RecognitionService`.
  Recognition may use the device's recogniser service (on-device or the vendor's cloud): the intent sets `EXTRA_PREFER_OFFLINE = true`, so the system prefers its on-device model. If none is installed the platform may still fall back to the vendor's cloud (Android's behaviour, not ours); no Whisper or other local model is bundled.
- The linen is drawn in `Canvas` (`LinenBackground`); flecks come from `Linen.flecks(seed)` (pure, fixed seed).

## Journal

- 2026-11-21 — built. Files: `engine/VoiceMode.kt`, `ui/voice/VoiceModeScreen.kt`, `strings_voice.xml` (en, es), `VoiceModeTest`.
  `:app:testDebugUnitTest --tests VoiceModeTest/LocalizationTest` and `:app:lintDebug` pass.

## Interaction modes

Four modes (`VoiceModeInteraction`, key `voiceModeInteraction`, default speech in / speech out), picked with a row of chips under the
top bar (🎙/⌨ in → 🔊/📄 out, each with a TalkBack label) or in You › Account:

- **Speech in** (`usesMic`): the mic and its rings show; otherwise they fade out and only the type box takes input.
- **Speech out** (`speaksReply`): the agent's reply is read aloud with Android's `TextToSpeech`, made once per screen and shut down with it
  (flushed by a new send, a mic tap or a mode change to text-out). **Subtitles** (`voiceModeSubtitles`, default off, shown in You only for a
  speech-out mode) show the spoken words in a card above the type box.
- **Text out**: the reply is shown in a card (with "Waiting for reply…" after a send).
- The reply is detected from `Engine.incoming` (the feed `MessageNotifier` reads): an undeleted, non-control text message from the agent's DM
  (`VoiceModeReply.text`, pure). Messages that arrive while Voice Mode is not open are not read out; the history is in the standard view.
- Not run on a device: TTS voices/data and the chips' look are unseen.

