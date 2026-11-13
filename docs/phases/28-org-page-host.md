# Phase 28 — The org-page host

Status: **step 1 of a port of iOS's org page** (`Apps/iOS/Peel/OrgPageSheet.swift`, `Sources/PeelUI/OrgPageRules.swift`,
`Sources/PeelUI/Stadeum.swift` `OrgPageRequest`, `Sources/PeelCore/OrgPage.swift`). Built: the pure model and rules, the web
view host that applies them, the "close when the workspace is no longer live" rule. Compiles; `./gradlew :app:testDebugUnitTest`
passes (JVM + Robolectric). **Never run on a device or against a real strut**: the document-start script, profiles, new windows
and downloads are listed as untested below. Open (later steps): the app wallet's bridge on this page (iOS `OrgWalletBridge`),
opening a page from a Hive link, an agent's deliverable or a tapped link (`OrgPage.hiveApp`, `AgentArtifact.Handle.onOrg`,
`Stadeum.tap`), and the page-to-page link (`StrutAppLink` is parsed and used for the new-window rule; opening from a chat is step 3).

An org page is strut's UI on a workspace's own strut showing one thing alone (`?app=<name>&solo=1`, `?artifact=…`), opened with
the person's token for that workspace, which is handed over twice and never in an address: as the bearer of the first request
(a swarm's gate wants it for the page itself) and in the page's own `sessionStorage["strut/apiKey"]` before its script runs.

## Built
- **`engine/OrgPage.kt`** (pure, JVM-tested): `UrlParts` (RFC 3986 split, no `java.net.URI`/`android.net.Uri`: lenient hosts such
  as `my_host`, `origin(url)` as `location.origin` reads it), `StrutUI` (`page(base, name, value, key, input)`, `page(page, input)`,
  `isPage(url, folder)`, `encoded`: unreserved characters only), `StrutAppHandle` (the app shape of iOS's `AgentArtifact.Handle`:
  `isPeerId`, `isAppName`, `of`, `peer`, `nameAndVersion`), `StrutAppLink` (+ `Source` own/peer/org, `parse`, `LONGEST_INPUT`;
  `decoded` = `URLSearchParams` + Swift's strict `removingPercentEncoding`), `OrgPage`, `OrgRequest` (url + bearer, redacted
  `toString`), `OrgPageRequest` (replaces the old `AppPage`: `origin`, `folder`, `serves`, `request`, `request(at)`,
  `request(link)`, `appLink`, `shownApp`, `handOver`, `title(showingAnotherApp)`, `showsAnotherApp`, `reload`, `retry`, `isLive`,
  `opening(app, live)`; `toString` = "org page on <workspace>", equality by `id` as on iOS), and `OrgNavigation` (the navigation
  policy and the new-window rule as plain functions).
- **`engine/OrgPageRules.kt`** (pure): `mayDownload`, `filename`, `failure(status)` and every word iOS shows (`Couldn't open it`,
  `Try again`, `Couldn't save it`, `There was no room to keep it.`, `The download did not finish.`), `suggestedName` (a
  server's `Content-Disposition`/path, iOS's `suggestedFilename`).
- **`engine/OrgDownloads.kt`**: one folder per download under `cacheDir/org-downloads/`, redirects followed by hand and only
  inside what the page serves, a failed download removes its folder, `cleanUp()` removes all. Tested against a local JDK server.
- **`ui/apps/OrgPageHost.kt`**: `OrgPageScreen` (Done, Back when the page can go back, Reload, the title, linear progress,
  failure screen with Try again, the notice dialog), `OrgPageController` (iOS `OrgPageController`), `OrgPageClient` and
  `OrgPageChromeClient` (iOS `OrgWebView.Coordinator`), `makeOrgWebView`. `AppsScreen` opens it instead of the old `AppPageScreen`.
- **`res/xml/file_paths.xml`**: `org_downloads` (`cache-path` `org-downloads/`) for handing a download to the share sheet.

## How the web view behaves (iOS → Android)
- **Hand-over before the page's script**: `WebViewCompat.addDocumentStartJavaScript(web, mainFrameHandOver, {origin})`;
  `mainFrameHandOver` is iOS's `handOver` wrapped in `if (window.top === window) { … }`, because Android's hook offers frames of the
  origin too where `WKUserScript(forMainFrameOnly:)` does not. If `DOCUMENT_START_SCRIPT` is unsupported the same script runs
  from `onPageStarted` and `onPageCommitVisible` (it checks origin and folder itself); that is **not guaranteed to be before the
  page's own script** — a page whose first script reads the key at once can miss it (it then 401s and shows the failure screen,
  and Try again/Reload work once the key is in place). Android System WebView 85+ (which `androidx.webkit` needs for the hook) has it in practice.
