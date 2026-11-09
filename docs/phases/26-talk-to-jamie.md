# Phase 26 — Talk to Jamie

Status: written **without a build**: not compiled, not run, not device-tested, not tried against a real home or voice agent
(the pod had no JDK, Android SDK or device). Ported from `peel`'s `docs/talk.md`, `Sources/PeelCore/TalkCall.swift`,
`Sources/PeelUI/TalkStore.swift`/`TalkWave.swift`, `Apps/Shared/TalkSession.swift`/`TalkView.swift`. Read against LiveKit
Android **2.29.2** (the version in `gradle/libs.versions.toml`), whose sources were checked on GitHub, not compiled against.

## Built
- **`engine/Talk.kt`** (pure, JVM-tested in `TalkTest`; HTTP behind `TalkHttp`, like `HiveHttp`):
  - `TalkHome(id, name, url, token)`: `TalkHome.of(HivePeer)` = the peer's strut + its Stadeum token; null for an address that is
    not https (http only to loopback, no user/query/fragment) or an empty token. `toString` hides the token. `DEVELOPMENT` =
    `"@development"`; `Stadeum.developmentHome` stays null. `startCall(voice)`: `POST {home}/voice/calls`, `Authorization: Bearer`,
    `{"key","voice"?,"artifacts":true}`, 30 s, reply capped at 64 KiB (`OkHttpTalk`, no redirects).
  - `TalkHome.newKey()`: 32 `SecureRandom` bytes, base64, fresh per call, never stored or logged (`TalkTicket.toString` hides it).
  - `TalkTicket.parse`: `wss://` (https→wss, http→ws; ws only on loopback; ≤ 2048 bytes; no user/query/fragment), `encrypted`
    must be `true` (else "can't make an encrypted call yet"), room/identity/agent pass `CallServer.isIdentity`, the JWT's
    `video.room` (`CallServer.parseClaims`) must equal `room`, unknown voice → null. **No** call-server media-host rule.
  - `TalkRefusal` (`BalanceTooLow` for 402 `{balance, minimum}`, `Refused` otherwise) in the home's words, `TalkVoice`
    (female `af_heart` default / male `am_michael`, kept per device in `SharedPreferences` `peel.talk` / `talkVoice`, glyph ♀/♂ +
    U+FE0E, "Jamie's voice: female|male"), `TalkPhase.of(connection, connectedOnce, agentState, agentSeen)` (the `lk.agent.state`
    fold), `TalkEncryption` (failing tracks → the two notices), `TalkTranscript` (fold of `lk.transcription` streams, own-line
    detection, 200-line cap), `TalkArtifacts` (`sphinx.artifacts` payloads: seen ids ignored by the session, 20 per stream, 30
    cards), `TalkCardAnchor` (card under the reply it came with).
  - **`engine/TalkWave.kt`**: bar heights per mode and a `LevelSmoother` for the waveform (pure).
- **`ui/talk/`**: `TalkSession` (the LiveKit room: end-to-end encryption, phase, transcript, cards, mic, voice attribute),
  `TalkStore` (starting / failed / on, voice, hang up), `TalkScreen` (full screen: waveform circle, status line, transcript with
  cards, Mute, voice toggle, Hang up; Try again / Close once over).
- **`MainTabs`**: `talkToJamie` now starts `TalkStore.talk` (the old `HiveClient.startCall`/`CallLink` path and its coming-soon /
  "connect Stadeum" toasts are gone). One call at a time: Talk refuses while a group call is on ("Leave the call in X first, then
  talk to Jamie.") and `request` (join/start a group call) refuses while Talk is on ("Hang up on Jamie first, then join the
  call."). The microphone is asked like the group call's (`askMic`), and the foreground `CallService` is reused (started when the
  room is up, again once the mic is granted; Leave in its notification hangs up). Hanging up leaves the room; tearing `MainTabs`
  down hangs up too.
- `CallServer`: `claimsOf`'s body moved to `CallServer.parseClaims` (companion; `claimsOf` delegates, no behaviour change) and the
  companion's `isIdentity` made `internal`, so Talk reuses both. `TalkHome` moved from `WorkspaceApps.kt` to `Talk.kt` (now with a
  token; `WorkspaceAppsTest` updated).
