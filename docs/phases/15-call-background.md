# Phase 15 — Calls keep running in the background

## Status (2026-11-13)

**Update:** calls now continue with Peel in the background or the screen locked, through an application-scoped `CallController` and the `CallService` foreground service — see "Update — the call outlives the Activity" at the end. Written and unit-tested for its pure part; **nothing has been run on a device** (`docs/call-background-device-checklist.md`).

Original status of the in-app phase:

**Built:** the piece Phase 13 named and deliberately left out — a call
survives leaving the screen that started or joined it. The chevron on
the call screen now only hides it; a banner (PeelCore's own
`IOSOnCallBar`) shows on every other screen — Chats, Search, You, and
any open conversation, including a different chat than the one the
call is in — with the chat's name, the running time, a mute toggle and
Leave, and tapping the row itself (not either button) brings the call
screen back. Built from the user's own screenshots of the real app:
the stacked-tile call screen, and the banner's exact row shape.

**The architecture change this needed.** `CallSession` used to be
`remember`'d inside `CallScreen` itself, keyed to a ticket that screen
fetched on its own — its whole lifecycle was scoped to that one
screen's composition, the same way `ThreadScreen`'s is to being open.
That's exactly what had to stop being true: the call now lives in
`MainTabs`, above both the open chat and the tab content, as a new
`CallPhase?` (`Connecting`/`Failed`/`Active`, in `CallSession.kt`) —
`Connecting` and `Failed` exist because there's a real gap between
"asked to join" and "there's a session," the ticket request itself.
`ConversationScreen`'s own former `activeCall` state, and the
`CallScreen` early-return it rendered, are both gone — Join and Start
now call a threaded-down `onJoinCall(chat, title, link, camera)` that
reaches `MainTabs`' own `connect(...)`, same as before except the
*result* doesn't live where the request was made.

`CallScreen` itself is now a pure renderer of whichever `CallPhase` it's
handed, with no ticket-fetch, no `CallSession` construction, and no
`DisposableEffect` to leave it — `onHide`/`onLeave`/`onRetry` are all
it needs, supplied by `MainTabs`. The permission-launcher plumbing
(`askMicPermission`/`askCameraPermission`, needing an
`ActivityResultLauncher`, which only exists in a composable) moved up
to `MainTabs` right along with everything else that used to live in
`CallScreen`.

**Open:**

- **No cross-chat "leave and join?" confirmation.** PeelCore's own
  `CallStore` asks first ("Leave the call in #design and join?")
  before switching; this port's own `connect(...)` just leaves the old
  call and joins the new one, no dialog. A deliberate trim, not an
  oversight — nothing is *lost* by leaving a call the way a half-typed
  message would be, so the safety a confirmation buys here is real but
  smaller than the UI it costs to build (the same pending-action-ask-
  run shape `PendingCall`/`callSwitchConfirmation` are in PeelCore).
  Worth adding once this port's calls feel real enough that silently
  dropping one to join another actually surprises someone.
- **Backgrounding the whole app: built, see "Update — the call outlives the Activity" below.**
  (This phase originally stopped at in-app screens; that gap is closed there, not device-tested.)
- **No tap-to-focus / pinned speaker.** Unchanged from Phase 13 — still
  no screen share, so still nothing a focus layout would be for.
- **The banner's mute button has no "refused" visual state of its
  own** beyond being disabled — PeelCore's own bar doesn't show the
  "Open Settings" note there either (only the full call screen's
  control bar does), so this matches on purpose, not a cut corner.

