# Phase 27 — Sign in with Sphinx

Status: **nothing was compiled or run**: not compiled, not unit-tested, not device-tested, not tried against a real strut app
or seller (no JDK, Android SDK or device here). First written from a summary of iOS `peel` PR #551, then revised after an
audit line by line against the real iOS source and strut's wallet bridge; every fix of that audit is applied but unverified.
The tests that need `libsphinxrs` (`VisitorSignatureVectorTest`, `EngineSignVisitorTest`) are `@Ignore`d on the JVM: run them
on a device.

**Scope.** iOS PR #551's merge title is "Sign a visitor in and buy their credit through Peel's wallet (strut item 9, #625)":
it holds **two** features, `sign_in` (commit 82bf0650) and `buy_credit`/`buy_credit_status`/`buy_credit_cancel`
(commit cfa9826b, `CreditPurchase.swift`). The spending-wallet methods (`pay_invoice`, …) pre-date it (iOS #484). This port is
`sign_in` only: strut's `host_capability` vectors list "sign_in alone" as a valid native host, so that is contract-valid, and
Android advertises only what it implements. `buy_credit` moves money (invoice decode, payment, one sheet per purchase, never
paying a hash twice) and is **left as a follow-up PR**.

A stranger's public strut app opens in a WebView from `peel://open?url=<address>`. Its page asks Peel (through a native
`strutWallet` handler) to sign a seller's visitor challenge; Peel asks the person, signs, and remembers the choice per origin.

## Built
- **`engine/VisitorSignIn.kt`** (pure, JVM-tested): `SignInKey` (`root`/`domain`), `VisitorChallenge.parse`
  (`strut-visitor.<claims>.<hmac>`, ≤ 2048 units, two base64url parts, claims `host`/`nonce`/`exp` ms/`visitorKey`; the HMAC is
  the seller's to verify, never checked here), `SignInRequest.problems` (the iOS rules and wording, in order; the challenge host
  is judged against the **loaded origin's host** when given, never the page's claimed seller), `VisitorSignature{key,sig}`,
  `VisitorSigner`.
