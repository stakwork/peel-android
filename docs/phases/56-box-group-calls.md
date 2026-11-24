# Phase 56 — Jamie from a box in a group call

Status (2026-11-24): **built; JVM/Robolectric tests pass; the box's routes, the tickets and the encrypted room run live on an
emulator against a local strut + voice service + LiveKit (see "Checked"); the call menu and posting the call not run on a device.**
Android's side of stadeum `plans/group-calls.md` work item 3 and `plans/call-canvas.md` work item 6. Strut's side is
stakwork/strut#881 (merged: `GET /voice/calls`, `POST /voice/calls/ticket`, `POST /voice/calls` forwarded to the box's voice
service as the person); the voice service's is stakwork/sphinx-voice#45–#47 (open). iOS/Mac is a parallel session in `peel`.

## What it is

A self-hosted box has no Hive. Its own `voice` service joins a Peel call, **end-to-end encrypted** with the call's key, and hears
and speaks with the box's own models. From a chat's call menu the person picks one of their linked boxes (docs/phases/45-box-link.md)
as the call's Jamie, optionally **Record on the canvas**, and starts an audio or video call.

Android never had Hive's orgs in the call menu (`HiveClient.startCall` is kept unused, docs/phases/26-talk-to-jamie.md), so the menu
offers boxes only.

## Built

**The key in the link (`engine/CallLink.kt`):** an encrypted call's link carries its key after `#`:
`https://<call server>/rooms/<room>[?startAudioOnly=true]#<key>`. `parse` used to refuse any fragment; now a fragment is the call's
key (`encryptionKey`, `isEncrypted`), percent-decoded (meet's own links escape it), 1–256 of `[A-Za-z0-9-._~+/=]` (`isKey`, so it
stands in a fragment unescaped); anything else refuses the link; an empty fragment is no key. `new(server, audioOnly, key)` makes
one. `toString` leaves the key out. `CallLink.hive` still refuses a fragment. The key never goes to the call server: `CallServer`'s
ticket request names only the room, the name and the photo.

**The box's routes (`engine/BoxCalls.kt`, `BoxCallsClient`)**, over `BoxHttp` with the person token Peel holds for the box:

- `status()`: `GET /voice/calls` → `BoxCallStatus { group, livekit }`. `404` (a strut before #881) and `503` (no voice service) are
  `NONE`: not offered.
- `join(room, key, record)`: `POST /voice/calls { mode: "group", room, key, record? }` (`record` only when true) → Jamie's identity.
  `409` "Jamie from <box> is already in this call."; `503` "Jamie from <box> can't join a call right now: <its words>."; others in
  strut's words. An answer without `encrypted: true` is refused ("can't join an encrypted call yet: its voice service needs an
  update"), never a call in the clear.
- `ticket(room, name)`: `POST /voice/calls/ticket { room, name }` → `BoxTicket { url, room, token, identity }`; `404` (Jamie from this
  box is not in that call) is null. The identity must be `box_<32 hex>` and the room the one asked.
- A `401` is `BoxTokenRefused`: `BoxesModel.asMember` renews the token once and asks again, as the places routes do.

**Which boxes the menu offers (`BoxCalls`, `Boxes.calls`; Compose state, main thread):** `read` asks every linked box at once
(as the chat opens, at most every 5 minutes; as the call menu opens, at most every minute). `offers(status, callServer)`: `group`
true **and** `livekit` a LiveKit of the call server's, as its own tickets are checked (`CallServer.isMediaServer`: the call server's
own host or the one recorded for it, `livekit.sphinx.chat` for `chat.sphinx.chat`; plain `ws://` only for a loopback call server in a
debug build). A box on another LiveKit would never meet the call's other people.

**The pick (`ui/dashboard/CallJamieItems.kt`):** under Audio call / Video call, when a box is offered: "Jamie", each box by host, "No
Jamie", and with a box, "Record on the canvas" (a checkbox). Choosing keeps the menu open, as iOS's inline picker does. The pick and
the recording are kept per chat while Peel runs, **not stored** (decided here: a box's Jamie hears the whole call, and recording sends
everything said to the box's model, so a call with Jamie is chosen again after a restart, not carried over silently). Another box,
or No Jamie, turns recording off. A pick that is no longer offered is No Jamie.

**Starting (`ConversationScreen.startCall`), §5's order:** with a box picked: refused at once while Talk is on (nothing asked or
posted); a new room (`CallLink.new`) and a fresh key (`TalkHome.newKey`: 32 `SecureRandom` bytes as base64); the box asked in
first ("Bringing Jamie into the call…" where the composer says what it is sending); if it fails, nothing is posted and the box's
words are said where the chat says a failed start; then the link with the key is posted (`Engine.startCall(…, made)`) and joined.