**Other UI gaps noticed while reading through the user's screenshots
(not fixed this round — a punch list for later, not a promise of
what's next):**

- The conversation header's call trigger is a single phone
  `IconButton` opening a text menu ("Start an audio call"/"Start a
  video call"); the real app pairs a blue phone pill with its own "···"
  more-options button as one combined control, and that "···" menu
  almost certainly does more than start a call (the rest of
  `docs/phases/05-conversation.md`'s own "the More menu... are all
  out" gap). Cosmetic as far as calls go; the "···" menu itself is a
  separate, larger feature.
- The call screen's own status pill at the very top of the
  screenshot (the black capsule with an amber dot) is iOS system
  chrome (a background-recording indicator), not anything this app's
  own UI draws — nothing to port.
- Everything else visible in the screenshots — the Chats list's own
  tribes/"Show all N tribes"/"Join a tribe"/"Create a tribe"/"Direct
  messages" rows, the agent's own "AGENT" tag, the bottom tab bar's
  shape, the green "connected" bolt — already matches what's built
  (Phases 4, 9, 11, 12).

## Decided

- `CallSession` itself didn't need to change at all for this — its own
  lifecycle (`leave()` idempotent, tiles/mic/camera as Compose state)
  was already decoupled from any particular screen; only *who holds
  the reference* needed to move. Worth noting as the payoff of having
  built it that way the first time, not something this phase had to
  go fix.
- `CallPhase.Connecting`/`Failed` exist as their own states (not,
  say, `CallSession?` plus a separate `TicketFailure?`) because
  `CallScreen` and `CallBanner` both need to render "asked to join, no
  session yet" and "that failed" identically regardless of which one
  is showing — one sealed type both can switch on, instead of two
  places independently reconstructing "which of these three things is
  true right now" from loose nullable state.
- A stale ticket-fetch (the user left, or started a different call,
  while the request was still in flight) is caught by comparing
  object identity (`callPhase !== requested`) against the exact
  `Connecting` instance that triggered it, not a counter or a flag —
  the same shape `CallLinkTest`/`CallServerTicketTest`'s own careful
  edge-case coverage already favors: the simplest check that is
  actually correct, not the first one that happens to work.
- `MainTabs` gained a `DisposableEffect` that leaves any active call
  if `MainTabs` itself is ever torn down (account forgotten). *Superseded:* that tied the call to the
  composition, so rotation ended it. Teardown is now explicit, on "Forget this account" (`CallController.endAll`).

## Journal

**Moving a feature's state up is a real refactor, not a config
change.** The instinct was to reach for something smaller — hold a
reference to the session somewhere shared and leave `CallScreen`
mostly alone. That doesn't work: `CallScreen` owned the entire
sequence (fetch a ticket, construct a session, show it, leave it on
exit) as one unit, and "outlive the screen" means the *sequence*
itself has to live somewhere that isn't the screen. Once that was
clear, the actual change was mechanical — move the ticket-fetch and
construction to `MainTabs`, turn `CallScreen` into a renderer of
whatever state it's handed — but it only became mechanical after
seeing that the state, not just a reference to a result, was what
needed to move.

**A sealed class with computed convenience properties collides with
its own subclasses' constructor properties in a way that isn't
obvious until the compiler says so.** `CallPhase.Connecting`/`Failed`
each have their own `chat`/`title`/`link` fields; adding same-named
*member* properties to the sealed class itself to read them uniformly
triggered "hides member of supertype, needs override" on every one —
Kotlin treats same-named members as an override relationship whether
or not that's remotely the intent. Extension properties instead
(`val CallPhase.chat: String get() = ...`) sidestep this entirely:
they're never members, so there's nothing to override and no
shadowing to accidentally fall into. Worth reaching for an extension
by default when a convenience accessor's name is already taken by
every variant's own field, rather than fighting the override system
to keep it a member.

## Done

- `ui/call/CallSession.kt`: `CallPhase` (`Connecting`/`Failed`/
  `Active`), with `chat`/`title`/`link`/`camera` as extension
  properties reading whichever variant is current.
- `ui/call/CallScreen.kt`: rewritten to take `phase: CallPhase` plus
  `onHide`/`onLeave`/`onRetry` instead of `engine`/`chat`/`title`/
  `link`/`camera`/`onBack` — no more internal ticket-fetch, session
  construction, or `DisposableEffect`; `CallStatusText`/`CallNote`
  made `internal` so `CallBanner` can reuse them; tile aspect ratio
  for one or two people changed from 16:10 to 4:3, closer to the real
  app's own proportions.
- `ui/call/CallBanner.kt` (new): the on-call strip — a dot (green
  once actually connected, muted otherwise), the chat and running
  time (or "Connecting…"/the failure's own message), a mute toggle
  when there's a real session to mute, Leave; a tap anywhere else
  reopens the call screen.
- `ui/dashboard/MainTabs.kt`: owns the call now — `callPhase`/
  `callScreenShown`, the permission-launcher plumbing moved here from
  `CallScreen`, `connect`/`retryCall`/`leaveCall`, the
  `DisposableEffect` safety net on teardown; renders `CallScreen` full-
  screen when shown, else `CallBanner` (when a call exists) above
  whichever tab or open chat is showing.
- `ui/dashboard/ConversationScreen.kt`: `activeCall` state and the
  `CallScreen` early-return both removed; gained an `onJoinCall`
  parameter, threaded to the header's "Start a call" and a received
  call card's own Join (both directly and through `ThreadScreen`,
  unchanged from Phase 13 otherwise).
- No new tests — this round changed only state ownership and wiring
  across three Compose files already in the existing, documented
  "Compose UI, not unit-tested" category (`CallScreen`/`CallSession`
  already named this gap in Phase 13); nothing new and pure was
  extracted to test in isolation. 129 tests passing, unchanged. No
  device was connected this session, so — the same as every call
  change so far — this is build-verified only.

## Learned

- A feature that looks like "add a banner" can actually be "move where
  a whole feature's state lives" once the banner's own job is to show
  the *same* thing a different screen already owns — the UI piece was
  the easy half; figuring out what had to stop belonging to
  `ConversationScreen` was the real work.
- When a sealed class's own subclasses already carry same-named
  fields, a convenience accessor on the sealed type itself is the
  wrong tool even if it looks like the obvious one — an extension
  property gets the same call-site ergonomics without the override
  machinery fighting back.

## Update — iOS call parity (screen share, chat, Jamie, devices, switch confirm, recency)

Built: screen share via LiveKit's `ScreenCaptureParams` (its own mediaProjection service; manifest declares
`FOREGROUND_SERVICE_MEDIA_PROJECTION`) plus "share a camera" as a share tile; tap-to-pin focus layout
(`CallSession.focusTile`, pinned tile or first screen share large, strip of the rest); in-call chat side panel
(`engine/CallChat.kt`, topic `lk-chat-topic`, JSON `{id,timestamp,message}` = LiveKit/meet convention); camera picker
and audio-route picker (device menu); "Leave the call and join?" dialog (`CallSwitch`, `MainTabs.request`);
Join hidden on call cards older than 12 h (`CallLink.isRecent`); call with Jamie from Apps
(`HiveClient.startCall` -> `POST /api/strut-devices/calls {workspace}`, `CallLink.hive`). The sidebar green phone already existed.
Assumptions (the iOS `peel` source was not available): chat wire format, `workspace` = the peer id, Hive room
must be on the account's call server, "camera picker for share" = share a second camera. Untested on a device.

## Update — the call outlives the Activity

**Built (not device-tested).**

- **`call/CallController`** (an `object`, installed by `PeelApplication`) owns the group call's `CallPhase`/`CallSession`,
  `callScreenShown`, Talk to Jamie's `TalkStore`, the banner `notice` and the permission request. `MainTabs` only observes it
  (`CallController.phase`, …) and calls `connect`/`leave`/`retry`/`talkTo`/`hangUpTalk`. The old `remember`ed call state and the
  `onDispose` teardown are gone: rotation, a recreated Activity or a relock (Home → Relock) no longer end the call. The only
  teardown is `endAll()`, from "Forget this account", Leave, a swipe from Recents, or a call lost in the background.
- **Permissions before connecting.** `connect` asks for `RECORD_AUDIO` (and `CAMERA` for a video call) first; the service starts
  only after. The launcher stays in `MainTabs` (an Activity thing): the controller publishes `permissionRequest`, `MainTabs`
  launches it and returns the answer with `permissionResult`, so a recreated Activity does not strand the wait. Sessions get
  controller methods for the later asks (a camera tap), never an Activity closure. Microphone denied = the call does not start
  (a Failed state with a Try again), because an FGS of the microphone type cannot start without it; camera denied on a video
  call = joined audio-only. Talk does the same through `TalkStore.preflight`.
- **`call/CallService`** is the lifecycle anchor: started once at call start while Peel is on screen, never re-promoted from the
  background. `ForegroundTypes.select` (pure, `ForegroundTypesTest`) picks the types per call: microphone always, camera only
  while video is wanted (`wantsCamera`) and granted; chosen at runtime with `ServiceCompat.startForeground` (the manifest declares
  `microphone|camera` as the allowed set, `stopWithTask="false"`). `ForegroundServiceStartNotAllowedException` and
  `SecurityException` are caught and reported to `CallController.serviceRefused` (a red banner, plus a notification if Peel is not on screen) — the call
  carries on while the app is visible. Adding the camera type later is only tried while visible. It holds a `PARTIAL_WAKE_LOCK` and
  a `WifiLock` (`WIFI_MODE_FULL_LOW_LATENCY`, `WAKE_LOCK` permission), released in `onDestroy`.
- **The notification** (`Notifications.ongoingCall`): chronometer from the call's start (`connectedAtMs`), Mute/Unmute and Leave,
  `FOREGROUND_SERVICE_IMMEDIATE`. Both actions go to the service, which calls `CallController.toggleMute`/`leaveAll` directly; the
  `ACTION_LEFT` broadcast and the receivers in `MainTabs` are removed. Sessions tell the controller what changed through an
  `onStatus` hook (not `snapshotFlow`, which does not run with no Activity composing).
- **Camera and the background** (iOS parity): the process lifecycle's ON_STOP turns the local camera off but keeps `wantsCamera`;
  ON_START turns it back on if it was paused that way. The microphone is untouched.
- **Talk to Jamie** uses the same controller and service (audio only).
- **Swipe from Recents** (`Service.onTaskRemoved`) ends the call.
- **Notifications denied:** the call runs; a one-time toast says the controls will not show (`notification_nudge_shown` preference).
- **A call that died:** lost in the background (LiveKit gives up) the call is ended, a notification is posted (`callEnded`) and a banner
  waits in the app. If the process itself is killed, a `call_background` preference flag (written at call start, cleared on every clean
  end) is found at the next start: the same banner and notification.

**Decided.** One controller object, not a ViewModel (a ViewModel dies with the task; the service is the anchor). Mic denied blocks
the call rather than a listen-only join, because the FGS type needs the permission. Service types follow the wish for video, not
the camera's momentary state, so ON_STOP/ON_START never changes the type.

**Not done / known gaps.** No device run. A permission dialog open while the Activity is recreated may show twice. A relocked
account keeps its call running behind the PIN (the engine does too); the call screen is shown again afterwards. No Picture-in-Picture,
no battery-optimisation prompt, no Telecom/ConnectionService, incoming calls unchanged, no Play Console FGS declaration (all out of scope on purpose).
