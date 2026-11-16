# Phase 29 — One page host, and the app wallet on org pages

Status: **step 2 of the org-page port.** Built: one web-view host for both an org's page and a stranger's public page
(iOS `OrgPageSheet<Page: PhonePage>`), the app wallet's bridge on both (iOS `OrgWalletBridge`), `WalletScope` and the
`WalletCaller(scope, place, personActed, person)` of iOS, the push plumbing. Compiles; `:app:testDebugUnitTest` passes
(731 tests, 3 skipped as before). **Never run on a device.** Open: the producers of wallet notifications and the spending
wallet itself (step 6), opening a page from a chat or link (step 3).

Source read: this checkout of `peel` (`fix/blank-timeline-new-messages-rescue`) predates iOS PR #551, so `PhonePage`, `PublicPage` and the
generic sheet were read from `origin/feature/sphinx-sign-in` (`git show`, read-only): `Apps/iOS/Peel/OrgPageSheet.swift`,
`OrgWallet.swift`, `Sources/PeelUI/PublicPage.swift`, `Sources/PeelCore/WalletHost.swift`, `Apps/Shared/StrutWallet.swift`.
strut itself is not on this machine, so strut's `kit/src/wallet/relay.ts` was not read: `notify` is called with the JSON text, as iOS does.

## Built
- **`engine/PhonePage.kt`**: `interface PhonePage` (url, origin, request, request(at), serves, script, isUIPage, appLink, request(link), shownApp,
  title(showingAnotherApp), walletScope, walletPlace; `showsAnotherApp`, `reload`, `retry` are shared defaults, moved here from `OrgPageRequest`),
  `PublicPage(link)` (iOS `PublicPage`: serves = same origin, request with no credential, `script`/`appLink`/`request(link)`/`shownApp` null/false,
  `title` = host, scope `Origin(origin)`, place = host). `OrgPageRequest` implements it (`script` = the main-frame hand-over, scope `Org(peer)`,
  place = workspace). `OrgRequest`'s token is optional (no token: no headers). `OrgNavigation.decide/window` take a `PhonePage`.
- **`engine/WalletHost.kt`**: `WalletScope` (`Org(peer)` key `org:<peer>`, `Origin(origin)` key `origin:<origin>`; `visitorHost` null for org, the origin's
  host for origin; `byOrg` true for both), `WalletCaller(scope, place, personActed, person)` (the person moved from the host's constructor to
  the caller, as iOS), `WalletPush(connection, app, envelope)`, `WalletHost.notifications: Flow<WalletPush>` (+ `publish`). `host_capability`
  answers `ops: []` for an org and `["sign_in"]` for an origin (`wallet: "Peel"` for both); `sign_in` from an org is `RESTRICTED` "Peel signs you in
  only to a public app it opened itself." (before anything else, held connection or not); `buy_credit*` from an org is `RESTRICTED` "Peel buys credit
  only for a public app it opened itself."; the sign-in check names `visitorHost`, the sheet and the silent-sign-in notice name it too, the saved
  connection's place is the caller's. Origin-scope behaviour is otherwise PR #74's. `Engine.walletHost` is one host per account (one sheet at a time across pages).
- **`engine/StrutWallet.kt`** (pure): `GESTURE_WINDOW_MS` (5 s; links out stay 1 s), `personActed`, `mayAnswer(isMainFrame, sourceOrigin, origin)` (scheme, host, port with
  a default port written or not), `mayPush(currentUrl, origin)`, `pushScript(message)`, `scriptLiteral`, `push(envelope, into: ScriptTarget, origin)`.
