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

**Also built, in a second pass:** delivery status — a sent row now
carries `PENDING`/`DELIVERED`/`FAILED` (PeelCore's `Message.Delivery`,
trimmed; no `UNSETTLED`, which is specifically about a payment the mixer
handed off but hasn't confirmed, not relevant to a free text send). A
pending message shows dimmed, the same `0.6` opacity PeelCore's own row
uses while `isSending`; a failed one gets "Not delivered" under it. See
the Journal for how it's actually confirmed — mostly by the message's
own echo, the mixer's `sentStatus` only when that never arrives.

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
- A `PENDING` send that never gets an echo and whose tag the mixer never
  mentions again (lost on the way there, or its answer lost on the way
  back) stays `PENDING` forever — no timeout, no retry button, no resend.
  PeelCore's own `isRetryable`/Retry exists specifically for this; not
  ported. A send that throws immediately (not connected) still just
  shows a one-line error under the list, and the text is already
  cleared from the composer by the time it lands (optimistic-clear).
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

**Update (2026-10-29), from a full iOS-vs-Android audit:** three real
fixes to how a run of messages reads, found by comparing against
PeelCore's own `ChatRow.rows`.

A run used to break only on sender identity — no day boundary, no gap
check, so a same-sender conversation spanning days rendered as one
undifferentiated block with a single timestamp at the very top of it.
`ConversationScreen.kt`'s run-break rule now also breaks on a day
change or a 5-minute gap, PeelCore's own rule, and a day change draws
an actual divider ("Today"/"Yesterday"/a weekday and date — PeelCore's
`Formatting.dayHeading`) — a component that didn't exist here before.
A "New messages" divider is new too, for a chat opened with something
unread: captured once as the screen opens, from the read marker as it
stood right before this chat's own `markRead` moves it (PeelCore's
`AppModel.newMessages`); see
[phases/06-read-state.md](06-read-state.md) for the boundary rule
itself.

Separately, a message's time was hardcoded to 24-hour
(`SimpleDateFormat("HH:mm", ...)`), ignoring the device's own clock
setting entirely. Now uses `android.text.format.DateFormat.getTimeFormat`,
which honors it, matching PeelCore's own `Date.FormatStyle`.

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

**Delivery status mostly isn't a `sentStatus` feature at all.** Started
reading `Engine+Messages.swift`/`liveStatus` expecting the mixer's
`sentStatus` to be the one source of truth for "did this send land" —
its own doc comment calls it "the one status the mixer publishes for a
send," which reads like the whole mechanism. Realised while tracing it
that `sentStatus` is specifically about *payment* routing (a boost, an
invoice, a tribe's priced message) — a free text send carries no sats
for the mixer to route at all, so nothing there may ever call back
about it. What actually confirms a free send is simpler and already
present: the message's own echo, coming back through the ordinary
live/restore path under the same `uuid` `insertMessages` already
dedupes on. Re-pointed `insertMessages` at that: a `uuid` conflict is
now also "this device's own pending send just got confirmed," not only
"skip it, already have it." `sentStatus`/`markSendStatus` stayed in
(reading `RunReturn.sentStatus` costs nothing, and a priced send -
paid tribes, once that exists - will want it) as the second, narrower
path for whatever `send` or send*ing* never gets its own echo back for.

## Done

- `engine/Engine.kt`: `sendMessage(chat, text, isTribe)` — calls the
  core's `send` through `run` (so it still commits+publishes under the
  one lock any other call does), stores the outgoing row by hand as
  `PENDING`; `storeMessages` now also reads `RunReturn.sentStatus` and
  marks a `tag` `FAILED`/`DELIVERED` through `markSendStatus`.
- `storage/Storage.kt`: `messages(chat)` — one chat's rows, oldest first;
  `MessageRow` gained `id` (the table's own row id, for a stable list
  key) and `status`; `message.status` (v4 migration); `insertMessages`
  now moves a `uuid` conflict's existing row from `PENDING` to
  `DELIVERED` rather than only skipping it; `markSendStatus(tag,
  delivered)`, the mixer-confirmation path.
- `ui/dashboard/ConversationScreen.kt`: the chat screen — header with
  back and the name (AGENT tag for the agent), the grouped message list
  (a `PENDING` row dimmed, a `FAILED` one marked "Not delivered"), the
  text composer and its send button, a one-line error on a failed send.
- `ui/dashboard/ChatsScreen.kt`/`MainTabs.kt`: a tapped row opens the
  conversation in place of the whole tab area (bar included); Back
  returns to whichever tab was open.
- Tests: `StorageTest` cases for `messages(chat)`, a `uuid` conflict
  moving `PENDING` to `DELIVERED`, and `markSendStatus` (including that
  it never moves a row already settled). 44 tests passing.
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
- A field's own doc comment calling it "the" mechanism for something
  ("the one status the mixer publishes for a send") describes what it
  covers from the perspective of the features that already use it (paid
  sends), not necessarily every case that sounds like it should be
  covered — worth checking what actually triggers a signal, not just
  reading its best description, before building around it as the only
  source of truth.
- A `uuid`-keyed `INSERT OR IGNORE` already *is* a dedup check for "this
  exact message again" — turning its conflict case into a signal
  ("this device's own pending send just got confirmed") rather than a
  pure no-op got delivery status mostly for free, no new round trip or
  polling needed.
