# Phase 9 — Threads and boosts

## Status (2026-10-29)

**Built, from a review of real iOS screenshots against what Android had:**
boosts (a bolt-and-sats chip under the message they boost) and threads
(a message with any replies collapses to "N replies"; tapping it opens
a thread sheet with its own composer). Calls — the third thing the
screenshots showed missing — are explicitly deferred: they need a whole
SDK (LiveKit) this port has none of, not a UI gap, and are their own
future task.

**Boosts:** a boost is a message (type 29) naming what it boosts by the
same `replyUuid` field a reply uses. Summed per target message
(`boostSummaries`) and shown as a chip (PeelCore's own `BoostChipView`)
— never as a row of its own, the same way an attachment's caption and
an attachment aren't two rows.

**Threads:** sending a reply now also tags `threadUuid` — PeelCore's own
`threadRoot` rule (`threadUuid ?? replyUuid ?? uuid`): a reply to a
message already in a thread joins that thread; a reply to a plain
message starts a thread at that message. A message carrying a
`threadUuid` is pulled out of the main list and grouped by it
(`threadSummaries`) **only once it's a real thread — a tribe with 2+
messages in it** (fixed 2026-10-29, see below); the root grows an "N
replies" row and moves to its thread's newest reply's position in the
list, the same as PeelCore's own `ChatRow.rows` (which overwrites the
collapsed row's `date` with the thread's latest surviving message's
date, not the root's own) — so an old message with a brand new reply
jumps to where that reply would sit, same as the real app. Tapping it
opens `ThreadScreen` — the root, every reply in order, and a composer
that always replies to the root.

**Update (2026-10-29), from a full iOS-vs-Android audit:** two real
scope bugs fixed, both in `threadSummaries`/`ConversationScreen.kt`'s
`displayedMessages` filter.

A reply in a DIRECT message used to collapse exactly the same as a
tribe's — it never should have. PeelCore's `Storage+Threads.swift`
`timeline(in:)` only builds the thread-summary dictionary `if isTribe`;
a DM's timeline never collapses at all, so a reply there always stays
an ordinary row with its own quote box. And within a tribe, a *single*
reply used to collapse immediately to "1 reply" — PeelCore's own
`threadsSQL` only counts something a thread once it has `COUNT(*) >=
2`; the first reply alone still renders normally, only the second one
triggers the collapse. `threadSummaries` is now called only when
`isTribe`, and only keeps a root whose replies number 2 or more.

**Update (2026-10-29), the pending thread items:** two of the Open
items below closed.

The thread sheet now shares the exact same `Composer` the main screen
uses — attach menu (Photo/Camera/File), the press-and-hold mic, all of
it — instead of its own hand-rolled text-only row. Every path still
always addresses the root (`replyTo = inThread = threadRootUuid`),
same as the text send already did; nothing about *what* a thread
reply can carry changed except that it's no longer limited to text.

The "N replies" row now shows up to three small overlapping avatars of
who's recently replied, PeelCore's own `ThreadSummaryButton`.
`senderDisplay` — a row's name, photo and avatar-colour key, the exact
derivation `MessageRowView` already did inline — is now its own
function, since the participant list needed the same derivation a
second time; `threadSummaries` picks the newest reply from each
distinct person (by hue key, or "me" for anything `fromMe`), up to
three.

**Update (2026-10-29), boost sending:** long-pressing someone else's
message (not yet boosted by this account) now offers "Boost 100 sats"
alongside "Reply" — `Engine.sendBoost`, a type 29 message naming the
target by `replyUuid`, carrying a fixed 100-sat amount through the
same `send` call every other message already goes through
(`coreSend`'s own `amtMsat` parameter, already there, just always `0u`
until now). This turned out not to need the payment infrastructure
the "Open" item below once assumed it would: the core's `send` call
already accepts a nonzero amount directly, and PeelCore's own
client-side balance/confirmation checks (`checkPayment`,
`spendableMsat`, a confirmation threshold) are a UX safeguard against
a send that would obviously fail, not something the underlying
mechanism requires to work at all. Skipped here on purpose — see
"Decided".

**Update (2026-10-29), a second full iOS-vs-Android audit:** three more
real fixes, all in the tribe-pricing/thread-avatar work this same day's
earlier audit didn't yet cover.

`TribeInfo.fetch` built its result straight from whatever the registry
answered, never checking the `pubkey` field it got back actually
matched the `pubkey` it asked for — PeelCore's own `TribeInfo.fetch`
guards this explicitly (`guard info.pubkey == pubkey else { throw
.tribeServerMismatch }`). The caller (`Engine.refreshTribeNames`) stores
the result keyed by the *requested* pubkey regardless, so a registry
answering for the wrong tribe — a caching bug, a misrouted response —
would have silently overwritten this tribe's name, photo, and (since
this phase's own pricing work) its *price* with another tribe's own
data, mispricing every later send to it with no error anywhere. Fixed:
`TribeInfo.fetch` now throws `MismatchException` when the pubkeys
disagree, caught by `refreshTribeNames`'s own existing per-tribe
`try`/`catch` the same way any other fetch failure already is.

`tribeMessageAmountMsat`/`tribePaymentPriceMsat` added
`pricePerMessageMsat + escrowMsat` with plain `Long` addition, which
wraps silently on overflow rather than throwing — PeelCore's own
`Tribe.messageAmount` uses `addingReportingOverflow`, saturating at
`Int64.max` instead. Both fields come from the tribes registry, not
anything this account chose; a buggy or compromised registry
answering with a price near `Long.MAX_VALUE` would wrap the sum
negative, which `coerceAtLeast` would then clamp back up to the floor
— the wrong number for the wrong reason, even though it happens to
land somewhere harmless. Fixed with a small saturating-add helper,
`saturatingTribePrice`, shared by both functions.

`threadSummaries` counted *any* row carrying a `threadUuid` toward
whether something is a real thread, who its recent repliers are, and
where its root bumps to — no check on message type or on whether a
text reply actually has any content, unlike PeelCore's own
`threadMessage` SQL filter (`type IN contentSQL AND noWords = 0`),
applied to the same three things. Unreachable from this port's own
send paths today (nothing here can produce a non-text/attachment
threaded row, and the composer refuses to send blank text) — reachable
from a message any other Sphinx client sharing the chat sends, which
isn't bound by that restriction. Fixed with `isThreadContent(row)`
(content-type check `canBoost` already uses, plus "a text reply must
have actual words"), applied inside `threadSummaries`'s own grouping
step. Left `ThreadScreen`'s own reply list unfiltered on purpose —
once a thread is genuinely open, showing everything addressed to its
root seemed more useful than hiding a control message from a view the
person explicitly asked to see, and nothing confirmed iOS's own thread
*view* (as opposed to the threshold/avatar queries) filters that list
either.

Everything else this second audit checked — both boost call sites
computing the same amount, `canBoost` against `isBoostable`, the
`CONFLICT_REPLACE` price-preservation fix from earlier the same day,
`rowSortKey`'s interaction with a priced thread — matched, no further
changes.

**Open:**

- No calls — Open/Join rows, the call banner, the full-screen call UI:
  all still to come, as their own task (LiveKit, real-time connection,
  not a UI change).
- No unread count specific to a thread (PeelCore's own `ThreadSummary.unread`)
  — Phase 6's read-state tracks a chat's marker, not a thread's own.
  Left open on purpose this round: PeelCore's own version needs its
  own per-thread read marker, a genuinely new storage mechanism, not
  a small addition on top of what Phase 6 already tracks.
- A reply sent *from inside* a thread only ever replies to the thread's
  root (`replyTo = inThread = threadRootUuid` always) — PeelCore allows
  replying to any message within a thread, which can start a *nested*
  thread at that reply. This port keeps every thread flat: one root,
  one flat list of replies, no nesting.
- No balance check or confirmation threshold on a boost — sent
  unconditionally at whatever `Engine.boostAmountMsat()` reads. A
  boost this account genuinely can't afford fails the same way any
  other send can (a plain error, nothing kept to retry) rather than
  being refused ahead of time the way PeelCore's own `checkPayment`
  refuses it client-side.
  **Update (2026-10-29):** the amount itself is no longer fixed —
  Phase 11 added a "Boost amount" setting in Profile, synced across
  this account's devices the same way PeelCore's own
  `setBoostAmount`/`tip_amount` is — see
  [phases/11-profile-preferences.md](11-profile-preferences.md). Only
  the balance/confirmation safeguards around *sending* stay open.
- **Fixed (2026-10-29):** a boost to a tribe now adds its own price and
  escrow on top of the tip, PeelCore's own `boostTotalMsat`/
  `paymentPriceMsat` (no floor, unlike an ordinary message) —
  `Engine.tribePaymentPriceMsat`, reading the same `contact.pricePerMessageMsat`/
  `escrowMsat` columns a tribe message now also charges (see
  [phases/05-conversation.md](05-conversation.md)'s own "Update"). Was
  "no `tribe` table at all" when first written; the two columns this
  needed turned out to fit on `contact` instead, the same trimming its
  own `isTribe` column already did for that table.
- No "Copy", "Copy link" or "Delete" in the long-press menu — PeelCore's
  own `MessageMenu` has all three; this port's menu only ever offered
  Reply before this round, and only gained Boost alongside it, not the
  rest.
- A deleted boost or deleted thread reply still counts — this port
  tracks no `deleted` flag on any message (not Phase 5's scope, not
  this one's), so neither a boost total nor a thread's reply count can
  ever shrink the way PeelCore's own can.

## Decided

- Both boosts and threads reuse the exact mechanism replies already
  established (Phase 7): a message names another by `replyUuid`. A
  boost is "a message whose type says boost, naming what it boosts the
  same field a reply uses"; a thread is "a message whose body also
  carries `threadUuid`, grouped the same way."
  **Update (2026-10-29):** this originally meant no new storage column
  — `replyUuid`/`threadUuid` stayed inside each message's raw JSON
  `body`, re-parsed in Kotlin (`ConversationScreen.kt`'s own
  `replyUuid`/`threadUuid` functions) everywhere a reply, thread or
  boost needed to know it. That held until the unread-count fix
  ([phases/06-read-state.md](06-read-state.md)) needed to ask "does
  this boost target one of my own messages" from *SQL*, which raw JSON
  in a text column can't answer without `json_extract` (not reliable
  at this app's minSdk 26). `message` now has real `replyUuid`/
  `threadUuid` columns (`Storage.v6`), derived once at the one place
  every message is written (`insertMessages`, from `body`, which stays
  the source of truth) rather than re-parsed on every read — PeelCore's
  own typed `Message.replyUUID` was always a real column for the same
  reason. A migration backfills every row already stored before v6.
  `ConversationScreen.kt`'s own `replyUuid`/`threadUuid` functions are
  gone; every call site reads `row.replyUuid`/`row.threadUuid` directly
  now.
- The thread sheet is its own composable (`ThreadScreen`), not a mode
  of `ConversationScreen`'s own list — PeelCore's `ThreadSheet` is a
  separate sheet too, with its own composer always addressed at the
  thread's root, not a filtered view of the same screen.
  **Update (2026-10-29):** "its own composable" never meant "its own
  composer" — once the main screen's `Composer` grew an attach menu
  and a mic (Phase 8), `ThreadScreen`'s hand-rolled text-only row was
  just a second, incomplete copy of the same thing. Replaced it with
  the shared `Composer`, with `ThreadScreen` supplying its own
  send/record/attach callbacks (every one addressed at the root)
  the same way the main screen supplies its own. `ThreadScreen` keeps
  its own picker/recorder state and launchers rather than sharing the
  main screen's — the two screens are still genuinely separate
  composables, just sharing the one UI component between them.
- A boost sends at a fixed amount, with no client-side balance check
  or confirmation threshold — PeelCore's own `quickBoost` only skips
  *confirmation* for its one-tap path (the amount itself was already
  approved when it was set), it still always checks the balance first
  (`checkPayment`). This port has no balance to check at all (no
  Payments tab, no payment history sync), so there's nothing to check
  against; a boost that can't be paid for fails like any other send,
  reported as a plain error. Matches the attachment-sending precedent
  (no draft-row-first design, no retry) rather than inventing a
  smaller version of PeelCore's own payment-safety machinery for one
  call site.
- Long-press now opens a small menu (Reply, and Boost when
  applicable) instead of replying immediately — the minimum change
  needed to offer a second action without inventing a new gesture,
  and the same shape PeelCore's own long-press menu already uses
  (`MessageMenu`), just with two of its five actions instead of all
  five.

## Journal

**Read the screenshots literally before assuming a feature's shape.**
The user's own screenshots showed the exact things to build: a call
banner and Open/Join rows in one, a full-screen call UI in another, a
thread sheet with a quoted "You:" reply and a boost chip in a third, a
tribe's main view with the *same* thread collapsed to "3 replies" in a
fourth. Reading PeelCore's actual `ChatRow.rows(...)` function
afterward confirmed what the screenshots already showed — a message
collapses the moment it has *any* reply, not past some minimum count —
rather than guessing a threshold that would have been wrong.

**A thread's root doesn't itself carry `threadUuid` — only its replies
do.** Worth stating since it's easy to get backwards: the root message
is just an ordinary message, found by *other* messages naming it via
`threadUuid`. This is what let the root keep rendering completely
normally in the main list (as `MessageRowView` already draws any
message) with only the "N replies" row *added* beneath it, rather than
needing a special "this is a thread root" render path.

**Added the "bump to the newest reply" positioning after all, once it
turned out to matter.** First built without it (see above, an earlier
version of this doc named it an open gap); the user flagged it as
critical once they noticed an old thread not moving when it got a new
reply. Reading PeelCore's `ChatRow.rows` closely showed the real
mechanism isn't a second sort pass at all — the SQL feeding it already
drops every thread message except the newest one with words, so the
survivor's own position in the (already timestamp-sorted) query result
*is* the thread's position; `ChatRow.rows` just copies that survivor's
date onto the row for its own bookkeeping. Android has no such
filtering SQL (thread replies are filtered out of `displayedMessages`
in Kotlin, not in the `Storage.messages` query), so the equivalent had
to be done explicitly: `rowSortKey` gives a thread's root the later of
its own timestamp and its thread's latest reply's timestamp, and
`displayedMessages` is sorted by that key (a stable sort, so anything
not in a thread keeps its original order). Same visible result, a
different mechanism — Kotlin doesn't have the SQL layer iOS's GRDB
query does this in.

**Reused the main composer instead of teaching the thread one to
attach things too.** The instinct when a hand-rolled composer needs a
feature another hand-rolled composer already has is to copy the
missing piece over — the attach menu, the mic button, the gesture
loop, the sending-label text. All of that already existed, correctly,
in `Composer`; `ThreadScreen`'s own composer Row was never a
deliberately different design, just an earlier, smaller version of the
same thing written before attachments existed at all. Replacing it
outright with the shared component, rather than porting pieces of it
over one at a time, is what let `ThreadScreen` end up with the exact
same slide-to-cancel and file-picker behavior the main screen has,
with no second copy to keep in sync by hand from here on.

**The participant-avatar derivation was already written once —
finding that mattered more than writing it a second time.**
`MessageRowView` already computed a row's name/photo/hue-key inline;
the temptation was to write a similar-but-not-identical version for
the thread-summary's avatars (it only needs three distinct people, not
every row). Factoring the existing logic into `senderDisplay` first,
then calling it from both places, kept the "tribe member keyed on
alias, not pubkey" rule — a real, previously-fixed bug (Phase 4's
avatar-collision fix) — from needing to be gotten right twice.

**Checked whether "no payment capability" actually meant "can't send
a boost" before accepting it as a blocker.** The Open list said
exactly that, carried over from when sending images first needed its
own infrastructure (encryption, upload, a media token). Boost sending
looked like it would need the same kind of lift, this time for
money — a balance, a confirmation threshold, payment history. Reading
`Engine+Payments.swift` in full showed the actual dependency is
narrower: the core's own `send` call already takes an `amtMsat`
parameter (Android's `coreSend` binding has carried it, unused, as a
hardcoded `0u`, since Phase 5) — the Lightning routing happens inside
the core/mixer when given a nonzero amount, not in anything the
Swift or Kotlin side builds. PeelCore's balance check and confirmation
threshold are a real feature (ask before an obviously-doomed send,
or a surprising one), but a *safeguard* around a mechanism that works
without them, not a precondition for it to work at all. Sending a
boost that skips the safeguard and just lets a real failure surface
as an error is a materially smaller, honestly-scoped feature — not
the same thing as building "payments."

## Done

- `engine/Engine.kt`: `sendMessage`/`sendImageAttachment` gained
  `inThread: String? = null`, written as `threadUuid` the same way
  `replyTo` already writes `replyUuid`.
- `ui/dashboard/ConversationScreen.kt`: `BOOST_MESSAGE_TYPE`,
  `BoostSummary`, `boostSummaries`; `threadUuid`, `threadRoot`,
  `ThreadSummaryData`, `threadSummaries`; both kinds of row excluded
  from the main list and summarized on whatever they target instead;
  `rowSortKey` bumps a thread's root to its latest reply's position;
  `BoostChip`, `ThreadSummaryRow`; `ThreadScreen` (the thread sheet —
  root, replies, its own composer); the main composer's `onSend` and
  the image-send path both now compute `inThread` from the reply
  target's own `threadRoot`.

**Also done (2026-10-29), the pending thread items:**

- `ui/dashboard/ConversationScreen.kt`: `senderDisplay(row, myAlias,
  isTribe)` factored out of `MessageRowView`'s own inline
  name/photo/hue-key derivation; `ThreadParticipant`; `threadSummaries`
  now also returns up to three, newest-reply-first, by distinct
  person; `ThreadSummaryRow` renders them as small overlapping
  `Avatar`s before the "N replies" text. `ThreadScreen` rebuilt on the
  shared `Composer` — its own picker/camera/file launchers, its own
  `VoiceRecorder`, its own permission handling, every send path always
  addressed at `threadRootUuid`; its old hand-rolled text-only
  composer Row is gone.

**Also done (2026-10-29), boost sending:**

- `engine/Attachment.kt`: `BOOST_MESSAGE_TYPE` (29) and
  `BOOST_AMOUNT_MSAT` (100,000 msat) moved here from
  `ConversationScreen.kt`'s own private copy, so `Engine.kt` can use
  them too — the same reason `ATTACHMENT_MESSAGE_TYPE` already lives
  here.
- `engine/Engine.kt`: `sendBoost(chat, targetUuid, isTribe)` — a type
  29 message (`{"content": "", "replyUuid": targetUuid}`) through the
  same `run`/`coreSend` path every other send already uses, now
  finally passing a nonzero `amtMsat`.
- `ui/dashboard/ConversationScreen.kt`: `canBoost(row, boosts)`
  (someone else's content message, not yet boosted by this account);
  `MessageRowView` gained `onBoost: (() -> Unit)?`, and its long-press
  gesture now opens a small `DropdownMenu` (Reply, and Boost when
  `onBoost` isn't null) instead of replying immediately; both the main
  screen and `ThreadScreen` compute `boostSummaries` and wire
  `onBoost` the same way for every row they show, root and replies
  alike.

No new tests: `Engine.sendBoost` needs the native `sphinxrs` library
(no host build for a plain JVM test, same gap every other `Engine`
send function has); `canBoost` and the rest are Compose UI/pure
functions in a screen already noted (Phase 5) as not unit-tested.

**Also done (2026-10-29), the second audit's fixes:**

- `engine/TribeInfo.kt`: `fetch` now throws `MismatchException` when
  the registry's own `pubkey` field disagrees with the one requested,
  instead of trusting the response blindly.
- `engine/Engine.kt`: `saturatingTribePrice` — the shared, overflow-safe
  `pricePerMessageMsat + escrowMsat` both `tribeMessageAmountMsat` and
  `tribePaymentPriceMsat` now call instead of a plain `+`.
- `ui/dashboard/ConversationScreen.kt`: `isThreadContent(row)`, applied
  inside `threadSummaries`'s own grouping step.

No new tests here either, same reasons as above.

## Learned

- Two features (boosts, threads) that look unrelated on the screen
  turned out to share their entire data-access pattern with a feature
  already built (reply's `replyUuid` lookup against in-memory
  messages) — worth checking whether a new feature is really a new
  *shape* of data access or just a new *interpretation* of one already
  in place before building a second mechanism for it.
- A "collapse behind a summary" UI pattern (threads here, same idea as
  Phase 4's agent-pinning or Phase 6's Unread section) keeps coming up
  as: filter the item out of the normal list, compute a lookup map
  keyed by whatever it targets, decorate the target's own row from
  that map — a reusable shape worth reaching for by default once a
  message needs to affect how a *different* row renders.
- When two composables need the same piece of per-row derivation
  (here, a sender's name/photo/hue), checking whether one of them
  already computes it inline — rather than assuming a new helper is
  needed — both avoids duplicating a rule that's already been
  corrected once (the tribe-member hue fix) and keeps that correction
  in exactly one place for whatever reads it next.
- A screen's own composer catching up to a shared component's feature
  set is often cheaper done by *replacing* the screen's version with
  the shared one than by porting the shared one's features into it
  piece by piece — the second approach recreates the exact
  copy-drifts-out-of-sync problem a shared component exists to avoid.
- "No payment capability" as a blocker was true for *attachments*
  (which genuinely needed encryption, upload, a media token — real
  new infrastructure) but turned out false for *this* feature, which
  only needed a parameter the send path already had wired through and
  never used. The same words in an "Open" note can describe two
  differently-sized gaps depending on what the feature actually is;
  worth re-deriving what a blocker really requires rather than
  inheriting its size from an earlier, differently-shaped feature
  that happened to use similar words.