- **`ui/apps/OrgWalletBridge.kt`**: the `peelWallet` listener + document-start shim of PR #74 (moved here unchanged, same `{id, body}` → `{id, reply}`,
  same `RESTRICTED` "Peel does not answer there." in `WalletErrors`' shape), now for any page; `envelope(...)` answers only the main frame at the page's
  origin **while the web view shows what the page serves** (iOS `serves(webView.url)`, which #74 lacked); `push` sends a notification only for the page's
  `walletScope.key`, via `evaluateJavascript` on the page's world.
- **`ui/apps/OrgPageHost.kt`**: `OrgPageScreen(page: PhonePage, wallet: WalletHost, onClose)`, the step 1 host made generic; installs the bridge, collects
  `wallet.notifications`, shows the wallet's sheet and its notice. **`ui/page/PublicPageScreen.kt` is deleted**; `MainActivity` opens `PublicPage(link)` in
  `OrgPageScreen` (the router is unchanged, it still holds the link); `AppsScreen(wallet, …)` opens an org's.
- **`ui/page/SignInSheet.kt`**: `SignInSheets` → `PhoneWalletSheets` (iOS name) with `toast`/`show(words)` (2.5 s, announced to TalkBack): the one mechanism for
  "a connection did something with no sheet" (the silent sign-in notice). The old app-wide `LocalToast` use for sign-in is gone.

## Differences from iOS
- **Scopes**: only `Org` and `Origin`; the Mac's `.app` and `.remote` scopes (an app on Peel's own strut; a stranger's app that strut hosts) do not exist on Android.
- **No spending wallet** (so `connect`/`pay` sheets, the `PhoneWalletSheets` payment/credit cases and `Connected apps` for org connections are not here): an org
  page's `capability` answers like a sign-in-only connection (`methods: []`, `connected: false`), `call` is `NOT_IMPLEMENTED`; no `buy_credit*` (an origin's
  is not advertised and falls through to PR #74's `BAD_REQUEST`; an org's is `RESTRICTED` as iOS).
- **A page's failure** now shows the iOS failure screen with Try again for a public page too (#74 showed "Couldn't open <host>."); the public page also gets Reload, Back,
  progress, downloads and the `window.open` rules (#74: new windows off). A `window.open` on a public page to its own origin, on a tap, loads here as a file does (iOS
  does the same); one to another site goes out on a tap.
- **The bridge's not-answered reply** is `RESTRICTED` in the wallet's error shape (the shim from #74), where iOS rejects the promise with "not answered".
- **Push**: iOS hands strut the JSON text; so does this (`notify("<json text>")`), the literal built by `scriptLiteral` (not `JSONObject.quote`, which does not
  escape `<`, U+2028/9 or lone surrogates). `notify` is not wrapped in `JSON.parse` because iOS does not.
- **Source frame of a `window.open`** is still unknown on Android (step 1's note); unchanged.
- The wallet note "This device's web view can't offer Sign in with Sphinx." shows only on a public page (an org's wallet offers no ops).

## Tests
JVM/Robolectric: `WalletHostTest` (29: PR #74's, adapted to the caller, plus scope keys and visitor hosts, `host_capability` per scope, org `sign_in` and
`buy_credit*` `RESTRICTED`, the caller's person, the connection key, notifications fan-out), `StrutWalletTest` (9: `mayAnswer` incl. default ports and opaque
origins, `mayPush`, the push script with hostile strings: quotes, backslashes, `</script>`, `<!--`, U+2028/9, control characters, lone surrogates, template and
quote breakers, each read back to the exact message), `PhonePageTest` (11: both pages, the one policy for both), `OrgWalletBridgeTest` (8: who is answered, scope and
place from Peel's record, the tap, org sign-in refused, push filtering by connection key, a published notification reaching the web view's script call through a fake
target), `PublicPageHostTest` (6: the host with a public page), `OrgPageTest` (28, adapted), `OrgPageHostTest` (16, unchanged).

### UNTESTED (needs a device)
- `addWebMessageListener` + the shim actually reaching `peelWallet` in a page on an org's origin and a stranger's; `evaluateJavascript` running the push in a real page.
- The composable (sheet, toast overlay, the note), `PhoneWalletSheets` timing, and a sign-in through a real strut app. Everything in phase 28's UNTESTED list still applies.
