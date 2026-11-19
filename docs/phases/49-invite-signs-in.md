# Phase 49 — A worker's invite page signs them in

Status (2026-11-19): **built; JVM/Robolectric-tested; not run on a device yet** (the live check is below).

## Why

A worker invite is `https://<strut>/public/join#action=i&d=…&c=…&a=<Tasks' URL>`. The join page opens Peel with
`sphinx.chat://?action=i&…&a=…`, by a tapped link or, after a fresh install, from the clipboard (phase 46). Peel signs the new
account up with the invite, then opens `a=` (phase 41; a `strut/worker` link goes to the Tasks tab, phase 48). The page then asks
to sign in (`sign_in`), and Peel showed its sheet with "As you" / "As a new identity for this app". Seen on 2026-11-19 on a Pixel 9a
(GrapheneOS, Peel 0.1.12): the new worker has to understand that choice, and picking the new identity means they can never be paid,
since Peel sends the account's payout address only with the root key and strut answers `no_address` on withdraw. Evan, 2026-11-19:
the worker should land on the employer's Tasks page already signed in, with no sheet and no choice.

## Built

- **No key choice when the server asks for root** (`WalletHost.signIn`): the sheet offers exactly the key the challenge names. A
  root challenge (strut's default; `visitors.visitorKey` is "root" unless the server sets "domain") offers root alone, even where
  Peel can sign per domain; a domain challenge is unchanged (domain alone). With one key the sheet draws no picker, only what it
  means (`WalletSheets.kt`; the title is still "Sign in to <host>?"). This departs from strut's `specs/WALLET.md` "Signing a
  visitor in" step 5, which offered both keys for root; the strut side updates that spec.
- **An invite link's page is granted sign-in as you** (`WalletHost.grantInvitePage`): when Peel opens the page a worker's invite
  link names (`InviteLink.appOf`, its `a=`), it writes a wallet connection for that page's origin that signs in with
  `SignInKey.root`, before the page loads (`PublicPageRouter.show` tells `opened` before it sets the page). `signIn` answers a held
  grant before the sheet and before the gesture rule, so the page's sign-in goes through at once with only the toast ("Signed in
  to <host> as you").
  - For a new account (the page opens after signup) and, Evan ruled 2026-11-19, for an account already on the phone that taps a
    worker's invite link (whose invite part Peel ignores; it only opens `a=`).
  - Only that link: `MainActivity.takeInvite` marks it (`publicLinkInvited`), the router keeps the mark with its waiting link
    (through a lock, dropped on reset, never carried to another link), and `opened` gets it. A `peel://open` link, an open from
    the Apps list, or any other origin is never granted. The mark is held in memory only and never logged.
  - Keyed and placed as that page's sheet-made grant is: `origin:<origin>` (`PublicPage.walletScope`) and the host
    (`walletPlace`), `app` `/<?app=>`. Titled by its host until the app's first sign-in through it, which names it with the app's
    own (cleaned) name and title, as the sheet would have; later sign-ins of other apps on the origin leave the name be.
  - A connection already at the origin keeps its wallet grant and budget and gains the sign-in; one that already signs in (a key
    the person chose) is left as it is; an expired one counts as none. It shows in You › Connected apps like any other and is
    revoked there; a later invite link to that page grants it again.

## Not built

- iPhone and Mac (`peel`) still offer both keys for a root challenge and grant nothing for an invite's page.

## UNTESTED (needs a device)

- The live run: uninstall Peel Dev on the Pixel, make a fresh invite in strut/staff ("Invite a worker") on
  strut.stadeum.stakwork.ai, open it, sign up. Tasks should open signed in with only a toast (`adb logcat`, tags `PeelPage` and
  `PeelDiagnostics`). This also covers the fresh-install clipboard path (phase 46), which had never been run.
