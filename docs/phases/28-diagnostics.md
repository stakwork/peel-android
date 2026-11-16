# Phase 28 — Diagnostics

Status: **built, compiled and unit-tested** (`./gradlew testDebugUnitTest`: 740 tests, 0 failures; `assembleDebug` builds).
**Not run on a device or emulator**: the uncaught-exception handler, `ApplicationExitInfo` reading, the share sheet and the
screens are untested outside the JVM. Ports iOS `Sources/PeelDiagnostics`, `AppModel+Diagnostics.swift`, `PeelError+Diagnostics.swift`
and `Apps/iOS/Peel/DiagnosticsViews.swift` (docs/diagnostics.md there). Nothing is uploaded; Copy and Share are the person's own act.

## Built
- **`diagnostics/`** — pure Kotlin, JVM-tested:
  - `DiagnosticEvent` — the same model: every enum and field of iOS's event (same names, so a record reads alike), `line` (the
    one-line form), JSON in the iOS ring's format (keys sorted, times as seconds). `MiniJson` is the codec (not `org.json`,
    which is a stub on a plain JVM).
  - `DiagnosticsRing` — `events.ring`, 256 KiB / 2000 events, JSON lines, identical consecutive events counted, rotated to the
    newest half, a damaged line skipped, a missing final newline repaired, atomic rewrite.
  - `RunMarker` (`PreviousRun`, `RunMarkerStore`, `RunEnvironment`, `PreviousRunDetail`) — how the last run ended
    (`clean`/`crashed`/`killedForeground`/`killedBackground`/`unknown`), and for a kill the likeliest cause
    (`restarted`/`osUpdated`/`reinstalled`/`system`) with the time alive out of the front, memory warnings and footprint.
  - `Redactor` (record redaction; allow-list for tribe names and keys, contact names removed, same rules and placeholders) and
    `DetailRedaction` (`Diagnostics.redact`: hex 16+, tokens 24+, invoices, emails, URL paths, 200 characters).
  - `CrashRecord`/`RedactedRecord` (`rendered()` in Apple's crash-log shape, `compact()`), `DiagnosticsReport.rendered()`.
  - `DiagnosticsStore` (`raw/` private, `redacted/`, never a name from disk unchecked), `MemoryPolicy` (5 % rule).
  - `Diagnostics` — the one entry point with iOS's `record*` functions, `note`, `setAction`, `setForeground`, `snapshot`,
    `latestRedactedReport`, `clearEvents`, `deleteRecords`; all no-ops before `CrashRecorder.install`. `DiagnosticsRuntime` is
    the testable core (clock, memory probe, environment injected). Mirror to Logcat (`PeelDiagnostics`) instead of `os.Logger`.
  - `ErrorKinds` (`PeelError.diagnosticKind`'s table for Android's exceptions), `LockTrace`, `ExitInfoMapping`,
    `DiagnosticsNames`, `DiagnosticsExport.screenText`.
- **Android pieces** (`CrashRecorder.kt`): installed first in `PeelApplication.onCreate`. An **uncaught-exception handler** writes
  the raw record (exception class, message and cause chain, the crashed thread's frames, the ring as it stood) and a
  `pending-crash` note before the process dies, then chains to the previous handler; the next launch finds it (= PLCrashReporter's
  pending report). A **native crash** is found next launch in `ApplicationExitInfo` and made into a record (signal and the
  system's words). The process lifecycle drives `setForeground` and the `scenePhase` events; `onTrimMemory` the memory
  warnings; `AndroidMemoryProbe` (PSS) a 60 s foreground sampler. A run the system saw end on purpose (exit, force-stop) is marked
  clean. `ExitInfoReader` records `appExits` and `metricDiagnostic` (crash/hang) for exits not yet seen.
- **UI** (`ui/diagnostics/`): `DiagnosticsScreen` (You › Account › Diagnostics, between Auto download media and Server: back
  button, "Diagnostics", monospaced text — "Now:", how the last run ended, the newest 500 events — Refresh / Copy ("Copied") /
  Share / Clear with the confirmation "Clear the event record?" / "Crash records are kept."; same caption as iOS, "phone" for
  "iPhone"), `DiagnosticsNotice` at the top of Chats when the last run crashed or was killed in front ("Peel recorded a problem
  from the last run. The record stays on this phone." — Show record / Dismiss), `DiagnosticRecordSheet` ("Problem record",
  redacted with this account's tribe names and keys kept and contacts' names removed, Copy / Delete, no share) and
  `DiagnosticsModel` (= `AppModel+Diagnostics`). Copy goes through `SensitiveClipboard` (hidden from the clipboard preview on
  API 33+, cleared after five minutes if still ours); Share is `Intent.ACTION_SEND` text/plain in the system chooser.
- **Recording call sites** (iOS equivalents in brackets): `Engine` — inbound failure (`coreFailure receive`), connect failure
  (`note`), restore failure (`coreFailure resumeHistory`), the catch-up after each connect (`catchUp` start/finish/abandon,
  `catchUpCursor`, `caughtUp`), history pages, `forcedReconnect` (network change, stall) and `stallReconnect`, a send marked
  failed by the server, `sendMessage`/`sendBoost` (`setAction(send)`, errors noted). `Transport` — `transportLifecycle`
  opened/closed with the live count, `subscribed`, `readFailure`. `ConnectionWatcher` — `network` (status, wifi/cellular/
  ethernet, metered, data saver) and `foregroundDecision` on coming to the front. `PeelMessagingService` — `pushFetch` begin/end.
  `MainActivity` — `lock` (cold launch; biometric cover, every open, idle timeout with idle seconds and timeout hours).
  Calls — `note(call)` for a failed join, service refusal, call ended in background, Talk failures (`refused`), camera paused/
  resumed and the scene phase on a call (`callAudio`).
- **Tests** (`app/src/test/.../diagnostics/`): iOS's RingTests, MarkerTests, RedactorTests, TraceEventTests and ClearEventsTests
  ported case by case, plus the Android pieces (crash record from a throwable, store round-trip, exit-reason mapping, error
  kinds, lock reasons, JSON, runtime with a fake probe/clock, names, screen text).

## Decided
- The Debug card's "Force test crash" row already existed; it throws, and now leaves a record. (iOS hides it outside Debug builds;
  this port shows it always, as before.)
- `.internal` is not in the redactor's device-host rule (iOS has it): Java names like `kotlinx.coroutines.internal` matched it.
  Android home paths (`/data/user/N/<pkg>`, `/data/data/<pkg>`, `/storage/emulated/N`, `/sdcard`) become `~` beside iOS's.
- Files live in `noBackupFilesDir/Diagnostics` (iOS: excluded from backup, `completeUntilFirstUserAuthentication`).

## PORT_NOTES
iOS behaviours not matched on Android, and why:
1. **Strut's exit and stderr tail** (`strutExit`, `recordStrutExit`, `StderrTail`, the record's "Strut's last stderr"): the strut is a
   Mac-only child process. The enum case and the render of the tail exist; nothing writes them.
2. **MetricKit.** `appExits` and `metricDiagnostic` come from `ApplicationExitInfo` (API 30+; nothing recorded below it), mapped to
   MetricKit's category names by `ExitInfoMapping`. They are read once per entry (iOS repeats a payload it still holds), carry no
   hang seconds or exception type/code, and `ExitInfoMapping.category`'s foreground/background split uses the process importance
   at exit, which is an approximation of MetricKit's.
3. **PLCrashReporter's record.** A Java crash has no addresses, registers, binary images or mach exception: frames are
   `class file / Class.method + line` with `pc` 0, no images, no registers. A native crash record has only the signal and the
   system's description (no stack: reading the tombstone trace is not done). A crash in the handler itself, or one killed before
   the record is written (an out-of-memory in the handler), leaves only the kill classification. The handler writes at crash time
   (iOS's reporter does too, then collects next launch).
4. **Memory.** `phys_footprint` → PSS (`Debug.MemoryInfo`), close but not the same number; `os_proc_available_memory` (per-process
   headroom) has no Android equivalent: `availableBytes` is the system's free memory. Memory warnings are `onTrimMemory`
   (RUNNING_MODERATE/LOW → warning, RUNNING_CRITICAL → critical), not `didReceiveMemoryWarning`; the Mac's dispatch source is n/a.
5. **Clean exit.** Android has no `willTerminate`; `markCleanExit` exists but nothing calls it. Instead a run `ApplicationExitInfo`
   says ended on purpose (self exit, user force-stop, package update) is marked clean at the next launch (API 30+). Below API 30
   a force-stop in front reads as `killedForeground` and shows the notice. Swiping from Recents reads as `killedBackground` (the
   app is stopped by then), as on iOS.
6. **Scene phase.** Android's process lifecycle has no `inactive`; only `active` (ON_START) and `background` (ON_STOP) are recorded.
   `launchedInFront` comes from the process importance at `Application.onCreate`, which can misjudge a process started by a
   high-priority push.
7. **Engine decision events with no Android counterpart.** The Android engine is a subset of PeelCore's: it always reconnects on
   coming to the front (so `foregroundDecision` is always `reconnect`/`away`; there is no trust/verify/rebuild, no `foregroundPing`,
   no keep-alive `pingResult` (MQTT's own keep-alive is the library's), no `pathChangeFlagged`, no `background` with an in-flight
   catch-up, no `selfCheck`, no `liveMessage`, no `transportLifecycle` `abandoned`/`establish`/`leak` (opened/closed only), no
   `coreRecovery` (there is no state reload from storage), no `tribeTrace`/`tribeState` (the tribe discovery and registry-confirmation
   machinery they trace is not ported; see phase 23), and `coreFailure` sites `commit`, `cameOnline`, `checkPending` are not
   distinguished. The events exist in the model and render; nothing records them yet.
8. **Background task expiry** (`backgroundExpired`, `Diagnostics.backgroundTaskExpired`): iOS's `beginBackgroundTask` expiry handler.
   Android has no equivalent callback for the catch-up (the engine keeps no background task); a push fetch has no timeout signal.
   `pushFetch` records `begin` and `end` only, never `expired`, and no `backgroundSecondsLeft`.
9. **CallKit** (`callKitStarted/Ended/Failed`, `callKitError`), **audio session** (`sessionActivated`, interruptions, `routeChanged`,
   `micState`, `audioCategory`): no CallKit; the LiveKit audio session on Android is not instrumented. Only `cameraPaused`,
   `cameraResumed` and `scene` are recorded.
10. **Notice placement.** iOS shows the notice as a list section row; here it is a card above the chat list. The Mac's notice and
    the agent's `diagnostics.latest` read (`DeviceReads`, `diagnostics` device request, `compact()` is ported but unused) are not ported:
    Android has no agent device channel.
11. **Share** is the system chooser with the text in `EXTRA_TEXT` (no file): a very large text could hit the binder limit, but the
    screen is capped at 500 events (about 70 KB). **Copy** is auto-cleared after five minutes (SensitiveClipboard), which iOS's
    plain `Clipboard.copy` does not do.
12. **Logcat mirror** replaces `os.Logger`; Logcat is readable by `adb` on a debuggable build only for other apps' lines, so it is
    not claimed as private the way `privacy: .public` is documented on iOS: lines hold enum names and numbers only, as there.
13. **Not device-verified**: see Status. In particular the foreground check at launch, `ApplicationExitInfo` fields on real devices
    and the handler's write under real crashes.

## Journal
- 2026-11-16: ported from iOS (see Built). Tests and `assembleDebug` pass on the JVM build host; nothing run on a device.
  Found by the ported redactor tests: iOS's `.internal` device-host rule rewrote `kotlinx.coroutines.internal` (dropped here).
