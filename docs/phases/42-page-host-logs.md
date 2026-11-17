# Phase 42 — Page host: a blank page on a phone, its logs and its two causes

Status: **cause found on the phone and fixed; verified on that phone.** There were two causes (see "The cause", below): the
WebView's WRAP_CONTENT layout height made `100vh` zero, and a new page kept the last page's emptied AndroidView. Both are fixed.
With the fixes, Peel Dev (a debug build of this branch plus #102, on the Pixel 9a below) draws strut's worker app, and draws
example.com opened over it. Release 0.1.6 still has both bugs until the next release. The logs below, and three other paths
fixed from the code alone (not seen on the phone), came first. Tests and lint pass locally (Java 21); the only local failure is
`ConversationDateTest.todayShowsTime`, which is Java 21's narrow no-break space before PM and passes in CI on Java 17.

## What was seen
On a Pixel 9a (GrapheneOS, Android 16, WebView `app.vanadium.webview` 140.0.7339.35), Peel 0.1.6 (release APK):
- `peel://open?url=https://strut.stadeum1.sphinx.chat/?app=strut/worker&solo=1&public=1` and `peel://open?url=https://example.com/`
  both show only the page's header (Done, the host, Reload) over an empty body in Peel's background colour. There is no progress
  bar and no failure screen.
- The same address renders in the phone's own Vanadium browser, which uses the same engine.
- uiautomator shows the AndroidView's holder laid out full size, with no `android.webkit.WebView` node under it.
- logcat shows a sandboxed renderer starting for Peel and no navigation error, console message or crash. Reload starts no renderer
  and logs nothing. Right after the first link, `ActivityManager: Killing <renderer>: isolated not needed` and chromium's
  `Renderer process crash detected (code -1)` were logged.
- The page host (phase 29/30) had never been run on a device before.

## The cause (found on the phone)
With Peel Dev (debuggable, so its WebView could be inspected over adb with the DevTools protocol) on the same phone:
1. **Strut's worker app: `100vh` was 0.** The page loaded (`pageStarted`, `pageFinished`, `commitVisible`; strut's own scripts
   logging to the console). Chromium called it attached, visible and 1080x2041. But every element from `<html>` down to the app's
   iframe had a computed height of 0px, while `innerHeight` was 777. In that page, `100vh` and `100dvh` measured 0, and a fixed
   `height:100%` measured 777. strut's solo layout is `.app-solo { height: 100vh }`. What showed was the page's own near-black
   body under Peel's header. The reason: `AndroidView` gives a view without layout params WRAP_CONTENT, and a WebView whose
   layout height is WRAP_CONTENT lays the page out on a zero-height viewport (it sizes itself to its content). Fix:
   `makeOrgWebView` gives the WebView MATCH_PARENT. Accompanist's WebView does the same, for this reason.
2. **A page opened over another: no web view at all.** example.com opened while the worker app showed logged the old page's
   `close` and no new `webView`. `OrgPageScreen` keyed its `AndroidView` on `controller.generation` alone, which is 0 for every
   new page. So Compose kept the old `AndroidView` (its factory never ran again), while the old controller's dispose destroyed
   that view's WebView and took it out. The result was an empty holder, a controller with no web view, no failure screen, and a
   Reload that did nothing (it returned at `web ?: return`). That matches the "no WebView node" uiautomator showed. Fix: key on
   the controller too. The same applied to an org page opened over another one in `MainTabs`.
The per-page profile was not a cause: `MULTI_PROFILE` works on this WebView (`profile=on`, and the page loads).

