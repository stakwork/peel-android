# Phase 12 — Calls

## Status (2026-10-30)

**Built, scoped deliberately:** call messages and presence, nothing
more. A chat shows a real "Audio call"/"Video call" card (not the raw
`call::{...}` JSON a call message's body carries, which is what showed
before this phase — Phase 5's own "Open" item: "nothing here reads or
writes any other type... those rows show as empty text today"); a
phone icon in the conversation header starts one; the call server's
presence feed drives a live "N here now" line on the card. There is no
in-app call screen at all — "Join" and "Start a call" both hand the
link off to whatever else on the device can open it (a browser, or the
real Sphinx app if installed), the same way any other `https://` link
would be opened from outside Peel.

This was an explicit scope choice, not a default: the alternative was
pulling in the LiveKit Android SDK and building a real in-call UI
(mute/camera/leave) this round. Decided against it for two reasons —
it's by far the largest single piece of what PeelCore's own Calls
feature is (`CallStore.swift`, `IOSCallSession.swift`,
`IOSCallViews.swift`, screen share, in-call chat — genuinely its own
phase-sized feature on top of this one), and no device has been
connected to this project all session (`adb devices -l` empty
throughout), so a live audio/video path couldn't be verified beyond
"it compiles" even once built. Messages and presence are real,
reachable, testable-without-a-device value on their own.

**Call messages.** `CallLink` (`engine/CallLink.kt`) is a close port of
PeelCore's `Call.swift` `CallLink`: anyone can write a call message
(it's just type 32, or even plain text containing
`sphinx.call.`), so a link is only ever one that passed every check
against this account's own call server — exact scheme/host/port, no
credentials or fragment, a strict room-name charset, only known query
keys, the canonical link always rebuilt from parsed parts rather than
the raw text echoed back. `CallLink.hive` (Stadeum's own call-with-
Jamie flow) is not ported — this account has no Hive/Workspaces
feature to need it from.

**Presence.** `CallServer` (`engine/CallServer.kt`) is a close port of
PeelCore's `CallServer` actor's presence half (the ticket-request half,
`GET /api/connection-details`, isn't ported — nothing needs a LiveKit
token without an in-app join): one WebSocket to `/ws`, subscribe per
room, ref-counted so the socket opens on the first watcher and closes
with the last, reconnects with 1/2/4/8/16/30-second backoff, parses
`current_participants`/`participant_joined`/`participant_left`/
`room_finished`. Built on OkHttp (a new, explicit dependency — already
on the classpath transitively through Coil's own `coil-network-okhttp`,
at the exact version it already resolves to, not a second one) rather
than hand-rolling frame handling: OkHttp's own `pingInterval` does the
keep-alive and dead-connection detection PeelCore's own version needed
a manual 30-second ping loop for, in one line.

**Open:**

- No in-app join — the LiveKit SDK, a real audio/video room UI
  (mute/camera/leave), is the single largest remaining piece. Starting
  or joining a call still works for everyone *else* on it; this
  device's own user leaves Peel to actually be on one.
- No screen share, no in-call chat (`CallChat.swift`'s own side-panel
  messages during a call) — both are in-app-join features, same reason.
- No Hive-originated calls (Stadeum's "call with Jamie") — no Hive/
  Workspaces integration exists on this port at all yet.
- No sidebar "live call" indicator (PeelCore's own `WatchedCall`,
  the green phone) — that needs watching several rooms' presence at
  once across the whole chat list, not just whichever call messages
  are visible in an open chat; a real, separable piece of its own.
- No "Started a call" notification — this port has no push
  notifications of any kind yet (a gap named well before this phase).
- No recency gating anywhere (PeelCore's own `CallLink.isRecent`/
  `liveAge`, 12 hours): a call card from a week-old message still
  offers Join exactly the same as a fresh one. PeelCore's own
  `CallBlock` doesn't gate its own Join button on recency either — only
  the (not-yet-built) sidebar/notification features need it — so
  there's nothing to wire it into yet; not ported ahead of a user.
- `CallServer`'s host comes from `link.server`, which itself always
  equals whatever `Engine.callServer()` returned at the moment the
  link was parsed or made. If the account's call server setting
  changes while a presence collector is still active, that collector
  keeps watching the *old* host until it's re-subscribed (a new
  `MessageRowView` recomposition, or reopening the chat) — matches
  PeelCore's own `callConnection(for:)`, which has the identical
  per-call-not-per-change binding.
- `Engine.startCall`'s own tribe-call pricing (the 3-sat floor, never
  the tribe's real per-message price) is not covered by a test —
  `Engine` has no host build of `sphinxrs` to run against, the same
  gap every other `Engine` send function already has.

## Decided

- Presence is spent on the call card itself ("N here now"), not saved
  for a sidebar indicator that doesn't exist yet — PeelCore's own
  `CallBlock` doesn't show this at all (its presence goes entirely to
  the sidebar/notification features this port hasn't built). Different
  spend of the same real mechanism, not a divergence in what the
  mechanism itself does.
- `CallLink.hive`/Hive-link checks, the ticket/token request half of
  `CallServer`, and any recency gate are all left out rather than
  stubbed — each is either unreachable (no Hive feature to call it)
  or has no consumer yet (no in-app join, no sidebar). Matches this
  project's own standing rule against building for a feature that
  isn't there yet.
- OkHttp over a hand-rolled WebSocket client: already effectively on
  the classpath (Coil's own image-loading dependency), well past the
  point of "a second networking library just for this."
- "Start a call" opens the link externally right after posting the
  message, same as "Join" on a received call does — without that,
  this device's own user would have started a call with no way at all
  to get onto it themselves.

## Journal

**Read PeelCore's Calls feature in full before deciding how to scope
it.** `Call.swift`, `CallServer.swift`, `Engine+Calls.swift`,
`CallStore.swift`, `IOSCallViews.swift` — five files, each covering a
genuinely different concern (link validation, presence, the ticket
request, the actual LiveKit session, the in-call UI). The size wasn't
uniform: link validation and presence are each a few hundred lines of
self-contained, portable logic with no SDK dependency; the LiveKit
session and its UI are where the real size (and the real "needs a
device to verify" cost) lives. Splitting the feature along that seam —
not "half of everything" — is what let this round ship something real
without the part that would have been unverifiable this session.

**Presence is reachable without a device, link validation is
reachable without a server.** Both `CallLinkTest` and `CallServerTest`
test real logic (the exact security checks a malicious call message
has to fail, the exact merge rules a presence update has to follow)
with zero native dependency and zero network — the same shape
`PreferencesFileTest`/`AttachmentTest` already established for this
project: a feature's *infrastructure* code (sockets, SDKs) often
contains a meaningfully-sized, genuinely pure core worth separating
out and testing on its own, rather than writing it inline where only
an instrumented or on-device test could ever reach it.

**OkHttp's `pingInterval` quietly replaced a whole hand-rolled
mechanism.** PeelCore's own `CallServer` actor spends real code on
`keepAlive` (a manual 30-second ping `Task`) and On tracking failures
to back off reconnects — URLSessionWebSocketTask has no built-in
equivalent. OkHttp's `WebSocket` does: one constructor argument, and
both the keep-alive and "this connection is dead, fail it" detection
are the client library's job, not this file's. Worth checking what a
platform's own networking stack already does before porting a
mechanism that exists only to work around what the *other* platform's
stack doesn't.

## Done

- `engine/CallLink.kt` (new): `CallLink` — `parse`, `new`, `linkText`,
  `inMessage`, `url`, `message`, the room/token-charset validators, a
  percent-encoder/decoder that doesn't treat `+` as space (matching
  Swift's `removingPercentEncoding`, unlike `URLDecoder.decode`).
- `engine/CallServer.kt` (new): `CallServer` — the presence WebSocket
  client (`presence(room)`, ref-counted subscribe/unsubscribe,
  reconnect with backoff, OkHttp-backed); `CallParticipant`,
  `CallPresence` (`Unknown`/`Live`/`Ended`); the feed's own parsing
  (`parseUpdate`, `participant`) and merge rules (`applying`), visibility
  relaxed from `private` specifically so they're unit-testable without
  a real socket.
- `engine/Attachment.kt`: `CALL_MESSAGE_TYPE = 32`.
- `storage/Setting.kt`: `CALL_SERVER` (unset on every account so far —
  no settings screen writes it; `Engine.callServer()` falls back to
  the official apps' own `chat.sphinx.chat`).
- `engine/Engine.kt`: `callServer()` (the configured-or-default-or-none
  host, PeelCore's own `configuredHost` logic); `calls` (the one
  `CallServer` connection, remade if the host changes) and
  `callPresence(link)`; `startCall(chat, isTribe, audioOnly)` (a type
  32 message through the same `run`/`coreSend` path every other send
  already uses, the 3-sat tribe floor, never the tribe's real price);
  `cancel()` now also closes `calls`.
- `ui/dashboard/ConversationScreen.kt`: a phone icon in the header
  (shown only when `callServer() != null`) opening a small menu —
  "Start an audio call"/"Start a video call" — that posts the message
  and hands the link off externally; `CallBlock` (the call card: icon,
  label, a live "N here now" line from `callPresence`, "Join");
  `MessageRowView` now detects a call (`CallLink.inMessage`, covering
  both the `call::` form and a bare link in plain text) and renders
  `CallBlock` instead of raw JSON text; `openExternally` (a small
  `Intent.ACTION_VIEW` helper, silently gives up if nothing can open
  it — not something to crash over).
- `app/build.gradle.kts`/`gradle/libs.versions.toml`: `okhttp` added
  as an explicit dependency, pinned to the exact version already
  resolved transitively through `coil-network-okhttp`.
- Tests: `CallLinkTest` (14 cases — a fresh link round-trips, every
  rejection the security checks are meant to catch: wrong host/port/
  scheme, credentials, a fragment, a bad room name, an unknown or
  duplicated query key, a bare key with no value, content over the
  length limit; `linkText`'s bare-vs-wrapped forms; `inMessage`'s
  call-type vs. Mac-interop-bare-link paths); `CallServerTest` (13
  cases — every feed message shape parses, a nameless join is a no-op,
  a renamed rejoin updates in place, a join reopens an ended room, a
  leave removes only that identity, `current_participants` keeps a
  known name for someone who re-arrives unnamed). 110 tests passing.
  `Engine.startCall`/`callServer()`/`callPresence()` are not
  unit-tested — the native `sphinxrs` gap every other `Engine` send
  function already has; `CallBlock`/the header's call menu are Compose
  UI, the same existing gap every other screen has. No on-device
  verification was possible this session (`adb devices -l` stayed
  empty throughout) — build- and test-verified only.

## Learned

- A feature that looks like one thing from its name ("Calls") can
  split cleanly into pieces with completely different risk profiles —
  here, "pure, testable, no-SDK logic" vs. "a real-time media SDK and
  a UI only a live device can confirm actually works." Scoping to the
  first kind first, explicitly, with the second kind named and left
  open rather than half-built, kept this round honestly sized.
- Checking what a platform's own standard library already provides
  (OkHttp's `pingInterval`) before porting a mechanism that exists in
  the reference implementation only because *that* platform's own
  primitive doesn't have it, avoids porting incidental complexity
  along with the real logic.