- **Bearer**: the first request, a reload, Try again, a new web view after the process died, and any main-frame GET navigation to
  strut's UI in the folder (`request(at:)`, `shouldOverrideUrlLoading` reloads it with the header, at most five in a row so a
  server redirecting to itself cannot loop). Files are never given the bearer (`request(at:)` is null for them).
  iOS does not re-issue ordinary in-page navigations with the bearer (it relies on the key in `sessionStorage`); Android does, as asked,
  since a header does not survive a redirect.
- **Navigation policy** (`OrgNavigation.decide`, = iOS `policy(for:)`): served (same origin and path under the folder) loads;
  `about:` loads; `data:`/`blob:` cancelled for the main frame, allowed in frames; `http(s)` elsewhere cancelled, and a link from the main
  frame with a gesture within 1 s of a touch goes to the system browser (`ACTION_VIEW`); every other scheme cancelled.
- **New windows** (`target=_blank`, `window.open`): `setSupportMultipleWindows(true)` and `onCreateWindow` hands the page a throwaway
  web view with network loads blocked that only reports where it was sent; then `OrgNavigation.window`: served UI → another app of
  the org's as that app's page alone *with the token and only on the person's tap* (`StrutAppLink` from the page it came from,
  which must itself show an app); a served file → loaded here with no bearer; anything else of strut's UI → nothing; elsewhere →
  the system browser, on a tap. Never a window.
- **Nothing kept**: when `WebViewFeature.MULTI_PROFILE` is supported the page gets a profile of its own (`peel_org_<uuid>`, set before
  anything else is done to the web view), deleted when the page closes (the web view is destroyed first; if deletion fails the
  shared data is wiped). Otherwise the shared `CookieManager`/`WebStorage`/cache/history are wiped before and after, as the public page does.
- **Downloads**: `DownloadListener` → only if `OrgPageRules.mayDownload(true, true, served)`; `OrgDownloads.save` (OkHttp: the
  user agent and the web view's cookies for the address, **no bearer**, as iOS's `WKDownload` carries only the navigation) into a
  folder of its own, then `ACTION_SEND` through the app's `FileProvider`; failures say what iOS says; folders go when the page closes.
- **Failures**: a main-frame HTTP status ≥ 400 (`onReceivedHttpError`) or a main-frame load error (`onReceivedError`, `ERR_ABORTED`
  ignored) shows the failure screen with `OrgPageRules.failure`'s words (the load error's own description for the latter, as iOS's
  `localizedDescription`); `onPageFinished` clears it only when the load did not itself fail; Try again = `OrgPageRequest.retry`.
- **Process death**: `onRenderProcessGone` destroys the web view and a new one is made (`key(generation)`) loading `request(at: lastUrl) ?: request`.
- **Title**: the app's/artifact's own title else the workspace (`title(showingAnotherApp)`); another app a link opened shows the workspace.
- **Closing when its workspace is no longer live**: `AppsScreen` reads `Stadeum.live` (Compose state); the page shown is
  `page?.takeIf { it.isLive(live) }`, an effect drops `page` (and with it the token), and `OrgPageScreen`'s dispose closes the web view,
  its profile and its downloads (iOS `MainTabs` `.onChange(of: model.stadeum.live)`).

