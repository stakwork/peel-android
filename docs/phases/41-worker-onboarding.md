# Phase 41 — Worker onboarding (strut's human steps)

Status: **written without a build here: no Android SDK on this Mac (Homebrew's JDK 21 only), so `testDebugUnitTest`, lint and
`assembleDebug` ran only in the PR's CI. Nothing was run on a device or against a real Stadeum, daemon or mixer.**
Written as phase 37, then 38; renumbered 41 when main landed 37–40 first (its `Storage` v15 is phase 40's `isControl`, so `public_app` is v16).
Fixed after #98 shipped in v0.1.6: the Apps row was gated on a Hive workspace only, so a worker (who has none) never reached
"Apps you opened" (see "Where Apps is reached" below).

Why: a client's Stadeum (a self-hosted strut) runs workflows with `human` steps (strut's `plans/human-steps.md`). Workers do the
tasks in a worker app the Stadeum serves publicly, sign in with Peel (root key), and are paid from the Stadeum's wallet (a Sphinx
daemon on the Sphinx mixer) by keysend to the Sphinx address their Peel gave at sign-in. Staff tap Invite; strut asks the daemon
for a Sphinx invite and gives staff one link, `https://<stadeum public host>/public/join#<invite>`, whose page opens Peel with
`sphinx.chat://?action=i&d=…&c=…&a=<percent-encoded worker app address>`. Built in parallel with the daemon and strut halves;
this half depends on neither.

## Built
- **`&a=` in the invite link** (`InviteLink.appOf`, `engine/Invite.kt`): the one `a=` section, percent-decoded (a `+` stays a plus),
  held to a `peel://open?url=` link's rules (`PublicAppLink.of`: https, or http on loopback; no credentials, fragment or second
  `app`). None, two, or one that fails: no app, and the invite is read exactly as before. The link still goes to the core whole:
  its `parse_invite_qr` (stakwork/sphinx `sphinx/src/ping/invite.rs`) finds `d=`/`c=`/`t=` by prefix and passes over `a=`, so no
  core, sphinx-rs or mixer change. The mixer never sees `a=`; the invite is still never logged, and only `Setting.INVITE` keeps it, as before.
- **Where the app opens** (`MainActivity.takeInviteLink`): an invite link with a valid `a=` sets the invite as before **and** hands
  the app to `publicLink`, the path a `peel://open` link takes (`PublicPageRouter`): open now when the account is unlocked, else
  waiting until it is. So:
  - **No account**: onboarding with the invite filled in, as before; the app waits, and opens once signup (or a restore) is done
    and `Home` shows (`openWaitingLink`). Signup has started the engine and waited for the register by then (`Signup.create`).
  - **An account here**: the app opens (at once, or after the PIN); the invite part is ignored, as it always was with an account.
  - **No `a=`**: today's behaviour exactly.
  The first sign-in still asks "Sign in to <host>?" after a tap in the page: a link never signs anyone in by itself.
- **`sign_in` answers with the account's Sphinx address** (port of iOS stakwork/peel#557; strut's `specs/WALLET.md` §1.2 "The
  answer"): `VisitorSignature(key, sig, address?)`, `result` `{ key, sig, address? }`. `VisitorSignature.address(contactInfo, key)`
  keeps a contact info only when it is `<pubkey>_<lsp_pubkey>_<scid>` (`ContactCode.contactInfo`, lower-cased) and its pubkey is
  `key`. `Engine.signInAddress()` is `Setting.CONTACT_INFO` (what the core reported at registering) checked against `accountKey`,
  and `Engine.signVisitor` adds it with the root key only: none before registering, none rather than another key's, none with a
  domain's LUD-05 key (a new identity stays unlinkable). Nothing new is signed. Both ways a sign-in is answered (the sheet's yes and
  a held connection) return `signVisitor`'s result, so both carry it; `WalletHost` is unchanged.
- **Vectors**: `src/test/resources/wallet-vectors.json` is strut main at 319d3d6, byte for byte the copy iOS #557 holds: it adds
  #689's two `sign_in_replies` and `sign_in_addresses`, and #690's `native_apps` (not run here). Every older list is unchanged.
