# Phase 5 — Conversation

## Status (2026-10-28)

**Built:** tapping a chat (a tribe, a direct contact, or the agent's
pinned row) in `ChatsScreen` opens `ConversationScreen` in place of the
whole tab area, bottom bar included — PeelCore's own
`ConversationScreen.swift` hides the tab bar the same way. It shows that
chat's stored history, flat rows grouped into runs the way the Mac/iPhone
draw them (a run's first row carries the avatar, the sender's name in
their hue, and the time; the rest of the run is just text under it), and
a composer at the bottom that actually sends: `Engine.sendMessage` calls
the core's `send`, stores the row once sent, and the screen's own poll
picks it up. A tribe message is attributed to whichever member's `alias`
its own `sender` JSON carries — the chat's own pubkey is shared by every
member there (`Engine.contactHint`'s own finding from Phase 4), so a run
groups by that alias, not by `chat`.

**Open:**

- No price: every send goes at 0 msat, whatever the tribe (or, later, a
  paid DM) actually charges — nothing here reads `TribeInfo.pricePerMessage`
  yet. A tribe that charges for messages may refuse a send until that
  exists; nothing here can yet tell the person that happened beyond
  whatever error text the core's own refusal carries.
- No reply, thread, delete, boost, pin, mention or attachment — PeelCore's
  composer and row views are built around all of these; this phase is
  text in, text out, nothing else. `MessageType` is hardcoded to 0
  (plain text) throughout; nothing here reads or writes any other type
  even when one already exists in stored history (a payment, an
  attachment, a membership note) — those rows show as empty text today,
  not specially rendered.
- No delivery status at all: a sent row has no "sending/sent/failed"
  state, and a send that throws only shows a one-line error under the
  list — nothing retries it, and the text is already cleared from the
  composer by the time the error lands (optimistic-clear, same spirit as
  PeelCore's own pending-then-confirmed row, minus the confirmation).
- No read receipts, no "seen", no typing indicator, no local time for a
  direct contact, no member count for a tribe's subtitle — the header is
  just a name (and AGENT, for the agent).
- Calls, the More menu, the pinned-message bar, "you can't post in this
  tribe" (a tribe this account left, or was removed from, still shows a
  composer here) are all out — PeelCore's own `footer`/`header` cover all
  of these; this phase ports only the parts that let text go back and
  forth.
- No pagination: `Storage.messages` reads a whole chat's history in one
  query. Fine for how little any one chat has so far; revisit once a
  busy tribe's history is large enough to matter.
- No reactive query layer (same gap `ChatsScreen` already has): a 3-second
  poll, plus a reload right after sending — a live message from someone
  else still waits up to 3 seconds to show.

**Decided:**

- The outgoing row is built by hand from what was just sent (the body,
  the chat, now), not run through `Engine.contactHint`/`storeMessages`
  — PeelCore's own `deliver` does the same: `send`'s own `RunReturn.msgs`
  entry is read only for its `uuid`/`tag`/`paymentHash`, confirmation
  that it went, not a message to classify. Running a sent row through
  `contactHint` the way a received one is would ask it to extract a
  contact's alias/photo from our own outgoing JSON, which carries none
  worth reading (see Phase 4's Journal on why that branch exists at all).
- Runs group by the message's own claimed sender alias for anything not
  `fromMe`, and by a constant key for anything that is — not by `chat`,
  which is shared by an entire tribe's membership and would merge every
  member's messages into one run.

## Journal

**A sent message needed its own storage path, not `storeMessages`.**
Tried first running `send`'s result through the exact same
`contactHint`/`storeMessages` pipeline restore and live messages already
use, since "a message is a message either way" was the reasoning for
sharing that pipeline between the listener and a restore's pages
(Phase 3/4). It does not hold for a message this device just originated:
`send`'s own echo-less `Msg` carries little meaningful `sender` JSON
(nothing worth asking `contactHint` about), and PeelCore's own `deliver`
confirms that's expected — it only reads `uuid`/`tag`/`paymentHash` off
`result.msgs.first`, building the rest of the stored row from what it
already knows was just sent. Matched that rather than force a received-
message shape onto something that isn't one.

**A tribe's messages can't be grouped by `chat`.** The flat-row design
groups consecutive messages from the same person under one avatar/name.
Grouping by `chat` (as `ChatsScreen`'s rows, one per *chat*, correctly
do) would merge an entire tribe's membership into a single run, since
every member's forwarded message shares the tribe's own pubkey as `chat`
(the same fact Phase 4 had to work around for contact detection).
Grouped by the message's own claimed `sender.alias` instead, which does
name the actual author per message.

## Done

- `engine/Engine.kt`: `sendMessage(chat, text, isTribe)` — calls the
  core's `send` through `run` (so it still commits+publishes under the
  one lock any other call does), stores the outgoing row by hand.
- `storage/Storage.kt`: `messages(chat)` — one chat's rows, oldest first;
  `MessageRow` gained `id` (the table's own row id, for a stable list key).
- `ui/dashboard/ConversationScreen.kt`: the chat screen — header with
  back and the name (AGENT tag for the agent), the grouped message list,
  the text composer and its send button, a one-line error on a failed send.
- `ui/dashboard/ChatsScreen.kt`/`MainTabs.kt`: a tapped row opens the
  conversation in place of the whole tab area (bar included); Back
  returns to whichever tab was open.
- Tests: new `StorageTest` case for `messages(chat)`. 41 tests passing.
  `Engine.sendMessage` itself is not unit-tested — `Engine` has no host
  build of the native `sphinxrs` library to run against (same gap
  `phases/01-foundation.md`'s "Learned" already names for the rest of
  `Engine`).

## Learned

- Not every `RunReturn` is a page of messages to classify the same way —
  a result this device's own `send` just produced needs its row built
  from what the caller already knows, not read back out of the result
  the way a restore or a live receive does. Sharing one storage path for
  "any `RunReturn` with `.msgs`" worked for Phase 3/4 because restore
  pages and live receives really are the same shape; a fresh send isn't,
  and forcing it through the same path would have been the wrong kind of
  reuse.