## Differences from iOS (deliberate or forced)
- **Source frame unknown.** WebKit tells iOS which frame a link or `window.open` came from; Android's `onCreateWindow` does not. So
  everything a new window can do needs the person's own tap (`isUserGesture` and a touch in the last second); iOS lets a main-frame
  page open a *file* in place with no tap. Likewise `shouldOverrideUrlLoading`'s `isForMainFrame` stands in for "from the main
  frame" for the system-browser link, and a download's frame is unknown (the `served` check still applies, `isMainFrame`/
  `sourceIsMainFrame` are passed as true).
- **A failed status does not cancel the response** (WebKit lets iOS cancel it): the page's body loads under the failure screen,
  which covers it.
- **`window.top` guard** around the hand-over (above). **The token is also re-sent on in-folder navigations** (above).
- **IPv6 origins** keep their brackets (`http://[::1]:3000`, what `location.origin` is); iOS's `URL.host()` drops them.
- **`StrutUI.page(page, input:)`** leaves no `?` when no query is left; Foundation leaves an empty one. A base with no host has no page (iOS builds one).
- **`filename`** cuts at 200 code points (iOS 200 characters). `$` in Swift's name regexes also matches before a final newline; here it does not (stricter).
- No iOS "swipe back" gesture (system Back and the Back button instead); no per-page link preview to turn off; `TouchWebView`'s
  `acted(within:)` for the wallet's sheets belongs to the wallet bridge (not in this step).
- **Not ported yet** (later steps): the app wallet bridge on this page (`OrgWalletBridge`, `PhoneWalletSheets`), `OrgPage.hiveApp`,
  `Handle.onOrg`/file handles, `Stadeum.opening(_:)`'s `connectFirst/notPaired/onMac` results (`opening` returns null; the Apps list says "Connect … to open this app.").

## Tests
JVM: `OrgPageTest` (28: iOS `OrgPageTests`' address tests, `StrutAppLinkTests` in full, `StadeumTests`' page tests,
the exact `handOver` script, serves/origin edge cases, title/reload/retry/liveness, the navigation and new-window tables),
`OrgPageRulesTest` (6: iOS `OrgPageRulesTests` plus the exact failure/notice wording and `suggestedName`), `OrgDownloadsTest` (7: a
local HTTP server), `WorkspaceAppsTest` (`opening` now `OrgPageRequest.opening`). Robolectric: `OrgPageHostTest` (16: the
client/chrome client/controller on a Robolectric `WebView`: what loads and with which headers, the system browser only on a tap,
failure words and Try again, process death, new-window routing, cleanup).

### UNTESTED (needs a device or a real strut)
- The Compose screen (`OrgPageScreen`): toolbar, title, progress bar, failure overlay, notice dialog, share sheet, Back handling.
- `makeOrgWebView` itself: `addDocumentStartJavaScript` running the hand-over before the page's script (and the `onPageStarted` fallback),
  `MULTI_PROFILE` profiles (`setProfile` before use, `deleteProfile` after destroy), cookies, `DownloadListener` against a real strut file,
  the `FileProvider` share, `onCreateWindow`'s throwaway web view reporting `window.open`/`target=_blank` URLs (some WebView versions report
  only via `onPageStarted`; both are handled), a real `onRenderProcessGone`.
- Reaching a real workspace (a swarm's `/lab/` gate with the bearer; strut's UI reading `strut/apiKey`).

## Journal
- Step 1 written after reading the iOS sources above and their tests line by line; the iOS test tables were ported one-for-one.
  `AppPage` (PR #74) became `OrgPageRequest`; `AppsScreen`'s page screen became `OrgPageScreen`.