- **Opened public apps stay in Apps** (Android only; iOS keeps none): `Storage` v16 `public_app(seller, app, url, origin,
  openedAt)`, one row per app on a seller (`app` '' for the seller's own page), the address it was last opened at.
  `PublicPageRouter(opened)` tells the account of every page that comes on screen, whichever way it came; `MainActivity` saves it
  (`rememberPublicApp`). `AppsScreen` lists them under "Apps you opened" (last opened first; titled by the origin's connection
  title, else the host; caption host · app name; found by the search; hidden while a workspace source filter is chosen), and a tap
  opens one through the router (`AppsScreen(onOpenPublic)`, the router `MainTabs` holds since phase 39). Disconnect in You › Connected apps forgets
  that origin's apps with its connection (`forgetPublicAppsOf`); long-press › "Remove from Apps" takes one off the list (its
  connection stays), so a link someone sent never sticks there for good.
- **Where Apps is reached**: Apps is a row on the Chats screen, not a tab. iOS (and #98 as it shipped) shows that row only with a
  live Hive workspace or a development Talk home (`Stadeum.showsApps`); a worker has neither, so the row never showed and the
  kept apps could not be opened. Android now also shows it while `Storage.openedApps()` is not empty
  (`Stadeum.showsApps(openedApps)`; `ChatsScreen` reads it again as `publicAppsVersion` moves); the workspace gate is unchanged.
  It shows above Threads/Mentions in the chat list, and, for a kept public app only, on the "No chats yet" screen too (a new worker
  usually has no chats, and that screen has no Apps row; a workspace's row stays only in the list, as before). In Apps, a worker sees only "Apps you opened": no Talk to Jamie
  rows (none without a workspace), no workspace empty state while an app is listed, and no Refresh (shown only with a workspace).

## Not built / open
- **The first sign-in right after a signup whose register was not confirmed** carries no address (none is known yet); a later
  sign-in does, silently, through the held connection. Whether strut takes an address from a later session is strut's side.
- Not decided, so not built: Play Store distribution (Peel Android ships as an APK from GitHub releases; a worker without Peel
  installs it first; nothing carries the link across an install, so it rides on the clipboard: phase 46), deferring the words
  backup in onboarding, per-invite grants. iOS is out of scope.
- `public_app` rows are not synced and not backed up: a restore on a new phone starts with none (they come back as apps are opened).
- The `https://…/public/join#<invite>` page itself is strut's.

## Tests
JVM/Robolectric: `InviteLinkTest` (a worker's link names its app by a `peel://open` link's rules; none for no/empty/http/javascript/
fragment/credentials/two apps/two `a=`/a broken escape/`aa=`/`app=`; the core still gets the whole link), `SignInAddressTest` (strut's
`sign_in_replies`: the address Peel sends is the one strut keeps and the answer is the reply, field for field; `sign_in_addresses`;
case folding, another key's address, not registered, malformed), `WalletHostTest.theAddressGoesWithTheAccountKeyAlone` (from the
sheet and from the held connection; none with a new identity), `PublicPageRouterTest.eachPageOpenedIsTold`, `StoragePublicAppTest`
(order, one row per app, connection title, remove, disconnect by origin only, search; a kept app shows Apps with no workspace,
and the last one removed hides it), `WorkspaceAppsTest` (the gate: no workspace with an opened app, none at all, a workspace
with none), `StorageWalletMigrationTest` (v13 → v16 has the
same `public_app` as a fresh database).
`@Ignore`d (need the native `libsphinxrs`): `SignInAddressNativeTest.peelsOwnAddressIsStrutsVector` (strut's seed through
`initialSetup` + `handle` gives the vector's address), `EngineSignVisitorTest.theEngineSendsItsAddressWithItsKeyAlone`.

### UNTESTED (needs a device)
- `MainActivity` reading a real `sphinx.chat://…&a=` intent; the onboarding → Home → page order on a new account; the Apps rows
  (tap, long-press, the empty states with only opened apps); the Apps row appearing on Chats (and on "No chats yet") for a
  worker; Disconnect clearing the row; a real worker sign-in and payout.
- A worker who removes their last opened app while in Apps sees "No Stadeum here yet" there (the workspace wording) until Back;
  the Chats row is gone by then. Left as is.
