# Phase 7 — Reply

## Status (2026-10-28)

**Built:** long-pressing a message sets it as the reply target; the
composer shows "Replying to <name>" with the original's preview and its
own dismiss (✕), and sending includes the target's `uuid` as the body's
`replyUuid` field — the same field PeelCore's `sendText`/`BodyFields`
read and write. A message that carries one shows a small quoted box
above its own text (sender name in their hue, a one-line preview), or
"Original message not available" when the uuid it names isn't loaded.

**Open:**

- No thread support at all — PeelCore's reply and thread share the same
  linking machinery (`links(replyTo:inThread:in:)`, a reply joins its
  parent's thread or starts one); this port only ever sets `replyUuid`,
  never `threadUuid`, and there is no thread sheet to open one in.
- No validation of the reply target beyond "it has a `uuid`" — PeelCore's
  `linkable()` also requires the target to be a content type, not
  deleted, and (for one's own undelivered message) not still pending —
  this port tracks none of deletion, per-message type distinctions, or
  thread membership finely enough to check any of that, so any stored
  message can be replied to.
- "Original message not available" shows for any uuid not in the
  currently loaded chat (there's no pagination yet — Phase 5's own gap —
  so today that only means "never stored," but it will also start
  meaning "stored, just not loaded" once paging exists).
- Tapping the quote box does nothing — PeelCore's own scrolls to the
  original; there's no scroll-to-message here.
- No swipe gesture, no per-message menu — reply is the only thing a
  long-press does. PeelCore's swipe-to-reply/mute and context menu
  (delete, boost, pin, mark unread) are all still out, same as Phase 5.

## Journal

**Reply needed no new storage at all.** Expected to add a `replyUuid`
column to `message`, mirroring PeelCore's own dedicated column — but
nothing here ever queries "every reply to X" or needs an index on it,
only "does *this* row's own body have one," which `ConversationScreen`
already does for `content` the same way. Parsing it from `body` at
render time, the same place `content` already comes from, meant zero
migration for a feature that otherwise touches storage, engine and UI.

**The reply target is resolved from what's already loaded, not a fresh
query.** `ConversationScreen` already holds the whole chat's messages in
memory (Phase 5: no pagination yet); looking up a `replyUuid`'s parent
is just `messages.firstOrNull { it.uuid == replyUuid }`, no new storage
function needed. This is explicitly tied to that same gap — once paging
exists, a reply to something scrolled out of memory needs an actual
lookup, not a list scan, and "not available" will start being genuinely
ambiguous (see Open).

## Done

- `engine/Engine.kt`: `sendMessage` gained `replyTo: String? = null`,
  written into the body as `replyUuid` exactly like `content` is.
- `ui/dashboard/ConversationScreen.kt`: reply-target state; long-press
  (`combinedClickable`) sets it; the composer's "Replying to" bar;
  `QuoteBox` (a message's own quoted parent, or "not available");
  `replyUuid`/`previewContent`/`senderAlias`/`senderJson` helpers,
  factored out of what `MessageRowView` already parsed from `body`/
  `sender` inline.

No new tests: everything changed here is Compose UI or
`Engine.sendMessage`'s one new parameter, in a function already noted
(Phase 5) as untestable without a host build of `sphinxrs`.

## Learned

- Before adding a column "because the reference implementation has
  one," check what that column is actually *for* there — PeelCore's
  dedicated `replyUuid` column exists because its queries need it
  indexed (thread counts, mention rescans, `isLinkable` checks across
  the whole table); a port that only ever reads one row's own field at
  display time doesn't inherit that need just by porting the feature
  built on top of it.
