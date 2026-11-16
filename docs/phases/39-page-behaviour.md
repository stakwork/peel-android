# Phase 39 — page behaviour (the Apps list, one page holder, Talk cards, Back on wallet sheets)

## Status
Built (JVM/Robolectric-tested; the Compose parts compiled only). iOS read: `AppsScreen.swift`, `PhoneRouter.swift`, `MainTabs.swift`, `PeelPhoneApp.swift`, `TalkView.swift`, `MessageRows.swift` (`AgentArtifactLine.open`), `OrgWallet.swift`.

## Built
- **Apps list (`engine/AppsOpenDecision.kt`, used by `ui/apps/AppsScreen.kt`).** iOS: a tap is `if case .page(let page) = stadeum.opening(app) { router.orgPage = page }` — an app whose workspace is not
  connected opens nothing and says nothing. The "Connect … to open this app." toast is gone. The list shows `apps.filter { peer in live }` (`visible`), the unread names are the *live* workspaces that failed (`unreadNames`, ids kept, as `unreadApps`),
  and the empty/unread words are iOS's (`emptyText`, `didNotAnswer`, `unreadNote`) — they already matched, now one tested source. The Hive-link path (`Connect @slug first`, then the browser) is `AppLinkHandler` from `MainTabs`' link opener, unchanged.
- **One holder of the pages (`ui/page/PublicPageRouter.kt`)** = iOS `PhoneRouter`'s `orgPage`/`publicPage`/`waitingLink`: `open(link, ready)` (ready: `orgPage = nil`, public page set; not ready: only the waiting link is set, nothing else touched, as iOS),
  `openWaitingLink()` (clears the org page), `openOrg` (iOS `router.orgPage = …`; **does not touch a public page** — nothing in iOS does), `workspacesChanged(live)` (iOS `MainTabs` `.onChange(of: live)`), `locked()` (iOS `PeelPhoneApp`: both pages nil, waiting link kept),
  `reset()` (all three). `MainTabs` takes the router (`pages`) instead of its own `orgPage` state, so a lock/sign-out clears the org page the same way. A `peel://` link replaces an org page; an org page opened later does not close a public page (iOS: same).
- **Talk cards (`ui/talk/TalkOpener.kt`, `LocalTalkOpener`).** iOS `TalkView` gives the call screen its own `TalkOpener` (the root's sheet does not show over its full-screen cover): `AgentArtifactLine` sets `opener.opened = .page(page)` or `.link(url)` when the opener exists, else `router.orgPage` / `openURL`.
  The sheet closes when its workspace leaves `live` (`.onChange(of: live)`) and when the call screen goes. Android: `MainTabs` holds a `TalkOpener` while the Talk screen is up and provides it; a card's page goes to it (not to the router), is shown by the one page host
  (`OrgPageScreen`) over the call screen, and closes with `workspacesChanged`/`close()` and when Talk ends. A card's link goes **straight to the system browser, without the Hive-link handler** (iOS `TalkSafariView` gets the URL as is); elsewhere it still goes through the app handler. The call stays up (foreground service).
- **Back on wallet sheets.** iOS has `.interactiveDismissDisabled()`, explicit "Don't connect"/"Decline" and no yes by dismissal. Android's `WalletSheets` dialogs already map Back (and nothing else: no outside tap) to `done(null)`/`done(false)` = the cancel button, and the slot
  (`PhoneWalletSheets`) turns every unanswered/closed/late answer into the empty answer. Tests now pin it (below). No code change needed there.

## Differences from iOS
- A Talk card's page is not a sheet with a drag handle but the page host's full-screen overlay (as every page here, phase 30/31); closing is its Back/close button. WebView inside a `ModalBottomSheet` fights the sheet's drag, so no sheet chrome.
- A Talk card's link opens in the system browser (no in-app Safari view): `androidx.browser` (Custom Tabs) is not a dependency. The call goes on because of its service; the app stays behind the browser.
- iOS lets an org page and a public page both be "set" (two SwiftUI sheets, the second waits for the first); here both would draw, one over the other, if it ever happened. It cannot by taps (each page covers the screen); only a link arriving while an org page is up does it, and then iOS closes the org page, as here.
- Wallet sheets: Back is the cancel (a system gesture iOS has no equivalent of); `stillOpen` is "the page not closed" (phase 34), not `phase == .ready`.
- Re-read of the 29/30/31/34 lists for phone rules with a concrete iOS rule: `createWebViewWith`/`follow` and the title/back affordance are already ported in `OrgNavigation`/`OrgPageHost` (phase 29); what is left there is source-frame information WebView does not give, a system gesture, or Mac-only (`.app`/`.remote` scopes) — skipped, not page behaviour of a phone.

## Tests
`AppsOpenDecisionTest` (5), `PublicPageRouterTest` (11: the old four plus the replace/keep/lock/reset/live rules), `TalkOpenerTest` (3), `PhoneWalletSheetsTest` (13, two new: Back = don't connect/decline, host cancel takes the sheet down).

### UNTESTED (needs a device)
- The composables: the Apps tap doing nothing, the Talk card's page over the call screen and Back from it, the browser opening for a card's link with the call up, wallet dialogs' Back key.
