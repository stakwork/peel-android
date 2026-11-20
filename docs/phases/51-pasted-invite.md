# Phase 51 — A pasted or scanned worker invite opens its app too

Status (2026-11-19): **built; JVM-tested (`InviteLinkTest`); not run on a device yet.**

## Why

Seen 2026-11-19 on stakwork/strut's stadeum.stakwork.ai box: a worker on GrapheneOS (Peel 0.1.15) signed up from an invite and
saw "No chats yet", then the inviter (Stakwork Support), but no Tasks tab and no Tasks page. He reinstalled and got the same.
Strut's request log showed no load of the join page (`/public/join`) for his invite and no load of the Tasks page its `a=` names,
in either install: the invite reached Peel without the join page, so it was entered by hand (pasted or scanned into "Paste your
invite"). He said there was no invite on the clipboard. His second install's diagnostics (You › Diagnostics) agree: Peel launched
at 01:20:39 UTC with nothing to take, was left for 4 minutes (01:22–01:26, no join page loaded meanwhile), came back, and signed
up at 01:28:14 with no restart in between (`previousRun clean`, no new launch). A minute later adding the inviter failed with
`send failed: … not enough balance` (below).

The core reads an invite by finding its `d=`/`c=` sections anywhere in the text, so pasting strut's join link
(`https://<strut>/public/join#action=i&d=…&c=…&a=…`) signs a new account up and adds the inviter. But only a tapped link and the
clipboard handoff (phases 41, 46) took `a=`: the invite step never looked for it, and `InviteLink.appOf` read sections only after a
`?`, which the join link does not have (its invite is after `#`). So the worker app never opened.

A reinstall that pastes the same link again also starts the new account with 0 sats: the first install spent the invite, and a
mixer whose invite price is 0 registers a spent code with no sats and no error (sphinx `mixer/src/routes.rs`,
`redeem_initial_amt`). That is the mixer's rule, not changed here; a second try needs a new invite. With 0 sats the new account
cannot pay for its key exchange to the inviter (`not enough balance`), so the inviter shows in Peel but never gets the request.

## Built

- **The invite step takes `a=` whatever brought the invite** (`OnboardingScreen`'s `onInviteApp`): when Continue reads the
  invite, the app it names (`InviteLink.appOf`) goes to the router as a tapped link's does (`PublicPageRouter.open`, not ready,
  `invited`), so it opens after signup and is signed in with no sheet (phase 49). Pasted, scanned, tapped or off the clipboard
  alike; for a tapped link it is the same link again.
- **`InviteLink.appOf` reads the sections after the first `?` or `#`**, or the whole text when there is neither (the bare
  `d=…&a=…` the core also reads): the join link, the `sphinx.chat://?` link, and bare sections all name their app.
- **The clipboard handoff takes the join link too** (`InviteLink.fromJoinLink`, used by `fromClipboard`): a worker who copied
  the link from the message it came in and opened Peel finds the invite step filled in. Only `https://<host>/public/join`
  (an optional `?lang=`, no user info, that exact path) with the invite after `#`, held to phase 46's rules; it is taken as the
  `sphinx.chat://?…` link the page opens Peel with (a `sphinx.chat://?` after the `#` dropped, as the page drops it). This
  changes phase 46's rule that only a `sphinx.chat://?` link was taken.

## Not built / open

- Why the join page was not used is not known (the worker's word, not checked): a messenger that copies rather than opens
  links, or a browser that did not hand the `intent://` link to Peel.
- The clipboard is still read once a process, at the first focus (phase 46). A worker who opens Peel first, then goes to copy the
  link and comes back (the case above) is not filled in by it; pasting now works instead.
- A reinstall that reuses a spent invite signs up with 0 sats and an inviter it cannot reach, with only the inviter-add toast to
  say so.
- `a=` still lives only in memory until signup: a Peel killed between the invite step and the end of signup loses it (the
  account and its invite are kept, so the inviter is still added).

## UNTESTED (needs a device)

- Paste the join link into "Paste your invite" on a new install: signup, then the Tasks tab opens signed in.
- Copy the join link, install Peel, open it: the invite step is filled in.