## The camera (strut's camera sheet), found next
Once the worker app drew and the worker signed in, its first task ("Photograph a legal filing") said "Camera: Permission denied".
The page host had no `onPermissionRequest`, so WebView denied every request, and Peel did not hold `CAMERA` yet. Ported from iOS
(`OrgPageSheet`'s `requestMediaCapturePermissionFor`): `engine/PageMedia.kt` (pure) grants the camera and the microphone to the
origin Peel loaded and nothing else. An app's frame is sandboxed with an opaque origin, so it never matches. Android's request
names no frame (iOS also checks it is the main frame), so a same-origin frame of strut's own would match. Strut's kit draws the
camera in its own page. `OrgPageChromeClient.onPermissionRequest` asks the system for `CAMERA`/`RECORD_AUDIO` only when the app
lacks it, through the page screen's `RequestMultiplePermissions` launcher (`OrgPageController.askSystem`). The system's own prompt
is the only one, as on iOS. Each request is logged (`permission <origin> asked= granted=`). On the phone: allowed at the prompt,
`granted=VIDEO_CAPTURE`, and the task's camera opened. Before the camera's first frame the sheet showed WebView's default video poster,
a big grey play button stretched across the screen; `OrgPageChromeClient.getDefaultVideoPoster` is now a transparent pixel.

## A public site keeps its visitor signed in
Every open used to start from a profile made for it and deleted at close, so strut's 30-day visitor session (a cookie and
`strut.visitorSession:<base>` in localStorage) went with the page, and the worker signed in again at every open. Ported from iOS
(stakwork/peel f8875ad, `PublicPage.storeIdentifier`): a `PublicPage` keeps its cookies and storage in a profile of its origin's,
`PhonePage.keptProfile` = `peel_public_<RFC 9562 v5 UUID over the origin, the one iOS names its store by>`. It is the same on every
open, and no site's is another's; closing the page keeps it (`close profile=kept`). An org's page still keeps nothing (a profile
per open, deleted at close): Peel hands it the token each time. Forget this account empties and deletes every kept profile
(`forgetKeptWebData`, iOS `forgetWebsiteData`). Signing out in the page clears the session, as strut does it. Without
`MULTI_PROFILE` nothing is kept (the shared data is wiped at every open, as before).

## A page's file input
The same task offers "or choose one from this device", an `<input type=file>`. WebKit shows its own picker on iOS; Android's
WebView shows nothing unless the app does. `OrgPageChromeClient.onShowFileChooser` hands it to the page screen
(`OrgPageController.chooseFiles`), which launches the system's picker from `FileChooserParams.createIntent()`, so the accept types
and multiple selection are the page's. The page gets what the person picked (`pickedFiles`: one URI or several as clip data), or
null on a cancel, exactly once; one picker at a time; a page that closes tells a waiting input null. Logged as
`fileChooser accept= multiple= shown=` and `files picked=<n>` (counts, never names).

## Which paths give "a web view, nothing painted, no failure screen, Reload does nothing" (read from the code, before the phone)
From `ui/apps/OrgPageHost.kt` as it was:
1. **The page left with no web view** (`controller.web == null`). `processGone()` destroys the web view and removes it from its
   holder, then counts `generation` up, so a new web view is made. If the page is ever left without one, `reload()` began
   `val web = web ?: return` and `retry()` called `load()`, which was `web?.loadUrl(...)`. Both did nothing, silently. That matches
   all of it: an empty holder (no WebView node), no failure screen, a Reload that logs nothing and starts nothing. A renderer gone
   (the kill logged after the first link) is what calls `processGone()`. Also, `processGone()` destroyed `controller.web` whichever
   web view had reported the gone renderer, so a stale web view's report took the page's own web view down.
2. **A navigation that never commits.** WebView reports `onPageStarted` only at commit, and the progress bar shows only from
   `onPageStarted` (`isLoading`). So a load that hangs before any response (for example in the page's own profile, see 3) shows
   exactly this empty body, with no error until the network gives up. Reload calls `loadUrl` again, which joins the same hang and
   needs no new renderer. This fits everything except, possibly, the missing WebView node: a WebView with no document may expose no
   accessibility node at all, so that node does not decide between 1 and 2.
3. **The per-page profile** (`MULTI_PROFILE`; `peel_org_<uuid>`, a new one per page). A `setProfile` that threw was swallowed by
   `runCatching`. The web view then went on in the shared profile with nothing wiped, and `close()` tried to delete a profile never
   used. That is not a blank page, but the logs could not say whether it happened. Whether a fresh profile per page loads at all
   on this WebView is unknown. Nothing in the code shows it failing, so profiles are not switched off here (see Open).
- **Not a cause**:
  - `wipeSharedWebData` only clears cookies, storage, cache and history.
  - The token's document-start script failing is caught, and the hand-over then runs as the page starts.
  - The wallet bridge's `addWebMessageListener`/`addDocumentStartJavaScript` throwing would have crashed the app, which did not
    happen. It is now caught (see Fixed).
  - A public page has no script and no headers, so `example.com` loads with nothing of Peel's added except the wallet shim.

## Built
- **Logs** (`engine/PageLog.kt`, pure and JVM-tested; written by `pageLog` in `OrgPageHost.kt`, `Log.i` tag `PeelPage`, kept in
  release builds since release is not minified). Each line carries an address's origin only (`scheme://host[:port]`: never the
  path, query or fragment), numbers, feature flags, an exception's class (never its message), and page text through
  `DetailRedaction`. No header, token or invite is ever passed. The lines:
  - `webView gen=… package=<webview package/version> MULTI_PROFILE= DOCUMENT_START_SCRIPT= WEB_MESSAGE_LISTENER= profile=on|none|threw:<Class>`
    `tokenScript=none|atPageStart|documentStart|threw:<Class> wallet=none|installed|unsupported|threw:<Class>`: one per web view made.
  - `loadUrl <origin> gen=…`: every `loadUrl` the page makes itself (first load, Reload, Try again, a new window loaded here).
  - `attached`/`detached gen=…`, `size <w>x<h> gen=…`: whether the web view is in the window, and its size.
  - `pageStarted`, `commitVisible`, `pageFinished <origin> gen=…`, `progress <n> gen=…`.
  - `error <origin> code=<WebViewClient ERROR_*> <description>`, `httpError <origin> status=<n>`: main frame only.
  - `renderGone didCrash= priorityAtExit= current=<the page's own web view?>`.
  - `console <LEVEL> <redacted message> @<file name>:<line>`. `onConsoleMessage` now returns true, so WebView no longer also logs
    the raw message itself.
  - `reload with no web view: a new one gen=…`, `retry …`, `close profileDeleted=…|profile=none gen=…`.