- **`engine/PublicApp.kt`** (pure, JVM-tested): `Seller` (`isSellerURL`, `host`, `base`, `origin`: https, or http on loopback only),
  `PublicAppLink.parse` (strict `peel://open?url=…`; one `url` item; the address has no fragment, one `app` at most, a seller
  base), `appName()` (`?app=`, else `<meta name="strut-serve">` of the seller's `/`, else the only app of `/public/apps`), HTTP
  behind `PublicAppHttp` (`OkHttpPublicApp`, no redirects, ≤ 1,000,000 bytes). The page loads the address as given, so `appName()`
  is built and tested but not called by the UI.
- **`engine/WalletHost.kt`** (pure, JVM-tested): `WalletHost.handle(message, WalletCaller)` → `{result}` or `{error:{code,message,data:{code}}}`
  built by `WalletErrors.body` (strut reads only `error.data.code`, one of the 12 `WalletError` names; numeric `code` -32602 for
  `BAD_REQUEST`, else -32000). Order as iOS: a message with integer `v` 1 and a string `op`; host ops (`host_capability`,
  `sign_in`); then `app` must parse (`BAD_REQUEST` otherwise); `capability` → `RESTRICTED` "This app asks for no wallet." or
  `{version:1, methods:[], notifications:[], wallet:"sphinx", connected:false}`; `call` → `BAD_REQUEST` (no method) or
  `NOT_IMPLEMENTED`. Numbers are read by `WalletProtocol.integer` (`1.0` is 1, `1.5`/"1"/true are not, ±2^53-1).
  Page text is cleaned by `TextLine.clean` before it is shown or stored. `host_capability` →
  `{version:1, ops:["sign_in"], wallet:"Peel"}`. `sign_in`: a stored connection holding a usable key signs silently (and
  `WalletPerson.signedIn` toasts); otherwise only right after a tap in the page, one sheet at a time, timed out at
  `min(challenge end, 9 min)`; after "yes" the connection is read again (revoked meanwhile stays revoked), saved as a
  sign-in-only connection, and a challenge that ended meanwhile is not signed.
- **`Engine.signVisitor`**: `root` → `signBytes(seed, 0, <unique clock>, network, challenge UTF-8)` with the account key
  (child 0) as `key`; `domain` → `signBytesLud05(bip39Seed, challenge.host, …)` with its `pubkey`/`sig`. The account's keys are
  untouched. `Account.bip39SeedOf` = `mnemonicToBip39Seed`; `Engine` derives it once and takes its Sphinx `seed` as its first 64 hex characters.
- **`Storage` v13** `wallet_connection(key, app, title, place, methods, signIn, createdAt)` (iOS `walletConnection` with its v34
  `signIn`): `walletConnection`, `saveWalletConnection`, `deleteWalletConnection`, `walletConnections`, and a
  `walletConnectionsVersion` flow. Key for a stranger's page: `origin:<origin>`. Revoking deletes the row.
- **`ui/page/`**: `PublicPageScreen` (full-screen overlay: close button, the host as title, JS + DOM storage on, file/content
  access off, navigation policy as iOS (see Differences), a tap in the page = `personActed` for 5 s,
  cookies/web storage/cache cleared before and after the visit), `SignInSheet` (iOS `WalletWords` copy; radio list, one note for the chosen key; confirm off
  for 700 ms; Back = decline; outside tap does nothing), `SignInSheets` (the `WalletPerson`), `PublicPageRouter` (open now when
  unlocked, else `waitingLink` until unlock; a lock only closes the page, `reset()` on sign-out/wipe drops both). Toast "Signed in to <host> as you" /
  "…as its own identity" plus a TalkBack announcement.
- **You › Connected apps** (`YouScreen`): each connection with its title, the place, "Signs you in to <host> as you" / "…as a new identity" and a
  destructive Disconnect.
- **Deep link**: `MainActivity` intent filter (`VIEW`, `DEFAULT`+`BROWSABLE`, `peel://open`), read in `onCreate`/`onNewIntent`
  next to the `sphinx.chat` invite link; an invalid link is ignored.
- **Dependency**: `androidx.webkit` 1.14.0 (version catalog + `app/build.gradle.kts`).

## Verified against the iOS source
- Sign-in sheet copy and order (who line `An app on <host>, which calls itself <title>`, shared line, key picker with one note for
  the chosen key, `Sign in to <host>?`, `Don't sign in`/`Sign in`, confirm off 700 ms), Connected apps rows, error body, integer
  reading, `capability`/`call` answers, text cleaning, app-name rule, `peel://` link rules (a port is not rejected; input is not
  trimmed), lock handling (a lock closes the page and keeps a waiting link; only sign-out/wipe drops both) and the navigation
  policy (origin and `about:` load; `data:`/`blob:` only in sub-frames; another http(s) site never loads, but a link tapped
  within 1 s opens in the system browser).
- Seed: `mnemonicToSeed(words)` is the first 32 bytes of `mnemonicToBip39Seed(words)` (sphinx-ffi v0.8.44 documents it; PBKDF2-
  HMAC-SHA512 of "absurd amount … adjust" starts `02592d42…0fb3`, sphinx-rs's own vector; `SeedDerivationTest`). So `Engine`
  does one derivation, like iOS.
- The wallet's `host_capability` answer is `{version:1, ops:["sign_in"], wallet:"Peel"}`, one of strut's vectors.

## Differences from iOS
- **No `buy_credit`** (see Scope). **No spending wallet**: `capability` answers `methods: []`, `connected: false` (a sign-in-only
  connection holds no wallet), so strut answers every wallet call NOT_IMPLEMENTED itself; `call` is always NOT_IMPLEMENTED
  (iOS first runs strut's `checkParams` for a known method's `BAD_REQUEST`: not ported, nothing is offered).
- Back dismisses the sign-in sheet as a decline. iOS's download handling and its pull-to-reload / failure screen are not ported
  (a failed main-frame load shows "Couldn't open <host>." under the header).
- URLs are parsed with `java.net.URI` (strict) where iOS uses `URLComponents` (lenient): a seller/origin host with characters
  `URI` rejects (an underscore, say) fails closed on Android. The `peel` link host check is `open`, case-insensitive.
- Cookies and web storage are wiped around a visit, but `WebStorage`/`CookieManager` are process-wide on Android (iOS gives the
  view a non-persistent store of its own). The only other WebView (`AppsScreen`) uses no cookies or stored state, so nothing is lost.
- A connection never expires (iOS sign-in-only connections have `expiresAt` nil too).
- **Bridge shim.** strut's `nativeWalletOf` looks only for `window.webkit?.messageHandlers?.strutWallet?.postMessage` (a function,
  main frame). No Android handler exists in strut as of ff5b896, which also predates its sign_in/host_capability code, so the
  wire shape came from iOS and the vectors (`sign_in_requests`/`replies`/`signatures`, `host_capabilities`). Android therefore
  installs a document-start script (`WALLET_SHIM`) defining that object, whose `postMessage` returns a Promise of the parsed
  reply, over `WebViewCompat.addWebMessageListener("peelWallet", {origin})` (which, unlike `addJavascriptInterface`, tells the
  frame and origin, keeping "main frame of exactly the loaded origin"). It is installed only for the loaded origin's rules. Its
  own failures are `INTERNAL`; "Peel does not answer there." is `RESTRICTED`, both in `WalletErrors` shape. No `webkit` beyond
  that is faked. If the WebView lacks `WEB_MESSAGE_LISTENER`/`DOCUMENT_START_SCRIPT` the page loads with a note that sign-in is unavailable.
- **Cleartext (deliberate).** iOS reaches `http://localhost`/`127.0.0.1` pages because App Transport Security exempts unqualified
  hosts and IP literals. On Android release builds http is blocked (`usesCleartextTraffic=false`, release
  `network_security_config.xml`), and plain loopback is debug-only by the repo's own rule (`NetworkPolicy.plainLoopbackAllowed`).
  `src/debug/res/xml/network_security_config.xml` already existed (cleartext to localhost, 127.0.0.1, 10.0.2.2, [::1]; it
  replaces main's in debug builds) and was left as is: it already covers what this needs. A `peel://` link to an http loopback
  address therefore parses (as iOS) but its page fails to load in release, showing the in-page note. From memory of the Android
  docs (not verified here): when `networkSecurityConfig` is set, the platform uses it on API 24+ and the manifest's
  `usesCleartextTraffic` is not consulted, so debug's `domain-config` works despite `usesCleartextTraffic="false"`; if a debug
  http page still fails, check that first.
- `app` names: `PublicAppLink.isAppName` is iOS's `AgentArtifact.Handle.isAppName` (≤ 120, `/`-separated segments of
  `[A-Za-z0-9][A-Za-z0-9_.-]*`, no segment `data.db` or `v<digits>`); it differs from `WorkspaceApp.isAppName` and is separate.
  `/public/apps` counts only when it lists exactly one app with a valid name. `appName()` is built and tested but not called by the UI.
- No change to `sphinx-rs`: its v0.8.44 bindings already have `signBytes`, `signBytesLud05`, `mnemonicToBip39Seed`.

## Tests
JVM: `VisitorSignInTest` (challenge, seller, request vectors from strut), `PublicAppTest`, `WalletHostTest`,
`StorageWalletConnectionTest`, `WalletProtocolTest`, `TextLineTest`, `SeedDerivationTest` (JDK PBKDF2), `PublicPageRouterTest`.
`@Ignore`d (needs the native library): the signature vector in `VisitorSignatureVectorTest` and iOS's engine signing test
(`EngineSignVisitorTest`: domain keys for site.com / app.example.com, root key and signature shape).
