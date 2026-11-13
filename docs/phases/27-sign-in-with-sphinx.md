# Phase 27 — Sign in with Sphinx

Status: written **without a build**: not compiled, not run, not device-tested, not tried against a real strut app or seller
(the pod had no JDK, Android SDK or device). Ported from the iOS app's `peel` PR #551 (not readable from here: ported from the
contract in the task, strut's `specs/WALLET.md` `sign_in` and the seller's `ln-message` verifier). The signature vector test
needs `libsphinxrs` and is `@Ignore`d on the JVM (run it on a device).

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
- **`engine/WalletHost.kt`** (pure, JVM-tested): `WalletHost.handle(message, WalletCaller)` → `{result}` or `{error:{code,message}}`
  (`BAD_REQUEST`, `RESTRICTED`, `USER_REJECTED`, `RATE_LIMITED`, `NOT_IMPLEMENTED`, `NOT_FOUND`, `INTERNAL`). `host_capability` →
  `{version:1, ops:["sign_in"], wallet:"Peel"}`. `sign_in`: a stored connection holding a usable key signs silently (and
  `WalletPerson.signedIn` toasts); otherwise only right after a tap in the page, one sheet at a time, timed out at
  `min(challenge end, 9 min)`; after "yes" the connection is read again (revoked meanwhile stays revoked), saved as a
  sign-in-only connection, and a challenge that ended meanwhile is not signed.
- **`Engine.signVisitor`**: `root` → `signBytes(seed, 0, <unique clock>, network, challenge UTF-8)` with the account key
  (child 0) as `key`; `domain` → `signBytesLud05(bip39Seed, challenge.host, …)` with its `pubkey`/`sig`. The account's keys are
  untouched. `Account.bip39SeedOf` = `mnemonicToBip39Seed`.
- **`Storage` v13** `wallet_connection(key, app, title, place, methods, signIn, createdAt)` (iOS `walletConnection` with its v34
  `signIn`): `walletConnection`, `saveWalletConnection`, `deleteWalletConnection`, `walletConnections`, and a
  `walletConnectionsVersion` flow. Key for a stranger's page: `origin:<origin>`. Revoking deletes the row.
- **`ui/page/`**: `PublicPageScreen` (full-screen overlay: close button, the host as title, JS + DOM storage on, file/content
  access off, navigation kept to the loaded origin — anything else loads nothing, a tap in the page = `personActed` for 5 s,
  cookies/web storage/cache cleared before and after the visit), `SignInSheet` (iOS `WalletWords` copy; radio list; confirm off
  for 1 s; Back = decline; outside tap does nothing), `SignInSheets` (the `WalletPerson`), `PublicPageRouter` (open now when
  unlocked, else `waitingLink` until unlock; cleared, page dismissed, on lock or sign-out). Toast "Signed in to <host> as you" /
  "…as its own identity" plus a TalkBack announcement.
- **You › Connected apps** (`YouScreen`): each connection with "Signs you in to <host> as you" / "…as a new identity" and a
  destructive Disconnect.
- **Deep link**: `MainActivity` intent filter (`VIEW`, `DEFAULT`+`BROWSABLE`, `peel://open`), read in `onCreate`/`onNewIntent`
  next to the `sphinx.chat` invite link; an invalid link is ignored.
- **Dependency**: `androidx.webkit` 1.14.0 (version catalog + `app/build.gradle.kts`).

## Assumptions
- **Bridge.** `addJavascriptInterface` cannot tell which frame called, so it cannot keep the "main frame of exactly the loaded
  origin" rule. `WebViewCompat.addWebMessageListener("peelWallet", {origin})` can (`isMainFrame`, `sourceOrigin`), so it is used,
  with `androidx.webkit` as the one new dependency. A document-start script (`WALLET_SHIM` in `PublicPageScreen.kt`) defines
  `window.webkit.messageHandlers.strutWallet.postMessage(body) → Promise` over it (posts `{id, body}`, resolved by `{id, reply}`),
  because strut's UI looks for iOS's handler. **Assumed**: strut's UI only needs that object shape; nothing else of `webkit` is
  faked. Peel's pushes (`window.strutWallet.notify`) are not needed: no notifications exist here. Anything but the main frame at
  the exact origin gets a `RESTRICTED` "Peel does not answer there." If the device's WebView lacks `WEB_MESSAGE_LISTENER` or
  `DOCUMENT_START_SCRIPT` the page still loads, with a note that sign-in is unavailable.
- App names: `PublicAppLink.isAppName` is the task's rule (letters, digits, `-`, `_`, 1–120, starting alphanumeric), stricter than
  `WorkspaceApp.isAppName` (which allows `/` and `.` for a peer's apps).
- `/public/apps` counts only when it lists exactly one app with a valid name.
- `capability` answers `{version:1, methods:[], connected:false}` (iOS's "not connected" shape inferred, not seen); `call` answers
  `NOT_IMPLEMENTED` "Peel's wallet has no method <name>."
- Connections do not expire (no expiry column was specified); "ignore expired" has nothing to ignore.
- The sign-in key is `bip39Seed` computed beside the Sphinx seed (a second PBKDF2 at unlock). iOS takes the Sphinx seed as the
  first 64 hex characters of it; that could not be confirmed against `mnemonicToSeed` here, so Android's derivation is unchanged.
- The page's data (cookies, web storage) is the app-wide WebView store, wiped around the visit; Apps' pages keep nothing there
  (token in a header), so nothing of theirs is lost. A plain-http loopback page is refused by the app's network security config
  (cleartext off), so only https pages load in practice; the config was left alone.

## Differences from iOS
- **Out of scope**: `buy_credit` and the spending-wallet methods (`pay`, …), and the Mac remote scope. Android has no app-wallet
  host, so `host_capability` lists only `sign_in` (iOS also lists the `buy_credit*` ops).
- No per-app wallet connection UI beyond Connected apps (no methods to grant).
- No change to `sphinx-rs`: its v0.8.44 bindings already have `signBytes`, `signBytesLud05`, `mnemonicToBip39Seed`.

## Tests
JVM: `VisitorSignInTest` (challenge, seller, request vectors from strut), `PublicAppTest`, `WalletHostTest`,
`StorageWalletConnectionTest`. `@Ignore`d (needs the native library): the signature vector in `VisitorSignatureVectorTest`.