**Joining (`CallController.connect`, `Engine.callTicket(link, fromBox)`):** a link with a key asks every linked box for the person's
own ticket at once (`BoxCalls.ticket`; 10 s each); the first in link order that is in the call answers, and its ticket must pass the
call server's checks (`BoxTicket.callTicket` → `CallServer.ticketOn`: the call server's LiveKit, a token for the room and for the
identity named, not expired). None in it, one that fails, or a ticket that doesn't pass: the call server's ticket, as before (the
person then runs as the starter). A link without a key never tells a box of the call.

**The encrypted room (`ui/call/CallSession.kt`):** `options(key)`: a shared-key `BaseKeyProvider` on the key string (LiveKit keys with
its UTF-8 bytes, as rtc-node and meet do), the microphone without DTX or RED, as Talk's. Once connected and before anything is
published or sent: `e2eeManager.dataChannelEncryptionEnabled = true`, then the room is left at once unless the manager says both the
media and the data channel are encrypted ("Peel couldn't encrypt this call, so it left."). On an encrypted call the in-call chat and
the handoff take only packets that came encrypted. A track that won't encrypt or decrypt is said under the controls
(`CallEncryption`: "…someone can't be heard." before "…the others can't hear you."). Jamie's screen share needs nothing:
`screenTile` already shows it.

**Recording on the canvas:** while a participant named Jamie has the attribute `recording` = `"true"` (`CanvasRecording`), the call
screen says "Jamie is recording this call on the canvas", beside the call server's own "This call is being recorded".

## Found

- **livekit-android 2.29.2 does not encrypt what it sends on the data channel by default.** It decrypts any encrypted packet it
  receives, but encrypts outgoing data packets and text streams only with `E2EEManager.dataChannelEncryptionEnabled` (a `@Beta`
  flag, off by default; nothing in the SDK turns it on). Without it a group call's chat and handoff would cross the server readable
  and the box's voice service would get them in the clear. Group calls set it (above). Talk to Jamie sends nothing on the data
  channel (its voice switch is a participant attribute, which is signaling), so it leaks nothing, but `TalkSession.options`' doc
  comment saying the SDK encrypts the data channel "with the same options as the media" is wrong for sending.
- The box's ticket carries no photo (the voice service mints the token with the name alone), so a person who joined on one shows
  their initial to the others.
- LiveKit's `Encryption.Type` is a protobuf enum whose class is not on the compile classpath (the SDK's protobuf is a runtime
  dependency), so `CallSession` reads it by name.
- **The first encrypted room in a fresh process crashed** (`UnsatisfiedLinkError` in `FrameCryptorFactory`): a `BaseKeyProvider` is
  native, and `LiveKit.create` loads WebRTC's library only after its options (and so the key provider) are built. Found on the
  emulator; `CallSession` and `TalkSession` now call `LiveKit.init` first. Talk had the same crash on its first call since phase 26
  (written without a build, never run on a device).
