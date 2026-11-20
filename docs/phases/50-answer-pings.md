# Phase 50 — Payments to this account arrive: pings answered, payments settled

Status (2026-11-19): **built; JVM-tested; run end to end on an emulator against strut.stadeum.stakwork.ai (below); not
yet on a phone.**

## Why

Seen on 2026-11-19 on a Pixel 9a (Peel 0.1.14), signing up with a worker invite from strut.stadeum.stakwork.ai: the inviter,
the box's treasury daemon ("Stakwork Support"), got the new account's key exchange and answered it, but the answer never
arrived. Sending to Support then failed in the core with `no contact key found`. Only a plain message from Support (which
carries its key) made the chat work.

The answer to a key exchange carries sats (`PRICE_TO_MEET`), and every payment to a contact goes the asynchronous way
(sphinx's mixer, `ping.rs`):
1. The sender's server holds the payment and pings this account's server, which passes the ping on (topic kind `ping`).
2. The core's answer to the ping (`handle` → `handle_ping`) is an "online" message to the sender's server, in `topics`.
3. Only when that arrives does the server hand over the payment, on the stream, with its message inside. The core's answer
   to it settles it (`settle_topic`); a payment within one server is held until then (`localpay.rs`) and goes back to its
   sender after a week.

Android published what `handle` returns only for a live key exchange (phase 21's "Not widened here"), so it never sent the
online message, and never asked for pings held while it was away (`fetchPings`). No payment to an Android account arrived:
not a key exchange's answer, sats in a chat, a boost, nor an employer's payout. A payment that did come (an invoice paid
through the server's node) was not settled either.

## Built

- **What `handle` asks to send is published** (`Engine.publishHandled`, rule in `InboundPublish`, `Pings.kt`): a ping's
  online message, a payment's settle, and (unchanged) a live key exchange's confirmation. Published in `PublishPlan` order
  (the settle first), without waiting for an async pay, under the lock like the commit. A key exchange is still never
  answered while a restore replays history; a ping or a settle only ever comes live. PeelCore publishes every result; this
  port these.
- **A ping before registering is let go** (`handleInbound`), as PeelCore's `receive`: answering needs this account's route
  hint, and the server passes it on again.
- **Pings asked for on coming online** (`afterConnect`, first, best effort): `fetchPings`, as PeelCore's
  `cameOnlineSteps`. The server passes on every ping it holds for this account and hands over again the payments held for it
  (`localpay::spawn_redeliver`).
- **Pings acknowledged** (`PingBook`, PeelCore's `notePing`/`pingsByHash`/`pingsByTag` and `followUp`'s `pingDone`): each
  handled ping is noted (`RunReturn.ping`, `<hash>:<time>:<tag>`); when the payment it was for comes (a message with that
  `paymentHash`), or the online answer is refused because the payment is gone (`async pay not found: <tag>`), `pingDone` tells
  the server to stop passing it on. Once each; in memory only, as on iOS (one a restart forgets is answered again at the next
  `fetchPings`, and acknowledged then).

## Not built

- Sats waiting for offline people (money-parity #2): our own async pays are still not tracked as pending rows.
- PeelCore's `cappedToArrived` (a payment's amount shown capped at what the topic says arrived): the core already refuses an
  onion that claims more than its HTLC.

## Tests

`PingsTest`: the topic kind, the publish rule (restore included), a ping acknowledged once when its payment comes, by its tag
when its payment is gone, and malformed pings and other errors acknowledging nothing. The native-core paths (`handle`,
`fetchPings`, `pingDone`) are untested on the JVM.

## Live run (2026-11-19, emulator)

A debug build on an Android 35 arm64 emulator signed up with an unused worker invite from strut.stadeum.stakwork.ai
(`sphinx.chat://?action=i&d=…&c=…`, without `a=`). A minute after catching up it added the inviter; within a second it
received three messages: its own contact record (index 0, unfiled as before), the treasury's key confirmation, and the record
that confirmation adds (index 2). It then sent to Stakwork Support before Support had written: Delivered, and strut/staff's
Support kept it from the new key. No `PeelEngine` warnings. The same flow on the Pixel with 0.1.14 received index 0 alone and
the send failed in the core with `no contact key found`.

Not run: the same on a phone; a payment in a chat or a payout to an Android account (the same path); a ping held while the app
was away, then asked for (`fetchPings`).
