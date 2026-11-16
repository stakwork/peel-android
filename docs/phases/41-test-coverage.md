# Phase 41 — test coverage for the ported-but-untested paths

Status: **built.** Compose UI tests under Robolectric for the wallet sheets, Connected apps, the agent-artifact lines, the Apps rows and Talk history;
Robolectric WebView tests for `OrgPageScreen` and the whole navigation table; the Talk-history save on hang-up; and an `androidTest` source set
(signing vectors, payment-runner smoke tests, a Storage upgrade on real SQLite, a real WebView over MockWebServer). **The instrumented tests only
compile here: there is no emulator, so none of them has ever run.** Open: run them on a device (`./gradlew :app:connectedDebugAndroidTest`).

## Built

### Setup
- `androidx.compose.ui:ui-test-junit4` (testImplementation) and `ui-test-manifest` (debugImplementation) through the version catalog, versions from the
  existing Compose BOM; `testOptions.unitTests.isIncludeAndroidResources = true`.
- `app/src/test/resources/robolectric.properties` pins `sdk=35`: with Android resources included Robolectric reads `targetSdk` 36 from the manifest,
  and SDK 36 needs Java 21 (this pod has 17). Existing Robolectric tests are unaffected.
- `androidTest` source set: `AndroidJUnitRunner`, `androidx.test` runner/ext-junit/core, `okhttp` `mockwebserver`, and `src/test/resources` added as
  androidTest resources so the instrumented signing test reads the same `wallet-vectors.json`.

### JVM / Robolectric (run, passing)
- `ui/page/WalletSheetsUiTest` (12): the sign-in-only connect sheet (its words from `WalletWords`, no wallet sections, the yes disabled for the first 699 ms
  and enabled after, a click held by the gate until its own clock agrees, the domain key chosen, the no, Back = decline, a touch outside leaves it
  up); the wallet connect sheet (what it may do, the limit sentence, the over-limit choice reaching the answer); the payment sheet (two taps, the
  second only after 700 ms and only once the gate's clock agrees, Cancel back to step one, Decline, Back); the credit sheet (the seller's words, its own
  armed note, two taps, Back).
- `ui/you/ConnectedAppsUiTest` (7): empty text, a connection's words, a sign-in alone has no limit rows, limit edit + Save writes storage, an empty limit
  clears it, the over-limit choice, Disconnect — over a real (Robolectric SQLite) `Storage`.
- `ui/apps/AgentArtifactLinesUiTest` (6): a line's words, a tap opening the page with the bearer, the tribe ask-first dialog (question text, Cancel opens
  nothing, Open app opens), a link through `LocalLinkOpener`, a line that cannot open.
- `ui/apps/AppsRowsUiTest` (7): `AppRow` and `TalkRow` (words, tap, long-press menus: Share link / New conversation / History), icons drawing for a
  lucide name, a renamed one, an unknown one and none.
- `ui/talk/TalkHistoryScreenUiTest` (4): empty text, a saved conversation as a row, opening its transcript, Back from the transcript then out, Talk
  from the toolbar and a row, another home's sessions not listed. `ui/talk/TalkStoreSaveTest` (3): what `hangUp` saves.
- `ui/apps/OrgPageScreenUiTest` (8): `OrgPageScreen` composed over Robolectric's WebView: first load with the bearer, Done, Reload, the failure overlay and
  Try again, a frame's failure showing nothing, `onRenderProcessGone` making a new web view that loads where it was, the download listener
  (a foreign URL ignored; a strut file reaching `OrgDownloads` and its failure shown in the notice dialog), the public page's "can't offer Sign
  in" note.
- `ui/apps/OrgNavigationTableTest` (6): the iOS table row by row (serves, about, data, blob, external link with and without a tap, every other scheme,
  look-alike hosts) for the main frame and a sub-frame.
- Wallet bridge reply path (RESTRICTED not-answered reply, id echo, push) was already covered by `OrgWalletBridgeTest`; not duplicated.

### Instrumented (`app/src/androidTest`, **compiled only, never run**)
- `engine/SigningVectorsInstrumentedTest`: every `sign_in_signatures` vector with a `signer` (signBytes + pubkeyFromSeed against the real core), LUD-05 domain
  keys for `site.com` / `app.example.com` of "abandon … about", root signing with the account key. (The `@Ignore`d JVM cases stay as they are.)
- `engine/PaymentRunnerInstrumentedTest`: `paymentRunner()` reflects the engine; an unreadable invoice throws; an unconnected keysend is refused and records
  nothing (expectation read from the code, accepts `NOT_CONNECTED` or the core's `PAYMENT_FAILED`).
- `storage/StorageUpgradeInstrumentedTest`: a v13 file upgraded by `Storage` on the device's SQLite. **main is at v14; there is no v15 here**, so it checks
  `>= 14`.
- `ui/PageHostWebViewInstrumentedTest`: a real WebView loading a MockWebServer page (http on localhost, no `file://`): a document-start script
  running before the page's script (`sessionStorage`), and a `strutWallet.postMessage` promise round trip through `addWebMessageListener`. It uses a copy of
  the shim's contract, not the production constant.

## Seams introduced (no behaviour change)
- `WalletSheets`, `WalletConnectPhoneSheet`, `WalletPaymentPhoneSheet`, `WalletCreditPhoneSheet`: a `clock: () -> Long` parameter defaulting to
  `SystemClock.elapsedRealtime()` (the `ArmingGate`/`WalletPayStep` clock), because Robolectric's `SystemClock` does not follow Compose's test clock.
- `ConnectedAppsScreen`, `AppRow`, `TalkRow`: `private` → `internal`.
- `AgentArtifactLines`: an `openings: () -> Openings = { Stadeum.openings }` parameter (the singleton's `link` has a private setter).
- `TalkStore.saveTranscript(...)` (companion, `internal`): the body of `hangUp`'s save, moved out unchanged so it runs without a LiveKit room.

## Untested
- Everything instrumented above, until run on a device.
- `AppsScreen` as a whole: it reads the `Stadeum` singleton and the network (`WorkspaceApp.pinned`); only its rows are driven. The pull gesture is not.
- `addWebMessageListener` / document-start script under Robolectric (the feature checks say unsupported): the bridge's listener and the notification push into a
  page are covered through `OrgWalletBridge.envelope`/`push` with fakes only (`OrgWalletBridgeTest`); the real path is the instrumented test.
- A tap outside a sheet: the test sends the touch to the dialog and checks it is still showing; Robolectric's dialog window may not hit-test outside, so this is a weak check.
- `TalkStore.hangUp` with a live room: only the extracted save is tested.
- MessageNotifier skipping: its PR is not in main; skipped.
- Layout, keyboard, TalkBack, the real 700 ms feel on a device.

## Journal
- Robolectric under `isIncludeAndroidResources` defaulted to SDK 36 and failed to start (Java 17); fixed with `robolectric.properties`.
- A Compose test with `mainClock.autoAdvance = false` plus `performScrollTo` stalled and ran out of memory; the one test that needs a frozen clock freezes it
  only around its assertions.
