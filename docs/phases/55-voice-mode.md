# Phase 55 — Voice Mode

Status (2026-11-21): **built; compiled, lint-clean, JVM/Robolectric-tested (setting, routing, flecks, text); not run on a device or emulator (none here), so the speech recognizer, permission prompt and the spring are unseen.** The iOS side is `peel`'s `docs/voice-mode.md`.

## What it is

An alternative first screen behind a setting (You › Account › Voice Mode, default off): a mic in the middle of a linen canvas, a small
"Type a message…" capsule at the bottom that springs up into a multi-line field with a send button, and a small grid button at the top
right that turns the setting off (the standard view comes back; turning it on again, from here or from You, reopens this screen at
once — `voice-mode-fixes` branch).

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
- 2026-11-21 — Agent turn-state awareness, the narrow slice Android can actually build: `ControlPlan.pendingApproval(nowSeconds)`
  (`engine/AgentChat.kt`) finds the first open, unanswered, not-yet-expired `AgentRequest` with `asks == true` in the agent's
  DM — the same thing `AgentRequestLine` already renders as "Waiting for your approval on your Mac" in the standard view.
  Wired into `VoiceModeScreen`: polled every 5 s (`ConversationScreen`'s own pattern, since a request can pass its expiry
  with no new message ever arriving) and refreshed on any `engine.incoming` row in the agent's chat; its label — unlocalized,
  as `AgentRequestLine`'s own string already is, since engine/control text keeps its English — shows in the status area
  above the ordinary phase label, below `notice` and the muted banner.
  **Deliberately not built**: iOS's `AppModel.agentUnanswered` (the more general "my last sent message anywhere in this
  chat has no reply yet", used so a *fresh* Voice Mode session opened mid-turn starts in `Waiting` instead of `Listening`).
  Android has no `AgentDM.unanswered`/bridge-`replyUUID`-matching port at all — that's a separate, real port of its own,
  not a small extension of this one. Today Voice Mode only knows about a turn it itself started (via its own phase
  machine); a turn started from the standard view, with Voice Mode opened afterward, shows no "Thinking…" until *this*
  screen sends something. The read-only approval card above is real progress on "say something instead of sitting
  silent," but it is not full turn-state parity.
  JVM-tested: `ControlPlanTest` gained 4 cases (`pendingApproval` finds the right request; ignores one that does not ask,
  is already answered, or is expired; is null with nothing pending and skips a quiet line to the next request).
