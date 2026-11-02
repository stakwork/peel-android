# Phase 14 — Message menu: Copy, Copy link, Delete

## Status (2026-11-02)

**Built:** the three actions the long-press menu was still missing
against PeelCore's own `MessageMenu.swift` — Copy (the row's own text
or caption), Copy link (a call's own link, or the first `http(s)://`
link in plain text), and Delete (for everyone, your own content
messages only). The menu now reads Reply, Boost, Copy, Copy link,
Delete, in that order, matching the official apps' own ordering
(`MenuItems`'s own body); each shows only when it applies, same as
before for Reply/Boost.

**Delete is a real protocol feature, not a local hide.** A type-17
message names the target by `replyUuid`, the same field a reply or a
boost already uses to name theirs — `Engine.deleteMessage`, the same
`run`/`coreSend` shape `sendBoost`/`startCall` already established. On
receipt (`Storage.applyDeletes`, called from inside `insertMessages`'s
own transaction, after every row's gone in and every uuid conflict's
been resolved to `DELIVERED`), the row it names gets `deleted = 1` and
its `body` cleared — ported from PeelCore's own `applyDelete`,
including its two least obvious rules:

- **A delete still `fromMe` and `PENDING` is not applied yet.**
  PeelCore's own comment on this is exact: "a delete that fails to
  send leaves the message as everyone else still sees it." The uuid
  conflict that moves `PENDING` to `DELIVERED` (the delete's own echo
  arriving) is what triggers applying it, in the same `insertMessages`
  call that resolved it — not a second pass, not a poll.
- **Who may delete what is re-checked on receipt, not just offered by
  the menu.** A DM: only the sender of the *target* message (deleting
  your own, or your contact deleting theirs) — never the other
  side deleting what isn't theirs. A tribe: the deleter's own claimed
  alias must match the target's (someone deleting their own earlier
  message), minus PeelCore's own "or the tribe's owner, deleting
  anyone's" bypass — see "Open".

**Open:**

- **No tribe-owner delete.** PeelCore's own `isDeletable`/`applyDelete`
  both let a tribe's owner delete *anyone's* message in it; this port
  tracks no tribe membership or roles at all (`docs/PLAN.md`'s own
  storage section: "No `tribe` (the rich kind — escrow terms,
  membership, roles)... yet"), so there is no "owner" bit to check,
  on either the sending side (`canDelete`, UI) or the receiving side
  (`Storage.applyDeletes`). A real gap, not a trim nobody would reach:
  a tribe owner moderating their own tribe from this port simply can't
  delete someone else's message. Needs the membership/role table
  itself before it can be closed.
- **Copy link is a bare-URL scan, not PeelCore's own markup parser.**
  `firstLink` is a plain regex (`https?://[^\s]+`, trimmed of trailing
  punctuation a sentence might end a URL with); PeelCore's own
  `MessageMarkup.firstLink` understands actual markdown link syntax
  (`[text](url)`), because its own renderer draws those links styled.
  This port draws every message as plain `Text` — nothing here ever
  renders a markdown link specially in the first place, so there is no
  `[text](url)` form its own composer or any received message's
  rendering would need `firstLink` to unwrap; a bare URL is the only
  shape "a link in the text" can take here. Worth widening only
  alongside actual markdown rendering, not before it.
- **No Retry/Discard.** PeelCore's own menu leads with these for an
  undelivered send (`isRetryable`) before Reply ever shows. This port
  still has no resend mechanism at all — Phase 5's own "Open" named
  this well before this round, a separate feature from "what does the
  menu offer," not folded in here.
- **Delete doesn't purge a deleted attachment's cached file from
  disk.** PeelCore's own `applyDelete` appends the body to a `purged`
  list for the caller to actually remove from the attachment cache
  directory; this port only ever clears the database column. The
  cached plaintext (`Storage.attachmentCacheDir()`) stays on disk
  after a delete — a real gap (the cache is meant to hold what's still
  in the chat, not what used to be), not something worth guessing an
  implementation for without re-reading how the rest of that cache's
  own lifecycle is managed elsewhere in this port first.
- **No confirmation dialog on Delete**, matching PeelCore's own menu
  exactly (`role: .destructive` is styling, not a confirm step) — not
  a gap, a deliberate match, called out so it doesn't get "fixed" into
  a divergence later by someone assuming red text implies a confirm
  sheet should exist.

## Decided

- `canDelete` takes a plain `replyCount: Int`, not the row's own
  `ThreadSummaryData?` — the thread-sheet's own root row is shown with
  `threadSummary = null` (that's the "N replies" chip belonging to the
  *collapsed* row in the main list, not something to repeat on the
  screen already showing every one of those replies), so reusing the
  same field for two different jobs (what the chip displays, what
  Delete gates on) would have made the root's own Delete always see
  zero and never correctly refuse deleting a root with two or more
  replies, right on the one screen where that actually matters.
