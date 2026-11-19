# Phase 45 — Peel links to a strut box

Status (2026-11-18): **built; JVM/Robolectric tests pass; the engine's signature checked against iOS's vector on an emulator;
end to end on an emulator against a local strut on stakwork/strut#737.** Port of stakwork/peel#574 (`Sources/PeelCore/BoxLink.swift`,
`Sources/PeelUI/Boxes.swift`, `docs/box-link.md` there). Strut's side is stakwork/strut#737 (`plans/people-and-hosts.md` §2.1,
`specs/API.md` §13.6). Evan ruled on 2026-11-18 that Peel is the client going forward and links to a self-hosted Stadeum box the
way it links to Hive.

## What it is

A member signed in to a box (any strut with #737) opens the Jamie app's Connect › **Link Peel**. The box shows a one-time link
challenge naming them, as a QR code and an **Open in Peel** link:

    peel://strut/link?url=<the box's base, mount path included>&challenge=strut-link.<claims>.<hmac>

Peel asks **"Link Peel to `<host>` as `<name>`?"**. On Link it signs the challenge with the account's own key and posts it; the box
links that key to the member and answers a seven-day person token. Peel then opens the box's own strut UI as the member, where
its apps are listed and opened. The key is the lasting credential: Peel renews the token by signing again, and removing the link in
Connect ends the token at once.

## Built

**Engine, pure (`engine/BoxLink.kt`), JVM-tested:**

- `PeelLink.values`: a `peel://<host><path>?…` link's values, each name once, nothing else (no user, port or fragment).
- `BoxChallenge`: `strut-link.<base64url JSON { host, actor?, name?, nonce, exp(ms) }>.<hmac>`, read through `StrutClaims` (now
  shared with `VisitorChallenge`, as iOS did), never verified: the HMAC is the box's.
- `BoxLinkOffer`: the link (`parse`), the box's base as strut names one (`Seller.base`), and `problem(nowMs, allowsLoopback)`: Peel
  refuses a challenge for another host than the link's address, one run out, one naming nobody (a renewal's), and an address on this
  device outside a debug build. `BROKEN` is said for a `peel://strut/…` link that is not one.
- `BoxLink`: what is kept, `{ url, actor, name?, token, exp, linkedAt, renewedAt }`, several at once, one per box address, never
  described with its token; `due` (a day since the last renewal, or the token within a day of its end); `reachable`; `home`, the box's
  strut UI folder (`<base>/`) as an `OrgPageRequest` (`peer = "box:<url>"`); `read`/`write` of the list (`{"boxes":[…]}`): what cannot
  be read throws, never reads as none.
