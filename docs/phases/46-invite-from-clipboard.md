# Phase 46 — An invite carried across the install, on the clipboard

Status (2026-11-19): **built; JVM-tested (`InviteLinkTest`); not run on a device or emulator.** Strut's half is its join page
(`src/public-join.ts`, stakwork/strut, same day).

## Why

Phase 41 left this open: a worker without Peel taps the invite link, Chrome downloads the APK (the intent's fallback), they install
it, and Peel opens with nothing, because nothing carries a tapped link across an install (no Play Store, so no install referrer).
They had to go back to the browser and tap the link again. Evan, 2026-11-19: signup has to go all the way to the work.

## Built

- **Strut's join page copies the invite** on the tap that downloads Peel (`navigator.clipboard.writeText`, on the gesture):
  `sphinx.chat://?action=i&d=…&c=…&a=…`, the same link the intent carries.
- **Peel reads it at its first focus** (`MainActivity.takeClipboardInvite`): once a process, with no account on the device and no
  invite already in hand (a tapped link wins and stops the look), Peel reads the clipboard. Android 10+ lets only the focused app
  read it, so it is read in `onWindowFocusChanged`, after the notification permission's dialog has closed on a first launch.
  Android 12+ says "Peel pasted from your clipboard".
- **Only a whole invite link is taken** (`InviteLink.fromClipboard`): `sphinx.chat://?` (any case), exactly one `action=i`, a
  non-empty `d=`, no whitespace, at most `MAX_LENGTH`. Anything else someone copied is left alone and never lands in the field.
  Since phase 51, strut's join link itself (`https://<strut>/public/join#action=i&…`, copied from the message it came in) is
  taken too, as the `sphinx.chat://?` link the page would have opened Peel with.
- **Taken as a tapped link is** (`takeInvite`): the invite step opens filled in, and its `a=` waits to open the worker app after
  signup (phase 41). The clipboard is then cleared if it still holds that text. Never logged or stored.

## Not built / open

- A worker who copies something else between the download and opening Peel loses the handoff; the page's "Opened Peel without the
  invite? Tap here" fires the intent again, which works once Peel is installed.
- An account already on the device never reads the clipboard (it ignores an invite anyway).

## UNTESTED (needs a device)

- The whole path on a phone: the page's copy in Chrome, the APK install, Peel's first focus reading it on API 29+ (and after the
  notification dialog on 33+), the invite step filled in, the worker app opening after signup.