- Authorization is re-checked where the message is actually stored
  (`Storage.applyDeletes`), not only where the menu decides whether to
  offer the button (`canDelete`, `ConversationScreen.kt`) — the same
  split PeelCore itself has (`Message.isDeletable` client-side,
  `applyDelete`'s own `allowed` check again on write). A locally-built
  `MessageRow` could in principle reach `insertMessages` from
  somewhere other than the menu; re-checking at the one place every
  message actually gets written is what makes that safe regardless.
- `message.deleted`/the clipboard copy and link-detection helpers stay
  in `ConversationScreen.kt`, not a new file — all three are small,
  single-purpose, and the two copy actions have no engine/storage
  involvement at all (pure clipboard operations on what's already
  rendered); Delete's own send/receive halves are the only parts large
  enough to want their own place, and those already have one
  (`Engine.deleteMessage`, `Storage.applyDeletes`).

## Journal

**Read PeelCore's `applyDelete` before writing the receiving side at
all.** The send side (`Engine.deleteMessage`) looked, correctly, like
a fourth instance of the same "a message names another by `replyUuid`"
shape boost/call/reply already established — cheap, mechanical. The
*receiving* side turned out to carry real logic worth reading in full
rather than guessing at from the type name alone: the "still pending,
don't apply yet" rule (easy to miss — a delete sent but not yet
confirmed must not hide the message locally, since nobody else has
agreed to hide theirs), and the authorization re-check (a DM's "only
the actual sender of the target, matched correctly in both directions"
rule, a tribe's "alias match, no owner bypass available here" rule).
Neither is discoverable from the column name `deleted` alone; both
came from reading `Storage+Writes.swift`'s own `applyDelete` function
directly before writing a line of Kotlin against it.

**The thread-root's own Delete, inside the thread it starts, needed
its own reply count — not the one already being carried around for
display.** First pass reused `threadSummary` for both "what the chip
shows" and "what Delete gates on," which worked everywhere `canDelete`
was called *except* the one place it actually needed to disagree with
`threadSummary`'s own display value (null, inside the thread sheet,
on purpose) — exactly the spot where getting the reply count wrong
would have silently let a two-or-more-reply thread's root be deleted
from inside its own thread sheet, the one screen that makes the bug
hardest to notice (you're already looking at the replies that would
survive it). Caught by asking "what does this parameter actually mean
at *this* call site" rather than assuming a working default carries
over.

## Done

- `storage/Storage.kt`: `MessageRow.deleted`; the `message.deleted`
  column (`v8`, no backfill needed — nothing before this version could
  have been deleted by a mechanism that didn't exist); `applyDeletes`,
  called from `insertMessages` after its own per-row insert/conflict
  loop; `aliasOf`, a one-line copy of `ConversationScreen.kt`'s own
  `senderAlias` extraction (kept local rather than a cross-layer
  import — storage has no reason to depend on a UI file, the same
  reasoning `attachmentMessageType`/`deleteMessageType`'s own plain-
  literal copies of `engine/Attachment.kt`'s constants already follow,
  for the same "wrong direction" reason).
- `engine/Attachment.kt`: `DELETE_MESSAGE_TYPE = 17`.
- `engine/Engine.kt`: `deleteMessage(chat, targetUuid, isTribe)` — a
  type 17 send through the same `run`/`coreSend` path every other send
  already uses, priced like an ordinary tribe message
  (`tribeMessageAmountMsat`, not the no-floor boost-style add-on).
- `ui/dashboard/ConversationScreen.kt`: `canDelete`; `firstLink`
  (a plain `https?://` scan); Copy/Copy link/Delete added to the
  long-press menu (Delete in `Theme.colors.destructive`); the
  long-press itself no longer opens the menu for an already-deleted
  row (nothing in it would apply); row rendering shows "This message
  has been deleted" (italic, muted) in place of text/attachment/call
  content, skipping `AttachmentView`/`CallBlock` entirely for a
  deleted row (its `body` is null by the time this ever renders);
  `QuoteBox` shows "Message deleted" for a reply whose parent was
  deleted since, instead of an empty line; `isThreadContent`/
  `canBoost` both gained a `!row.deleted` check, so a deleted reply
  stops counting toward its thread's total and a deleted message can
  no longer be boosted; `deleteMessage(target)` added alongside the
  existing `sendBoost(target)` in both the main screen and
  `ThreadScreen`; `MessageRowView` gained `onDelete`/`deleteReplyCount`
  (the latter overridden only at `ThreadScreen`'s own root call site —
  see "Decided").
- Tests: `StorageDeleteTest` (5 cases — a DM delete from the actual
  sender applies, one that isn't refused; this device's own delete
  waits for confirmation before applying; a tribe delete only applies
  when the deleter's claimed alias matches the target's; a delete
  naming a non-content message, or a target already deleted, is a
  no-op). 129 tests passing. `Engine.deleteMessage` itself, and
  `ConversationScreen.kt`'s own `canDelete`/`firstLink` (pure
  functions, but inside a screen already documented, Phase 5 onward,
  as not unit-tested) are not — the same two existing gaps (no host
  build of `sphinxrs` for `Engine`; this screen's own established
  convention) every other send function and pure helper in this file
  already has, not new ones.

## Learned

- A column name (`deleted`) describes *what* changed, never *when* it
  is safe to change it or *who* is allowed to cause the change — both
  of those lived only in the reference implementation's own write
  path, not in anything guessable from the schema or the feature's own
  name. Worth reading the actual mutation function, not just the
  model it mutates, before porting a feature that writes shared state
  other devices and other people's clients will also come to agree on.
- A parameter that happens to produce the right value at every
  existing call site isn't the same as a parameter whose *meaning*
  is right for a new one — `threadSummary`'s display value and
  Delete's own reply-count gate were two different questions that had
  simply never been asked to disagree before the thread-root case
  existed. Worth asking what a shared value actually *means* at a new
  call site, not just whether reusing it happens to type-check.