- `BoxClient` over `BoxHttp` (`OkHttpBox`: no redirects, answers capped): `POST /people/link { challenge, key, sig, name?, address? }`,
  `renew` (`GET /people/challenge`, signed only when for the box's own host, then `POST /people/token`; `403` is `Unlinked`), and
  `accepts` (`GET /apps` with the token; 401 is "no longer held"). Refusals in iOS's words: 401 ran out or used, 409 linked to
  someone else, 413, 429, strut's own words for the rest. The answer is checked: a `strut-person.` token, an actor, an end ahead.
- `Engine.signBoxLink`: the challenge's UTF-8 bytes as a Lightning signed message with the account key (child 0), the visitor
  sign-in's root path (`signWithAccountKey`, which both now share), with the account's Sphinx address when it has one.

**Model (`engine/Boxes.kt`, `BoxesModel`, `Boxes.model`):** `take` holds a link for the person's yes (`asking`) or says why Peel won't
sign it (`problem`); `decline`; `accept` signs and posts, keeps the answer (`linking` while it runs); `forget`; `forgetAll` (with the
account); `home`/`page` (the token checked first; a 401 renewed once; a box whose renewal says unlinked, or that refuses even a fresh
token, let go and said in `notice`); `shows(page)`. Renewal: an hourly look while the account is open, each due box renewed, and on
coming back to the front (Android's own, as `StadeumModel.cameToFront`). Its work runs on the main thread (iOS's `@MainActor`).

**Storage (`account/BoxStore.kt`, `KeystoreBoxStore`):** a new Keystore-wrapped file `boxes.default.v1` under its own key
`peel.boxes.default`, beside `KeystoreHiveStore` (`hive.default.v1`), never in it. **Hive is untouched**: no migration, no change to
`HiveLink`, `KeystoreHiveStore` or Stadeum pairing. Deleted with the account (You › Forget this account → `Boxes.model.forgetAll`).

**App:**

- `AndroidManifest.xml`: a filter for `peel://strut` (all of it, so a broken box link is said, as the Mac does). An older Peel has no
  such filter and is unaffected.
- `MainActivity.takeBoxLink`: a box link goes to `Boxes.model.take`, a broken one to `takeBroken`, and You opens (`DeepLink.YOU`).
- `BoxesHousekeeping` (in `MainActivity`): opens the boxes with the account (sign = `Engine.signBoxLink` off the main thread, the
  account's alias as the name), renews on resume, closes them on lock or sign-out (iOS closes them with the lock).
- `MainTabs`: the question is an `AlertDialog` (Link / Cancel; no tap outside or Back shuts it); a box's page is kept open while its
  box is linked (`PublicPageRouter.workspacesChanged(live, shows)`, iOS `closeGoneOrgPage`) and closes when it is forgotten or let go.
- You › Boxes (`YouScreen.BoxesSection`): each box as `host · member`; a tap opens it in the one page host (`LocalPageOpener`, as an
  org's page: Bearer on the first load, the `sessionStorage["strut/apiKey"]` hand-over, only that strut's UI folder, Reload with the
  bearer); a long press offers Forget; the linking row, the notice/problem lines, and how a box is linked.

## Decided (iOS's, kept)

- **A box's apps are its own strut UI's**, opened as the member; Peel does not list them, and a box is never put into
  `Stadeum.live` (which Talk, the Apps list, Hive links and calls all read). A box stays beside Stadeum.
- **"A 401 on use" is checked at Open** (`GET /apps`), the only use of the token outside the web view. A page already open is not
  watched; the next Open renews.
- **A renewal's challenge is not checked for its end** (a minute; a device clock off by more would block every renewal); its host is.
  A link's challenge (ten minutes) is checked, to say so before asking. (Seen working end to end with the device clock a day ahead.)
- **Boxes that cannot be read are never written over**: a link is refused and Forget does nothing until they are read again.
- **Forget is local.** The words say to remove it in Connect too.
- **One link per box address.** Linking again (as another member) replaces the kept one.
- **A box's page gets the app wallet keyed `org:box:<url>`**, as the iPhone's does (the page host's `WalletScope.Org(peer)`).

## Differs from iOS

- **"A development profile" is a debug build** (`NetworkPolicy.plainLoopbackAllowed(BuildConfig.DEBUG)`): only a debug build links to
  a box on this device's own address (http or https). Android has no development profile; this is the rule the rest of the app uses
  for loopback.
- **Renewed on coming back to the front** too, besides the hourly look, as Android's Stadeum is: a backgrounded process's timer may
  not have run.
- **The question waits in `MainTabs`** (shown once unlocked, like iOS's alert over `MainTabs`); You opens under it.
- **Errors read as iOS's `PeelError.noted`**: "Could not <what>. <why>".
- `PeelLink` is used by the box link only; `PublicAppLink.parse` keeps its own reader, unchanged.

## Tests

- `engine/BoxLinkTest` (Robolectric, 15): the link as strut writes it (a mount path, a port, upper case), what is not a box's link,
  a challenge's claims, what Peel won't sign (another host, run out, nobody, loopback outside debug, an emulator's `10.0.2.2`); the
  vector's challenge is the one Android builds byte for byte; the list kept in its own Keystore-wrapped file beside Hive's, whose
  file is untouched, deleted when empty, unreadable throws; never described with its token; due; the box's page (folder, bearer,
  hand-over, `org:box:` wallet scope, Reload keeps the bearer); the three routes on a stand-in (the post's body and no credential, the
  refusals in words, renewal signing only a fresh challenge for the box's own host, 403 as unlinked, the token check).
- `engine/BoxesTest` (Robolectric, 13): nothing signed or sent without the yes; one link per box; a renewal never replaces a box
  linked again meanwhile (found by `/code-review`); boxes that can't be read never
  written over; another host's link never asked, a broken one said; a link opened while locked waits and links once open, none with
  no account; a due box renewed and an unlinked one let go; a failed renewal said and kept; a refused token renewed once before the box
  opens and the router keeps its page, a box whose link is gone let go and its page closed; a box refusing a fresh token let go;
  Forget and the account forgotten; close.
- `androidTest/engine/BoxLinkSigningInstrumentedTest` (**run** on an emulator, API 35 AOSP arm64): the engine signs iOS's vector
  challenge to the vector's key and signature (made by the core and checked with strut's own `VERIFIERS["ln-message"]`, the check
  `POST /people/link` runs), the same as a visitor sign-in with the root key over the same bytes.

## End to end (2026-11-18)

An emulator (API 35 AOSP arm64, airplane mode: nothing reached production; `config.sphinx.chat` never resolved) with this branch's
debug build, a test account restored offline from the "abandon … about" words, and strut from its own checkout of #737's branch
(e42e327c) on :3400 under `env -i` (`STRUT_API_KEY` only; model URLs on a closed port), reached as `127.0.0.1:3400` through
`adb reverse`. Alice's link was minted by strut's own `account/link` step, called as the Jamie app for alice (`POST /mcp` `step_run`,
as strut's `src/apps/jamie.test.ts` does); Remove was its `account/unlink`.

1. `am start` with the link: "Link Peel to 127.0.0.1 as alice?" over You; Back and a tap outside left it.
2. Link: `POST /people/link 200`; You › Boxes "127.0.0.1 · alice"; strut's `account/links` for alice: `sphinx:03216967…` (this
   account's key, the vector's). The box kept in `boxes.default.v1` (437 bytes, wrapped).
3. Open: `GET /apps 200`, `GET / 200`, then strut's UI as alice (`/workflows`, `/apps`, `/peers` 200; `GET /people?actor=alice 403`,
   a person token's answer); the page drawn in the page host, titled 127.0.0.1. Reload: the same requests, all as alice.
4. Remove in Connect, then Open: `GET /apps 401` → `GET /people/challenge 200` → `POST /people/token 403`; Peel let the box go and
   said "127.0.0.1 removed Peel's link, so Peel let it go. Link it again from Connect there."; the file deleted.
5. Linked again, then the device clock moved a day ahead: (the PIN timeout locked Peel, which let the boxes go mid-renewal) at the
   unlock `GET /people/challenge 200` → `POST /people/token 200`; Open with the renewed token `GET /apps 200`, `GET / 200`.
6. Forget (long press): the row and the file gone; strut's `account/links` still has the link (Forget is local).
7. A broken link (`&` lost): "That link to a box doesn't work. …", nothing asked.

## Review (2026-11-18)

One `/code-review` (high): 7 findings. Fixed: a renewal that started before the box was linked again (as another member) could
replace the new link (now it renews only the link it started from); Open's token-check failure said "could not renew" (now "could
not check Peel's link"); Forget on unreadable boxes said nothing (now says why). Left: the token check reads `GET /apps`'s body (iOS
does the same); `KeystoreBoxStore` repeats `KeystoreHiveStore` (kept apart so Hive's code is untouched); no Compose UI test of the
question and the Boxes rows (covered end to end only); the store read on the main thread at unlock (as Stadeum's).

`./gradlew testDebugUnitTest` on this Mac's JDK 21: 1287 tests, one failure, `ConversationDateTest.todayShowsTime`, which is JDK 21's
time format (U+202F before "PM", which the test's `\s` does not replace), not this branch; the project targets JDK 17.

## Open

- Not run on a phone (GrapheneOS or otherwise); the QR scanned by a phone's camera opening Peel was not tried (the emulator was
  handed the link with `am start`).
- Strut's "Open in Peel" from its own host page (`ui/open-link`) opening Peel on a device: not seen (strut's note says the same).
- Whether Connect shows Linked by itself when linked by a challenge other than the one it shows (iOS's note for strut) was not
  checked here: Connect's page was not opened; its steps were called directly.
