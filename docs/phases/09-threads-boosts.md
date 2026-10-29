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
message starts a thread at that message. Every message carrying a
`threadUuid` is pulled out of the main list and grouped by it
(`threadSummaries`); the root keeps its ordinary place in the chat and
grows an "N replies" row. Tapping it opens `ThreadScreen` — the root,
every reply in order, and a composer that always replies to the root.

**Open:**

- No calls — Open/Join rows, the call banner, the full-screen call UI:
  all still to come, as their own task (LiveKit, real-time connection,
  not a UI change).
- The thread sheet's composer is text only — no attach button, so a
  photo can't be sent into a thread yet, only the main chat.
- The collapsed thread row keeps the root's own chronological position
  in the list, rather than bumping to the position of its newest reply
  the way PeelCore's own collapsed row does (`row.date = message.date`,
  the *latest* message's date) — a real, visible difference: a thread
  that just got a new reply won't visually "jump" to the bottom of an
  active chat the way the real app's does. Simpler to build, and still
  correct about *what's* shown, just not *where*.
- No participant avatars on the "N replies" row (PeelCore shows up to
  three small overlapping avatars of who's replied) — text only.
- No unread count specific to a thread (PeelCore's own `ThreadSummary.unread`)
  — Phase 6's read-state tracks a chat's marker, not a thread's own.
- A reply sent *from inside* a thread only ever replies to the thread's
  root (`replyTo = inThread = threadRootUuid` always) — PeelCore allows
  replying to any message within a thread, which can start a *nested*
  thread at that reply. This port keeps every thread flat: one root,
  one flat list of replies, no nesting.
- No boost sending — this port has no payment capability at all, so
  every boost shown is one already in stored history (restored or
  live), never one this device originates. Matches the same scope line
  attachments drew for sending vs. receiving.
- A deleted boost or deleted thread reply still counts — this port
  tracks no `deleted` flag on any message (not Phase 5's scope, not
  this one's), so neither a boost total nor a thread's reply count can
  ever shrink the way PeelCore's own can.

## Decided

- Both boosts and threads reuse the exact mechanism replies already
  established (Phase 7): a message names another by `replyUuid` in its
  own JSON body, looked up against whatever's already loaded in memory
  — no new storage column, no new query, for either feature. A boost
  is "a message whose type says boost, found by scanning what's
  already loaded"; a thread is "a message whose body happens to also
  carry `threadUuid`, grouped the same way."
- The thread sheet is its own composable (`ThreadScreen`), not a mode
  of `ConversationScreen`'s own list — PeelCore's `ThreadSheet` is a
  separate sheet too, with its own composer always addressed at the
  thread's root, not a filtered view of the same screen.

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

**Chose not to replicate the "bump to the newest reply" positioning.**
PeelCore's collapsed row uses the *latest* reply's own date for where
it sits in the list, so an old message with a brand new reply jumps to
the bottom of an active chat. Matching that means tracking, for every
thread, which of its own position in `displayedMessages` it should
actually occupy — real work for a visual behavior that's secondary to
"can you see that something has replies and open them," which is what
actually matters for a first version. Named as an open gap rather than
silently different.

## Done

- `engine/Engine.kt`: `sendMessage`/`sendImageAttachment` gained
  `inThread: String? = null`, written as `threadUuid` the same way
  `replyTo` already writes `replyUuid`.
- `ui/dashboard/ConversationScreen.kt`: `BOOST_MESSAGE_TYPE`,
  `BoostSummary`, `boostSummaries`; `threadUuid`, `threadRoot`,
  `ThreadSummaryData`, `threadSummaries`; both kinds of row excluded
  from the main list and summarized on whatever they target instead;
  `BoostChip`, `ThreadSummaryRow`; `ThreadScreen` (the thread sheet —
  root, replies, its own composer); the main composer's `onSend` and
  the image-send path both now compute `inThread` from the reply
  target's own `threadRoot`.

No new tests: everything here is Compose UI plus pure grouping
functions over data already covered by existing `MessageRow`/body
parsing, in a screen already noted (Phase 5) as not unit-tested.

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