- **Jamie's chat lines don't show on Android.** They arrive and decrypt (`GCM`), but livekit-android 2.29.2 finds a data packet's
  sender only by `UserPacket.participantSid`, which rtc-node (the box's Jamie, Hive's worker) and livekit-client (meet) leave empty, so
  `DataReceived.participant` is null and the call chat drops the line as from nobody (as it always has, Hive calls included). iOS
  names a line by the payload's `sender` and takes one without it as Jamie's (PeelCore `CallChatMessage.isFromJamie`); Android can't
  copy that safely, since meet's lines carry no `sender` either and would read as Jamie's once meet joins encrypted calls. Left as
  is: a follow-up (a LiveKit version that looks the sender up by identity, or a sender field in the voice service's lines).

## Tests

- `CallLinkTest` (+8): the key read, kept and written back; percent-encoded and empty fragments; what is not a key refuses the link;
  a new encrypted call through the message; `toString` without it; Hive's links still refuse a fragment.
- `CallServerTicketTest` (+2): `isMediaServer` by host and port; `ticketOn` checks as the call server's own tickets are checked.
- `BoxCallsTest` (11, against a stand-in strut answering as #881 forwards the voice service): status parsing and the offer rule; the
  menu's boxes in link order, read throttled, a pick kept only while offered, recording off with another pick; boxes that can't say;
  the join's body as the person; never a clear call; every refusal's words and the missing `encrypted`; a refused token renewed once;
  the box in the call gives the ticket and the others 404; no ticket from a failing box or for a clear call (and no box asked);
  tickets for another room, identity, LiveKit or subject not taken; the ticket's name.
- `CallEncryptionTest` (3), `CallJamieItemsUiTest` (2, Robolectric: nothing without a box that can join; a pick, Record on the
  canvas, No Jamie). The encrypted room's options (`CallSession.options`) can't be built on a plain JVM (`BaseKeyProvider` is
  native): the live run below builds them.

## Checked

Live, 2026-11-24, on an emulator (API 35 AOSP arm64, `-no-audio`: a headless emulator hung on the host microphone) with this
branch's debug build: `androidTest/ui/call/BoxGroupCallLiveTest` (skipped unless a box is named). Against strut `origin/main`
a4ed1c3e (with #881) on :3077, sphinx-voice `feature/group-recording` c7f9068 (#45–#47) on :8088 (`STRUT_VOICE_URL`/`TOKEN`), and
`livekit-server --dev` 1.13.11; strut and LiveKit reached from the emulator as `127.0.0.1` through `adb reverse`, the media over
UDP to the host's LAN address. The call server stood in as the LiveKit itself (`127.0.0.1:7880`), so the tickets' checks take it.
A person token from strut's `POST /peers/tokens` stood in for a linked box's.

    adb shell am instrument -w -e class com.sphinx.peel.ui.call.BoxGroupCallLiveTest \
      -e boxStrut http://127.0.0.1:3077 -e boxToken strut-person.… -e callServer 127.0.0.1:7880 \
      com.sphinx.peel.test/androidx.test.runner.AndroidJUnitRunner

Passed (OK, 1 test):
- `GET /voice/calls` → `{ group: true, livekit: "ws://127.0.0.1:7880" }`, offered by `BoxCalls.offers`.
- `POST /voice/calls { mode: "group", room, key, record: true }` → `{ agent, encrypted: true }`; the same room again → 409, said as
  "Jamie from 127.0.0.1 is already in this call."; a ticket for a room the box is not in → 404 (null).
- The posted message read back with its key; two tickets (Ada, Bob), each `box_<32 hex>`, through `BoxTicket.callTicket`.
- Two `CallSession`s joined encrypted: `encryptionOn()` true for both (no notice), LiveKit's frame cryptors `OK` for both senders and
  every receiver (Jamie's track included); Jamie's tile; Jamie's `recording` attribute seen by both (`recordedOnCanvas`).
- Ada's typed line reached Bob, who takes only encrypted packets, and the voice service logged it ("text message from Ada: \"Hello
  from Android\""): Android's outgoing data encryption interoperates with rtc-node.
- An rtc-node client on the host, in a box's room the same way, got Jamie's notice ("Jamie is taking notes on the canvas."); on
  Android it arrives but has no sender (Found).

Not checked live: the call menu and the start flow on a device (needs a Sphinx account with a server connection and a linked box;
the menu is covered by `CallJamieItemsUiTest`), joining from a posted card (`Engine.callTicket(fromBox)`; JVM-tested), a real call
server's tickets beside a box's, audio both ways (no microphone in the emulator; Jamie did not speak), the recording's screen share,
and meet.

## Open

- **Jamie's chat lines** on Android (Found).
- **Captions** (optional in the brief): group call screens show none. The voice service sends them on encrypted `lk.transcription`
  text streams, the speaker by `lk.transcribed_track_id` (not the stream's sender, which is Jamie). `TalkTranscript` already folds
  those streams; a group version needs the track → participant map and a place on the call screen.
- Hive's orgs in the call menu (iOS has them) remain unported.
- meet joins an encrypted call only once it reads the key with `encryption` (group-calls.md work item 4); until then a meet
  participant hears nothing of a box's call.
