# Phase 6 — Read state

## Status (2026-10-28)

**Built:** unread counts, synced across every device of the account the
same way PeelCore does it — read markers and mute levels live on the
server (`Sphinxrs.getReads`/`getMutes`/`read`/`mute`), not something one
device decides alone. `ChatsScreen` now has a real Unread section (direct
messages first, then tribes, newest first, never trimmed), and every
`ChatRow`/the agent's row shows a count badge and dims its name when
there's nothing new — PeelCore's own `ChatListRow`. Opening a chat
(`ConversationScreen`) marks it read, here and on the server, and keeps
marking it as new messages arrive while it's still open.

**Open:**

- No UI to mute a chat at all — `chatState.muteLevel` is read (another
  device's mute is respected: its badge stops showing) but nothing here
  can set one. No "mentions only": this port has no mention tracking, so
  a chat muted that way reads identically to fully muted (both just hide
  the count) — PeelCore's own three-tier `MuteLevel` is a two-tier
  approximation here.
- No "mark unread", no swipe actions, no per-row menu at all — PeelCore's
  `ChatLink`'s swipe-to-read/mute and context menu are both out; marking
  read only ever happens by opening a chat.
- `markRead` only ever moves a marker *forward* to "everything in this
  chat" — there's no equivalent of PeelCore's `markUnread` (moving it
  back to just before some message, to make a chat show unread again on
  purpose).
- Unread counting is coarser than PeelCore's own `countsAsUnreadSQL`:
  every non-`fromMe`, non-boost message past the marker counts, full
  stop — no filtering for control messages, no-words rows, or
  restricting to "real" content/payment/call types the way PeelCore
  does, since this port doesn't parse message bodies that finely. In
  practice this only over-counts rows nothing here renders specially
  yet anyway (Phase 5's own gap: a payment or membership note already
  shows as empty text).
  **Update (2026-10-29):** that reasoning went stale the moment Phase 9
  added boosts as a specially-rendered chip — a boost (type 29) from
  anyone, on anyone's message, was inflating the badge, which a user in
  an active tribe would actually notice. PeelCore's own
  `countsAsUnreadSQL` only excludes a boost that *doesn't* target this
  account's own message; this port has no column for what a boost
  targets (it's `replyUuid` inside the message's JSON `body`, parsed in
  Kotlin — `ConversationScreen.kt`'s `boostSummaries`), and
  `json_extract` isn't reliably on this app's minSdk 26, so
  `Storage.conversations()` now excludes every boost from the count
  instead (`type != 29`) — undercounts a boost on your own message, but
  no longer overcounts boosts between other people the way no filter at
  all did. A real, documented difference from PeelCore, not a silent
  one.
- No mentions at all, so the Unread section's PeelCore ordering (direct,
  then tribes that mention you, then other tribes) is simplified to
  direct then every unread tribe, no mention-based split.
- A device that was never online since this phase shipped starts with
  every chat showing fully unread (`chatState` empty, `lastRead` treated
  as -1 for everything) until the first `fetchReadsAndMutes` answers —
  expected, same as PeelCore's own first run with no local `chatState`,
  and it should settle within moments of connecting, not something to
  "fix".

## Journal

**Read almost everything in `Storage+Reads.swift`/`Engine.swift`'s read
half before writing a line.** This mechanism is bigger than it first
looks: unread counts, mention counts, mute levels, a `isBehind` flag,
`isNew` (someone who added this account before it was read), and a
whole offline-queueing dance (`readPending`, `pendingReads`,
`readsSentSinceGetReads`) for markers moved while there's no connection
to publish them to. Decided early what to actually port: the server-
synced marker and the unread count it drives, since that's the one
thing visibly missing from the dashboard; mentions, `isNew`, and
`isBehind` all need other features (mention parsing, a "new contact"
concept, a finer `isMoneyForMe`-style classification) this port doesn't
have yet, so they're left out rather than half-built.

**`sentStatus` isn't the only place a `RunReturn` field needs to be read
generically.** Phase 5 already established the pattern (whatever a
`RunReturn` carries gets handled in `storeMessages`, regardless of
which ask produced it, since there's no per-topic dispatch here the way
PeelCore's `receive(_:)` has). Read markers and mutes follow the exact
same shape — `getReads`'s and `getMutes`' own answers arrive
asynchronously like every other history-style ask — so they went into
the same function rather than inventing a second mechanism.

**`setRead`'s "never move backward" rule needed a second axis.** First
version only let the call through when `index` actually increased,
mirroring PeelCore's own `setRead`. That broke the one case this port
actually needs it for differently: flushing a *pending* marker once a
connection comes back resends the *same* index, only to clear the
`readPending` flag — under the index-only rule, that call was always a
no-op, and the flag never cleared. Fixed by also allowing the call
through when only `pending` changes at the same index. PeelCore doesn't
need this split: its `markRead` and `publishPendingReads` are already
driven by different data (the newest stored index vs. `chatState`'s own
pending column), where this port's `Engine.markRead` and
`fetchReadsAndMutes`'s flush both go through the one `setRead` function.

## Done

- `storage/Storage.kt`: `chatState` table (v5 migration — `lastRead`,
  `readPending`, `muteLevel`); `setRead`, `pendingReads`,
  `applyServerReads`, `applyServerMutes`, `highestServerIndex(chat)`;
  `conversations()` now also selects each chat's `unread` count and
  `muted` flag; `ConversationRow.shownUnread` (0 when muted, same as
  PeelCore's `ChatReadState.shownUnread` collapsed to two tiers).
- `engine/Engine.kt`: `markRead(chat)`; `fetchReadsAndMutes` (asks
  `getReads`/`getMutes`, then flushes anything still pending), called
  from `start()`/`resume()` same as the history catch-up; `storeMessages`
  now also applies `RunReturn.lastRead`/`muteLevels` whenever present.
- `ui/dashboard/ChatSections.kt`: `unread` — direct first, then tribes,
  never trimmed; a chat there is excluded from `tribes`/`direct`.
- `ui/dashboard/ChatsScreen.kt`: the Unread section (no collapse, same as
  PeelCore); `ChatRow`/`AgentChatRow` show an `UnreadBadge` and dim an
  already-read chat's name.
- `ui/dashboard/ConversationScreen.kt`: `markRead` on every reload, not
  only the first — a message arriving while the chat is still open gets
  caught too.
- Tests: `StorageTest` cases for unread counting, muting, and `setRead`'s
  forward-only/pending-clearing rules; `ChatSectionsTest` cases for the
  Unread section's sorting, its never trimming, and a muted chat never
  landing there. 53 tests passing.

## Learned

- A rule framed as "only move forward" can be hiding two different
  things that both need to move forward independently (here: the marker
  itself, and whether it's still waiting to reach the server) — worth
  checking whether a guard condition is really one invariant or two
  before copying it as a single comparison.
- Reading the *whole* mechanism behind a feature before porting any of
  it (not just the one struct or function that seemed relevant) is what
  surfaces which parts are load-bearing for the one thing actually
  being built (the synced marker) versus along for the ride in the
  reference implementation (mentions, `isNew`, `isBehind`) — scoping
  that correctly up front beats discovering it mid-port.