- 2026-11-21 — `EchoFilter` (`engine/EchoFilter.kt`), ported from peel's `Sources/PeelUI/Voice/EchoFilter.swift`: a
  transcript is an echo when a run of three of its words (the whole thing if shorter) is a contiguous run of the text
  being spoken, or spoken in the last 3 s; it still guards for 1.5 s after speech ends (the room's own ring). Pure, a
  clock parameter (ms since epoch here, `Date`/`TimeInterval` on iOS). JVM-tested (`EchoFilterTest`, ported from
  `EchoFilterTests.swift`, all 10 cases).
  **What this actually guards on Android, and why it's narrower than iOS's**: `SpeechRecognizer` is a closed API — the
  app gets no `AudioRecord` session of its own to attach `AcousticEchoCanceler` to the way iOS's `.voiceChat` audio-session
  mode gives "best effort" echo cancellation for free, and the recognizer never runs while TTS is speaking in the first
  place (no continuous listening at all on Android, see the state-machine item above), so the classic "the mic hears
  Peel's own reply mid-sentence and transcribes it as if the user barged in" scenario iOS has to actively filter for
  essentially cannot happen here by construction. The one real, narrow risk this filter catches: tapping the mic to
  interrupt a reply calls `tts.stop()` and starts a fresh one-shot recognition right away, and there is no guarantee the
  speaker has gone fully silent by the time that capture begins (`TextToSpeech.stop()`'s own audio-buffer-flush latency,
  not something app code can observe or wait on precisely). `echoFilter.isEcho(text, now)` is checked on every recognizer
  result and, if it matches, the result is discarded exactly like "heard nothing" rather than sent or parsed as a command.
  **Real barge-in** — saying "peel stop" out loud while the reply is still playing, with no tap at all — is not possible
  on Android's `SpeechRecognizer` without a continuously-running, custom `AudioRecord`-based recognizer bypassing the
  system API entirely; that is a materially bigger, separate piece of work, not an extension of this one.

## What the reply actually is (`voice-mode-fixes` branch)

Peel's iOS side made a pass fixing several things across the modes once it actually ran (commit `dfa01f4`, "make every
mode work, grid turns it off"). Checked each one against Android; four applied here too, three didn't need to:

- **Replies missed, and only the first of several shown.** `VoiceModeReply.text(row, agentPubkey)` only ever looked at
  one row from a single `engine.incoming` emission -- a transient, non-replaying `SharedFlow`
  (`Engine.kt`'s `incomingFlow`). Anything already sitting in storage before this screen started collecting (the app
  was closed, or Voice Mode simply wasn't the open screen) was never seen, and if the agent sent two messages in the
  same batch, `rows.firstNotNullOfOrNull { ... }` kept only the first and silently dropped the rest.
  `VoiceModeReply.latest(messages, agentPubkey)` (`engine/VoiceMode.kt`), ported from peel's own `VoiceModeReply.latest`,
  walks a fresh `storage.messages(engine.agentPubkey)` read backward from the newest row, collecting every one of the
  agent's text replies until it hits the person's own last successfully-sent message (a failed send doesn't count as a
  turn boundary); `VoiceModeReply.text(replies)` joins them with a blank line between, same as peel's. `engine.incoming`
  is now only the trigger to re-read storage (the same pattern `pendingApproval` already used), not the data itself.
  This is the same class of bug as iOS's (reply detection tied to a live event stream rather than persisted history),
  reached by a different route: Android never had iOS's single global "open chat" gate in the first place.
  JVM-tested: `VoiceModeReplyTest`, ported from peel's `replyIsWhatTheAgentSaidSinceYourNewestMessage`/
  `replySkipsWhatIsNoTurn`.
- **A second reply cut the first one off instead of waiting.** Speaking a reply re-chunked and re-spoke the *whole*
  joined text from scratch on every change with `QUEUE_FLUSH`, so a reply arriving while an earlier one in the same
  turn was still being read restarted the speech mid-sentence, now retelling the first reply before the second. Fixed
  by tracking which replies (by row id) have already been queued this turn (`spokenReplyIds`) and speaking only the
  unqueued remainder, `QUEUE_ADD`-ed after whatever's already playing rather than flushing, whenever `phase` is already
  `Speaking` (`speak(chunks, flush = ...)`, batched utterance ids so the progress listener still recognises the true
  end of the whole queued sequence). Unlike iOS, which built a separate `readAloud` path specifically for text-in,
  speech-out (its regular voice-mode speaking already serialised replies through its own turn machinery), Android's
  single shared speak-the-reply effect had this gap for *every* speech-out mode, not only that one.
- **The grid icon had no way back.** It set an in-memory `showedStandard` flag for the rest of the launch; the
  `voiceMode` setting itself never changed, so turning Voice Mode back on required relaunching the app. The grid
  button now sets `voicePrefs.enabled = false` directly, and `MainActivity.PeelApp` listens for that setting changing
  from *any* screen (`VoiceModePrefs.onEnabledChanged`, a `SharedPreferences.OnSharedPreferenceChangeListener` bridged
  into Compose state -- Android's nearest equivalent to iOS's `@AppStorage`, which every view observes automatically),
  so re-enabling it from You while already in the standard view reopens Voice Mode at once, not on the next launch.
  `voice_setting_note` and the grid's own accessibility label (renamed `voice_turn_off`, was `voice_standard_view`)
  updated to say so, in English and Spanish. The deep-link/notification override (`showedStandard` forced by a tapped
  link) is untouched -- that's Android already doing better than iOS's own documented gap there, not part of this fix.
  JVM-tested: `onEnabledChanged` fires across separate `VoiceModePrefs` instances on the same SharedPreferences file.
- **Subtitles kept showing the previous reply while idle.** The condition was just `replyText != null`, with nothing
  checking that a reply was actually *being read* right now; once TTS finished and phase settled back to Listening,
  the subtitle band kept showing the last reply's text until the next send. Added `phase == VoicePhase.Speaking` to
  the condition.

Not ported, already fine or structurally not applicable on Android: a separate "spoken replies" setting overriding the
mode's own choice (Android never built a second, conflicting setting -- `VoiceModeInteraction.speaksReply` is the only
thing that decides); the reply panel only showing in mic-less modes (Android's own condition was already keyed on
`!interaction.speaksReply`, not `!interaction.usesMic`, so both text-out modes already worked); the mode picker's
emoji labels being translatable strings (Android's `glyphs()` was already a plain Kotlin string, never in the catalog).

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

