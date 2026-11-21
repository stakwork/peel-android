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
- 2026-11-21 — `SpokenText` (`engine/SpokenText.kt`), ported from peel's `Sources/PeelUI/Voice/SpokenText.swift`: the reply is
  cleaned (code fences, tables, links, bold/italic/strike, HTML, emoji, UUIDs/hashes/paths all collapsed to a short phrase) and
  split into sentence-sized chunks before `TextToSpeech.speak`, queued one after another (`QUEUE_FLUSH` then `QUEUE_ADD`), instead
  of reading the raw markdown. A reply over 600 characters / 8 sentences is cut at the last whole paragraph (or sentence) that
  fits and ends with "That is the short version. Say read it all for the rest." — the full text has nowhere to go yet ("read it
  all" is a voice command, not built here; see the mute/commands item below). JVM-tested (`SpokenTextTest`, ported from
  `SpokenTextTests.swift`, all cases).
- 2026-11-21 — `VoiceMachine` (`engine/VoiceMachine.kt`), ported from peel's `VoiceModeState.swift`: the same pure reducer
  (`VoicePhase` idle/listening/hearing/sending/waiting/speaking, `VoiceEvent`, `VoiceEffect`, mute as a flag over the phase,
  the full `VoiceCommand` vocabulary) in place of the screen's two ad hoc booleans (`listening`, `waiting`). `VoiceModeScreen`
  now drives the mic, `Engine.sendMessage`, and the TTS lifecycle (`SpeechStarted`/`SpeechEnded` via an `UtteranceProgressListener`
  on the last queued chunk) through `machine.apply(...)`, and shows "Sending…"/"Thinking…"/"Speaking…" from the phase for every
  interaction mode, not only the ones with a mic.
  **A real platform gap, not papered over**: Android's `SpeechRecognizer` (`ACTION_RECOGNIZE_SPEECH`) is one-shot — it captures
  one utterance and ends on its own, unlike iOS's continuously-running `AVAudioEngine` tap. There is no live, editable draft
  spanning several pauses yet, and no way to "keep talking" after a failed send; the one-shot result is treated as an implicit
  `Silence` event (auto-send), and wherever the reducer would otherwise settle back into `Listening` with no live capture
  underneath it (a failed send, a reply with nothing to speak, the end of TTS), the screen explicitly follows with `Stop` to
  reach `Idle`, so the next mic tap can fire `Start` again. Reaching true continuous listening (and a real auto-send-after-
  silence setting, since Android's recognizer behaves as if it were always on) is a bigger, separate piece of work.
  One upside from the same change: tapping the mic mid-send or mid-wait no longer starts a second recognition session on top
  of the first (a gap in the old booleans, now blocked by the phase's `isActive` check).
  `VoiceCommand`/`VoiceContext` exist as the reducer's vocabulary only — no phrase parser yet (`VoiceCommandParser`, the next
  item) and no mute/command behavior wired into the screen; `VoiceEvent.Command(SEND)` is fired directly by code today (the
  type box's send button, and the recognizer's implicit end-of-utterance), not by a recognized phrase.
  JVM-tested (`VoiceMachineTest`, ported from `VoiceModeStateTests.swift`, excluding the `VoiceSettings`/`VoiceDefaults` cases,
  which belong to the auto-send item).
- 2026-11-21 — `VoiceCommandParser` (`engine/VoiceCommandParser.kt`), ported from peel's `VoiceCommand.swift`: the English
  phrase table (`VoiceCommandPhrases.ENGLISH`), the wake word "peel" and its mishearings ("pill", "peal", "feel"), longest-
  phrase-wins matching, and `VoiceContext.accepts` (which commands count in which context). JVM-tested
  (`VoiceCommandParserTest`, ported from `VoiceCommandParserTests.swift`, all cases).
  Wired into the screen: the one-shot recognizer's final text is parsed (always in the `DICTATING` context — Android has no
  live mic while speaking to hear a barge-in command, and no wake listener armed outside a chat, so that is the only
  context it ever reaches) before falling back to plain dictation. Acted on today: **send** (explicit "peel send" or
  auto-send on the one-shot result, same as before), **discard**, **mute**/**unmute**, **deactivate** (now closes to the
  standard view, like the grid button), **repeat that** and **read it all** (re-speak the last reply, or its untruncated
  `SpokenText.full`). Recognized but not yet acted on: **stop speaking** and **skip** (need a live mic while TTS plays —
  barge-in, the next item), and **stop the agent** (no `stopAgentTurn`-equivalent engine hook on Android yet).
  Mute is a tappable button too (iOS's panel has one "as the tappable alternative"), not only a spoken command: it dims the
  mic, blocks a mic tap until unmuted, and shows "Muted — tap Unmute to continue" (highest priority over the phase/notice
  text, since it is a standing choice, not a passing status). Typed sends are unaffected by mute — mute is specifically
  about microphone input, as on iOS ("muting is about the user's microphone input: spoken replies still play").
  The screen no longer forces a `Stop` event to settle "no live recognizer right now" (commit 7d35acd's approach): `Stop`
  is "voice mode ended" in the reducer and also clears `isMuted`, so doing that between every one-shot utterance would
  silently un-mute a muted person the moment anything else happened (a reply arriving, TTS finishing). The phases instead
  settle naturally wherever the reducer's own transitions put them (`Listening` at rest, `Hearing` with a kept draft after
  a failed send), and a plain `recognizing` flag (not part of the ported machine, since it is Android's own one-shot-
  session detail) tracks whether the recognizer is literally capturing right now, for the mic's stop-icon/pulsing-rings.

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

