# Phase 30 — The ways things open in the page host

Status: **step 3 of the org-page port.** Built and wired: `OrgPage.hiveApp`, the `Opening`/`LinkTap` results over the Stadeum model, a Hive app link
tapped anywhere in a message opening its page (one app-wide handler, iOS `\.openURL`), the page host at `MainTabs` level (any page closes when its workspace
leaves `live`), the agent's DM and a tribe's deliverable lines, and Talk cards opened through the same host. Compiles; `:app:testDebugUnitTest` passes.
**Never run on a device.** Open: `AgentJob`/tool lines/control messages of the agent's DM, `strut.proposals`, the Mac-only openings.

iOS read from `main` (1778b01, #551 merged): `OrgPage.swift`, `Stadeum.swift` (`Opening`, `opening`, `LinkTap`, `tap`, `opensFromTribe`), `ControlEnvelope.swift`
(`AgentArtifact`, `Handle`, `agentArtifacts`, `strutArtifacts`), `CallChat.swift` (`strutEntry`), `TalkCall.swift` (`TalkArtifacts`), `Labels.swift`
(`asksBeforeOpening`, `tribeAppQuestion`), `ChatRow.swift`, `MessageRows.swift` (`AgentArtifactLine`), `MainTabs.swift` (`openURL`), `TalkView.swift`
(`TalkOpener`), `AppsScreen.swift`, and the tests of each.

## Built
- **`engine/AgentArtifact.kt`** (pure): `AgentHandle` (`File(path)`, `App(app)`; `file()`/`app()` with iOS's validation: path characters, 512 bytes, ≤ 3 `/peers/<id>` hops,
  `jobs/<id>/files/…` or `artifacts/<run>/…`, no `.`/`..`/escaped `/`/`\`/control in file segments, run ids without `/`; app names and `@vN`; `isId`, `peer`,
  `appNameAndVersion`, `onOrg`), `AgentArtifact` (`parse`: `title` required, `id` else title, text cut at 200, https link with a host and no userinfo, `path` else `app`
  handle; `strutEntry` resolves `app`/`peer`+`job`+`via`/`url`/document-link against a workspace; `asksBeforeOpening`, `tribeAppQuestion`),
  `AgentMessages` (`artifacts(body)` = `metadata.peel.artifacts` of a talk message (no `op`), ≤ 20, metadata ≤ 64 KB; `strutArtifacts(body)` = `metadata.strut`).
- **`engine/OrgPage.kt`**: `OrgPage.hiveApp(url, hive)` (https on Hive's host, no port or userinfo, exactly `/w/<slug>/apps/<name>`, case-insensitive scheme/host, query/fragment
  ignored); `percentDecode` (Swift `removingPercentEncoding`; `StrutAppLink.decoded` now builds on it); `OrgPageRequest.opening(app, live)` goes through `Openings`.
- **`engine/Openings.kt`**: `Opening` (`Page`/`ConnectFirst`/`NotPaired(peer)`/`OnMac`), `LinkTap` (`Open`/`ConnectFirst(slug)`/`NotOurs`), `Openings(live, connected)` with `opening(page|handle|app)`,
  `tap(url)`, `opensFromTribe`, `workspaceName`, and `AppLinkHandler` (`.open` → page / "Connect @<slug> first" then the browser / the browser). `StadeumModel.openings`/`Stadeum.openings` give it the live
  workspaces and whether Stadeum is paired and not revoked.
- **`ui/apps/PageOpening.kt`**: `LocalLinkOpener` (app-wide, provided at `MainTabs`; `MessageText` uses it, else the browser) and `LocalPageOpener`; `openInBrowser`.
- **`ui/dashboard/MainTabs.kt`**: holds the one `orgPage`, shows `OrgPageScreen` over everything (the Talk call screen included: the session is the store's, so the call goes on behind it), drops it (and its token)
  when `Stadeum.live` no longer holds its workspace, provides the two locals. `AppsScreen` opens its apps through `LocalPageOpener` (no page state of its own).
- **`ui/apps/AgentArtifactLines.kt`**: iOS `AgentArtifactLine(s)`: `📄 Label: Title · place  Open`; where it is (workspace, the link's host, "Connect Stadeum in You to open", "@peer isn't connected here",
  "On your Mac"), a tap opens its page via `opening(handle, title)` (made again on the tap) else its link, and in a tribe an app asks first (`tribeAppQuestion`, Open app / Cancel).
- **`ui/dashboard/ConversationScreen.kt`**: under the text of a message that is not yours, not deleted, text: in the agent's DM (`chat == engine.agentPubkey`, which Android already has) its
  `metadata.peel.artifacts`; in a tribe its `metadata.strut` entries.
- **Talk**: `TalkArtifacts.Card` is now an `AgentArtifact`, resolved against the call's home (`batch(text, workspace)`; the old Android-only cards, which took a label for a missing title, a 400-character summary and
  no handle, are replaced by iOS's); the card rows are the same lines, opening on the org's strut over the call or the link in the browser.
- **Page-to-page links** (`OrgNavigation.window`, steps 1–2) are covered by tests here: a link to another app of the org (`?app=…&input=…`) loads in the same page, only on the person's tap, with the token,
  Back returning (the web view's own history); the title is the workspace while another app shows.

## Differences from iOS
- **Android shows control messages as the text they carry**: it has no control-envelope reader, so an agent's `request`/`ack`/`result` lines are ordinary bubbles (unchanged), and tools/job lines (`AgentJob`, `AgentToolLine`)
  are not drawn. Only the deliverable lines are.
- **No separate Safari sheet for a Talk card's link** (iOS `SFSafariViewController` over the call): the link goes through the app-wide handler, so a Hive link opens its page, any other the browser (the call stays up:
  it has its foreground service). A page opens in the same full-screen host as everywhere (a sheet on iOS), above the call screen.
- `AgentArtifact` text is cut at 200 characters (iOS: 200 graphemes); an https link is kept as written, not re-serialised.
- `Opening.NotPaired` vs `ConnectFirst` is decided by `link != null && !revoked`, as iOS's `link == nil || revoked`.
- `strut.proposals` (a tribe's proposal cards) and `AgentJob`/`AgentToolLine` are not ported.
- `Stadeum.opening(...)` returns the iOS cases; the Apps list still says "Connect <source> to open this app." (as step 1) for any case that is not a page, where iOS opens nothing.
- A `peel://open` public page and an org page can both be open (iOS replaces one with the other): they are separate hosts here.

## Tests
JVM/Robolectric: `AgentArtifactTest` (17: iOS `ControlEnvelopeTests`' deliverables/handles/apps tables, `CallChatTests`' strut entries, `ControlRowTests`' tribe entries and ask-first, `OrgPageTests`' `onOrg`),
`OpeningsTest` (19: `hiveApp` tables incl. share-link round trip, `opening` cases, the `tap` table, `opensFromTribe`, the app-wide handler, an artifact line's place and action, a link to another app in the page and
the title rule, liveness), `TalkTest` (artifacts section replaced by iOS `TalkArtifactsTests`), `MessageTextLinkTest` (2: a tapped link in a message reaches the handler: page, connect-first, system).
### UNTESTED (needs a device)
- The composables: `AgentArtifactLines` drawing and tapping, the ask-first dialog, the page host over the Talk call screen, `MainTabs` closing the page as `live` changes (the `isLive` rule is tested), the
  agent's DM and a tribe with real messages, a real Hive link opening a real workspace's app.
