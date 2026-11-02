# Phase 13 — LiveKit calls: joining in-app

## Status (2026-10-30)

**Built:** the real thing Phase 12 deliberately deferred — joining a
call without leaving Peel. "Join" on a call card and "Start a call"
from the conversation header both now bring up a full-screen call
screen: a tile grid (video where someone's camera is on, their avatar
otherwise), mic/camera/flip-camera/leave controls, a running-time
header, and recovery from a dropped connection ("Rejoin"). Built on
the real LiveKit Android SDK (`io.livekit:livekit-android`, pinned to
`2.29.2` — peel's own iPhone/Mac apps are on `client-sdk-swift`
`2.10.2`; the two platforms' SDKs version independently, same as any
two native LiveKit clients talking to the same room), the same media
engine the iPhone and Mac apps use.

**The ticket request.** `Engine.callTicket(link)` — PeelCore's own
`Engine.callTicket(for:in:)` — asks this account's call server (`GET
/api/connection-details`) for a LiveKit server address and a
short-lived access token, under this account's own alias (or "Peel
user" if blank, never the tribes registry's own "Unknown") and photo.
The reply is checked exactly as strictly as PeelCore checks it:
the media server must be the call server's own host or the one
specific allow-listed LiveKit host (`livekit.sphinx.chat`), never an
arbitrary `serverUrl` the call server's reply could otherwise point
anywhere; the token's own claims (decoded, not verified — this account
has no key to verify a token someone else issued, and trusts it only
for "which room, whose identity, until when") must name the room asked
for and not already be expired. `CallServer.ticket`/`buildTicket`/
`mediaUrl`/`claimsOf` port `CallServer.swift`'s own ticket half
line-for-line in intent, now that something actually needs a token
(Phase 12 deliberately didn't port this half at all).

**The call session.** `ui/call/CallSession.kt` ports PeelCore's own
`IOSCallSession`: one LiveKit `Room`, the tile list (built from
`room.localParticipant` and `room.remoteParticipants`, refreshed on
every `RoomEvent`), mic/camera state read from the actual track
publications (`muted`, not "unpublished" — this SDK mutes rather than
unpublishing on "camera off" too, so one check covers "never turned
on" and "turned off" alike), a connecting/connected/reconnecting/lost
state machine, and the running-time display. Trimmed from PeelCore's
own version: no screen sharing (no tile ever needs an `isScreen` flag
or a focus/pin layout to go with it — this round has nothing to
share), no device-menu camera/mic picker, no manual audio-session
setup (`AVAudioSession` is an iOS-only concern; this SDK's own
`audioswitch` dependency already handles Bluetooth/speaker/earpiece
routing itself, inside `Room` — nothing here configures `AudioManager`
by hand).

**Permissions live at the UI layer, on purpose.** A system permission
dialog needs an `ActivityResultLauncher`, which only exists in a
Composable, not inside `CallSession`. `CallScreen` owns two launchers
and hands `CallSession` two suspend closures
(`askMicPermission`/`askCameraPermission`) that check-then-request;
`CallSession` calls them before ever actually turning a device on,
whether that's the automatic "microphone on after connecting" or an
explicit tap on the control bar — the same gate either way, not two
different permission paths for the automatic and manual cases.

**No persistent background call.** Leaving the call screen (the
chevron, the same as every other screen in this port — not the system
back gesture, which nothing in this port intercepts) ends the call;
there is no banner to return to it and no way to keep it running while
browsing other chats. `CallSession` is created with
`remember(ticket) { ... }` and torn down (`room.disconnect()`,
`room.release()`) in a `DisposableEffect`'s `onDispose` — its whole
lifecycle is scoped to the call screen's own composition, the same
pattern `ThreadScreen` already uses for being "this chat screen,
replaced by something else for a while." This is the single biggest
simplification against PeelCore's own `CallStore` (an app-level,
always-alive store juggling the call you're on, the ones you're only
watching, cross-chat "leave and join a different one?" confirmations,
and Jamie/Hive picks) — see "Open" and "Decided".

**Open:**

- **Persistent background call / on-call banner is built** — see
  [phases/15-call-background.md](15-call-background.md). Still open
  from that phase: the cross-chat "leave and join a different one?"
  confirmation, and real OS-level backgrounding (no foreground
  service yet — this is "survives switching screens inside Peel," not
  "survives Android backgrounding the app").
- No screen sharing (no Android equivalent of iOS's broadcast
  extension / Mac's screen picker built), so no focus/pin layout
  either — `CallTile` has no `isScreen` field to need one.
- No in-call chat (`CallChat.swift`'s own side-panel text during a
  call).
- No device-menu camera/mic picker (choosing a specific physical
  camera or audio device rather than "the camera" as a whole) — this
  SDK's own default capturer/audio routing is used as-is.
- No Hive-originated calls ("call with Jamie") — no Hive/Workspaces
  integration on this port at all.
- The "Rejoin" after a lost connection re-fetches a ticket and builds
  a whole new `CallSession`/`Room` rather than reconnecting the
  existing one — simpler, reuses the same ticket-fetch path the first
  join already has, at the cost of a very brief extra round trip
  `Room.connect`'s own built-in reconnect logic (the `RECONNECTING`
  state, before a connection is given up on entirely) wouldn't need.
- No verification beyond "it compiles and assembles" — no device has
  been connected this entire session (`adb devices -l` stayed empty
  throughout), so the actual join flow, video rendering, audio
  routing, and permission dialogs have never run. The ticket-building
  logic (`CallServerTicketTest`) and the pure status-formatting
  (`CallSessionTest`) are unit-tested; the LiveKit session itself and
  the screen's own Compose UI are not, the same category of gap every
  other screen/`Engine` send path in this port already has, just with
  a real media SDK underneath this time instead of the core's own FFI.
- `CallSession`'s tile sort (`joinedAt` ascending, you last) matches
  PeelCore's own ordering, but `joinedAt` is read once per `refresh()`
  from `participantInfo` (populated by the server) — a participant
  whose info hasn't arrived yet sorts as if they joined last
  (`Long.MAX_VALUE`), which self-corrects on the very next event that
  carries their real join time. Not a bug seen, just an ordering
  footnote inherent to reading a value that can itself arrive late.

## Decided

- No app-level `CallStore` (PeelCore's own always-alive store) — a
  call session scoped to the screen that started or joined it, torn
  down on leaving. This is a real behavior difference (no persistent
  background call), made deliberately rather than discovered as a
  limitation: building the always-alive version means solving "where
  does it live above `MainTabs`" and "what does leaving a chat screen
  even mean while a call from it is still going" before writing a
  single line of LiveKit code, and neither question has an answer this
  port's own architecture (no app-level model object at all yet,
  unlike PeelUI's `AppModel`) makes obvious. Worth doing once the
  banner/persistent-call feature is actually wanted, not assumed as
  part of "joining calls work now."
- Mic/camera permission requests live in the Composable
  (`CallScreen`), not `CallSession` — an `ActivityResultLauncher` has
  no equivalent reachable from a plain Kotlin class, so the session
  takes two suspend closures instead of asking Android directly
  itself. Keeps `CallSession` ignorant of Activity-level Android API,
  testable in principle (even though nothing here currently
  constructs one in a test, given the native SDK underneath).
- `Room.disconnect()`/`release()` being plain (non-suspend) functions
  on this SDK (unlike `await room.disconnect()` on iOS) meant `leave()`
  could be a plain synchronous function too, callable directly from
  `DisposableEffect`'s `onDispose` (which cannot suspend) — no async
  cleanup bridge needed, unlike what the Swift port required.
- Video rendering tracks which `VideoTrack` a `SurfaceViewRenderer` is
  currently attached to via the view's own `tag`, rather than trusting
  Compose's `AndroidView` `update` callback to only fire when the
  track actually changes (it fires on every recomposition of the
  tile, for any reason) — a camera being toggled off and back on makes
  a *new* `LocalVideoTrack` instance reusing the same tile/renderer,
  and the old track needs an explicit `removeRenderer` or it would
  keep delivering frames nobody shows anymore.

## Journal

**Read the real Android SDK before writing a line against it, not just
PeelCore's Swift equivalent.** The temptation with a well-understood
reference port (iOS's `IOSCallSession` lays out exactly what a
session needs to track) is to start writing Kotlin that "should" match
some remembered shape of the Android SDK. Instead: pulled the actual
`client-sdk-android` source for `Room`, `Participant`,
`LocalParticipant`, `RoomEvent`, `TrackPublication` and the sample
app's own `MainActivity.kt` before writing `CallSession` at all —
confirmed `room.events` is a custom `EventListenable`, not a plain
`Flow` (needs `io.livekit.android.events.collect`, not
`kotlinx.coroutines.flow.Flow.collect`), confirmed `setCameraEnabled`/
`setMicrophoneEnabled(false)` *mute* rather than *unpublish* (matters:
PeelCore's own camera-off branch explicitly unpublishes, a difference
worth knowing rather than silently porting the wrong mechanism and
having it merely happen to look right), confirmed `Room.disconnect()`
is not suspend. Every one of these would have been a real, possibly
silent bug if guessed instead of verified — more so than usual here,
specifically because nothing in this session could run the code
against a real device to catch the guess being wrong.

**A platform's own SDK sometimes already does what the reference
implementation had to build by hand.** PeelCore's audio-session setup
(`AVAudioSession`, `.playAndRecord`/`.voiceChat`) and LiveKit's own
Android `audioswitch` dependency do the same job — Bluetooth/speaker/
earpiece routing for a call — but the Android SDK does it *inside*
`Room` itself (confirmed in its own source: `audioHandler.start()`
right on the `CONNECTING` state transition), so there was nothing for
this port to build at all here, only to recognize was already handled
and not duplicate.

**A real dependency needs a repository, not just a version.**
`io.livekit:livekit-android` resolved cleanly, but failed at
`processDebugNavigationResources` over a *transitive* dependency
(`com.github.davidliu:audioswitch`) that Maven Central and Google's
own repository don't carry — only JitPack does. Easy to miss since the
direct dependency itself resolves fine; worth checking a new SDK's
full dependency tree, not just its own artifact, before assuming
"it compiled" means "it's really available."

## Done

- `engine/CallServer.kt`: `CallTicket`; `CallServer.ticket(link,
  participantName, photoUrl)` and its own `TicketException` sealed
  class; `ticketUrl`/`buildTicket`/`mediaUrl`/`claimsOf`/`endpoint`,
  all internal for testing; `fromLiveKitRoom(identity, name, metadata)`
  — the same participant-sanitizing `participant(fields)` the presence
  feed already used (Phase 12), reused rather than re-implemented, for
  a LiveKit room participant instead of a presence-feed one.
- `engine/CallLink.kt`: `encode`/`baseURL`/`defaultPort` relaxed from
  `private` to `internal` so `CallServer`'s own ticket-URL building and
  media-host validation can reuse them instead of a third copy of the
  same scheme/encoding logic.
- `engine/Engine.kt`: `callConnection(link)` factored out of what used
  to be `callPresence`'s own inline logic, now shared with the new
  `callTicket(link)`.
- `ui/call/CallSession.kt` (new): the LiveKit room owner — connection
  state, tiles, mic/camera, permission-refused/device-notice state,
  `formatElapsed` (the pure running-time formatter, exposed for
  testing).
- `ui/call/CallScreen.kt` (new): the full-screen call UI — ticket
  fetching (with a "Try again" retry), permission-request bridging
  (`ActivityResultLauncher` wrapped as suspend closures), the header
  (chevron, title, running time), `CallStage` (the tile grid),
  `CallTileView` (video or avatar, a speaking border, a muted/name
  chip), `CallVideoView` (the `SurfaceViewRenderer` `AndroidView`
  wrapper, tracking its currently-attached `VideoTrack` via the view's
  own `tag`), `CallControls` (mic/camera/flip/leave, refused-permission
  and lost-connection notes, a recording indicator).
- `ui/dashboard/ConversationScreen.kt`: `activeCall` state (replacing
  this screen's content with `CallScreen`, the same pattern
  `openThreadRoot` already uses for `ThreadScreen`); `startCall` now
  joins in-app instead of calling the now-removed `openExternally`;
  `CallBlock`'s Join calls a threaded-through `onJoinCall` instead;
  `ThreadScreen` gained the same `onJoinCall` parameter, threaded to
  both of its own `MessageRowView` calls, so a call message that
  somehow ended up inside a thread joins the same way.
- `app/build.gradle.kts`/`gradle/libs.versions.toml`:
  `io.livekit:livekit-android:2.29.2`.
- `settings.gradle.kts`: added the JitPack repository — LiveKit's own
  `audioswitch` dependency is published only there.
- `AndroidManifest.xml`: `CAMERA` permission (requested at runtime,
  same pattern `RECORD_AUDIO` already uses), an optional
  `android.hardware.camera` feature declaration (a device with no
  camera can still join audio-only).
- Tests: `CallServerTicketTest` (13 cases — `claimsOf` reads/refuses a
  token's own shape; `buildTicket` accepts a valid matching token and
  refuses a wrong-room/expired/malformed one; `mediaUrl` accepts the
  call server's own host and the one allow-listed media host, refuses
  everything else — an unlisted host, plain `ws://` on a non-loopback
  server, credentials, a query, a fragment, oversized text — and
  accepts plain `ws://` only when the call server itself is
  loopback); `CallSessionTest` (`formatElapsed`'s minute/second and
  hour-crossing formatting). 124 tests passing. `CallSession`/
  `CallScreen` themselves are not unit-tested — they need the real
  LiveKit SDK and a device, the same category of gap as every other
  `Engine` send path and screen in this port, just with a heavier
  native dependency underneath this time.

## Learned

- Verifying a third-party SDK's real current API against its actual
  source (not a remembered shape of "how LiveKit probably works") is
  worth the extra lookups specifically when nothing in the session can
  run the result to catch a wrong guess — the gap between "compiles"
  and "actually correct" is exactly where an unverified guess about a
  callback-vs-Flow API or a mute-vs-unpublish semantic would have
  silently landed.
- A reference platform's own workaround (PeelCore's manual
  `AVAudioSession` configuration) is not automatically a requirement
  for the port — checking whether the *other* platform's SDK already
  solves the same problem internally (LiveKit Android's own
  `audioswitch`) avoided building infrastructure that was already
  there, one layer down.
- Scoping "no persistent background call" as a named, deliberate cut —
  not a bug, not forgotten, a specific unanswered architectural
  question (where does an app-level call store live, with no app-level
  model object existing yet) — kept this round's own size honest
  rather than quietly growing to answer a question nothing had asked
  yet.