- `HiveClient.startCall` (Hive's `POST /api/strut-devices/calls`) is **kept, unused** by the app now (nothing in `main` calls it). Delete it with `CallLink.hive` if the strut-device flow is not coming back.

## Assumptions
- Transcript cap is 200 lines, as the task states; iOS `TalkTranscript.maxLines` is 500 (its docs say 200).
- A line is the member's when `lk.transcribed_track_id` is one of the *local participant's publication sids* or the stream's
  sender is the member's identity (the ticket's `identity`).
- The agent participant is the one whose identity equals the ticket's `agent`.
- The attribute `voice` is set with `LocalParticipant.updateAttributes` (merges, as iOS's `set(attributes:)` of the full map does).
- Mid-call switch before the room is connected is remembered and told once connected (`TalkSession.setVoice`).
- Artifact cards: strut's `ArtifactRef` entries are read as `title` (or `label`), `summary`, `url` only; a card is tappable only for
  an absolute `https://` url (opened in the browser, call stays up). Entries with no title are dropped.

## iOS vs Android
- **Encryption options**: iOS must use `encryptionOptions` (the deprecated `e2eeOptions` leaves the data channel in the clear). In
  Android 2.29.2 there is only `RoomOptions.e2eeOptions`, and `Room` passes the `encryptionType` of each data packet / stream
  through the same E2EE manager, so it covers text streams too. `BaseKeyProvider(enableSharedKey = true).setSharedKey(key)` keys
  with `key.toByteArray()` (UTF-8), the same bytes as iOS. Key derivation is the SDK's default (PBKDF2), unchanged. Not verified
  against the sphinx-voice agent on a device.
- `AudioTrackPublishDefaults(dtx = false, red = false)` via `RoomOptions.audioTrackPublishDefaults`.
- **Encryption failures**: the track E2EE state (`RoomEvent.TrackE2EEStateEvent`) is surfaced, with the same two sentences. The
  Android SDK has no "data packet failed to decrypt" callback (iOS `didFailToDecryptData`), so a text-only failure is not said.
- **Waveform**: iOS meters the audio buffers (30 fps, loudest RMS); Android uses LiveKit's `Participant.audioLevel` (a few updates a
  second, speaker-level), smoothed (`LevelSmoother`) and wobbled per bar. No Reduce Motion variant and no per-bar easing.
- **Artifacts**: no `AgentArtifact`/handle model on Android, so no in-app page, no handle resolution against the home: title,
  summary and (https only) a link. Cards are anchored as iOS (`TalkCardAnchor`).
- No Talk history store (iOS `TalkHistoryStore`), no Mac window, no dev setting (`-talkHome`), no "talk at launch", no waiting for
  group calls to say goodbye (Talk refuses while one is on, so there is nothing to wait for), no audio-session handling (LiveKit's
  Android `AudioSwitch` does routing).
- Voice-set failure is not said (the SDK's `updateAttributes` returns nothing).
- Back does not hide the Talk screen; Hang up (or the notification's Leave) ends the call.

## Open
- Run it: a real home, the sphinx-voice agent, a device; check mic permission + foreground service start order on Android 14, the
  `E2EEOptions` shared key against the agent, and how the text stream handlers behave when the agent sends in the clear.
- A sats balance UI (only the 402 message exists), Talk history, an artifact page.

## Backend dependencies
- strut's `POST /voice/calls` on the org's home (the HivePeer `baseUrl`), taking the person's peer token, `{key, voice, artifacts}`,
  answering `{url, room, token, identity, agent, encrypted, voice}`.
- The sphinx-voice agent with the E2EE `key` support (`encrypted: true`) and the `artifacts` capability (`sphinx.artifacts`).
- `HivePeer.token` from Android's Stadeum pairing (`StadeumModel`) must be live; Android has no development home.

## Journal
- Written from the iOS sources (read in the sibling checkout) and the LiveKit Android 2.29.2 sources; nothing compiled or run. Tests
  (`TalkTest`) use Robolectric where `org.json` or `SharedPreferences` is touched, as `TribeFormTest` does.