- **Fixed** (each from the code alone, none verified on a device):
  - Reload and Try again with no web view make a new one (`generation++`, which loads where the page was), instead of doing
    nothing.
  - `processGone(gone)` destroys the web view that reported the gone renderer. Only when that is the page's own does it make a new
    one: a stale web view's report no longer destroys the one on screen.
  - A `setProfile` that throws drops the page's profile (`OrgPageController.dropProfile`, via `useProfile`). The page then wipes the
    shared web data as a WebView without profiles does, and `close()` no longer tries to delete a profile it never used.
  - The wallet bridge's install is caught. A throw is a page without the wallet (its existing notice), not a crash.

## Open
- Release 0.1.6 has both bugs. A release with this phase is what fixes Peel for workers; that release is not cut here.
- The paths fixed from the code alone (a stale web view's gone renderer, Reload with no web view, a throwing profile) were not
  seen on the phone.
- The progress bar still starts at `onPageStarted` (commit). Showing it from `loadUrl` would make a hang visible, but a load that
  becomes a download never calls `onPageFinished`, so the bar could stay up. No hang was seen, so it is left.

## Tests
JVM: `PageLogTest`. It checks that origins drop the path, query, fragment and credentials; that steps and failures carry no app or
folder; that a console message is redacted with its source cut to a file name; and that an exception is its class.
Robolectric:
- `OrgPageHostTest`: a stale web view's gone renderer leaves the page's own web view; the client's lines are origin-only.
- `PublicPageHostTest`: Reload and Try again with no web view make a new one; a profile that throws is dropped.
- `PageHostLogTest`: the real `OrgPageScreen` logs `webView`, the first `loadUrl` and `close` for an org page and a public page,
  with no bearer, app or path in any line.
- `PageMediaTest` (JVM): the page's own origin (a trailing slash or `:443` alike) gets the camera and microphone; another origin,
  http, another port, an opaque `null`, and any other resource get nothing; each is granted only with its system permission.
- `OrgPageHostTest`: the page's own origin is granted the camera after the system's permission and denied when the person says no;
  another origin, a sandboxed frame or another resource is denied without asking the system; a video with no poster gets a
  transparent pixel; a file input shows nothing until the screen can, then gets what was picked, or null; `pickedFiles` reads one
  file, several, a cancel and no data.
- `PublicPageStoreTest` (JVM): a public site's kept profile is the same across opens and case/default-port spellings, another host's
  or port's differs, and its UUID is iOS's (`a49ab031-29db-5436-b8e7-0779241b28fe` for the worker app's origin, version 5); an
  org's page keeps nothing. `PublicPageHostTest`: closing a public page keeps its site's profile; a per-open one is deleted.
- `OrgPageScreenTest`: the real `OrgPageScreen` gives its WebView MATCH_PARENT, and a page opened over another gets a web view of
  its own. Each test fails without its fix (checked).
  - The page-switch test runs its own `Recomposer` and `BroadcastFrameClock`. After an earlier test has destroyed its activity,
    Robolectric's shared Compose frame clock runs no more frames, so a state change would never recompose.

On the phone (Peel Dev): the worker app and example.com, each drawn, the second opened over the first; `100vh` measured over the
DevTools protocol. Not covered anywhere: the renderer kill and the stale-view path.
