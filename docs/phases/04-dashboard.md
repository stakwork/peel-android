# Phase 4 — Dashboard

## Status (2026-10-28)

**Built:** live messages now store the same way restored ones do (one
`Engine.storeMessages`, called from both `Engine.page` and the persistent
inbound listener — Phase 3 only wired the latter to commit state, not to
save what it carried). Tribes are real: `Engine.contactHint` now detects
one the way PeelCore's `discoveredTribe` does — a tribes server, not a
person, sets `host` on what it forwards — and `Storage.contact` gained
`isTribe`. A one-time `Engine.backfillContactsIfNeeded` re-derives
`contact` rows for history a restore stored before this phase, so an
account already restored under Phase 3 doesn't need restoring again.

The "Chats" dashboard is the real structure from `ChatsTab.swift`/
`ChatList`/`ChatSections.swift`: the title and connection bolt, Tribes
then Direct messages, each collapsible, each trimmed to 5 with "Show all
N", each ending in its own actions (present, not wired — see Open). The
agent's chat is pinned first in Direct messages with its own row (Peel's
logo, an "AGENT" tag), never among the regular ones, the same as the real
app. Avatars are the real `PersonHue` algorithm (FNV-1a over the key, one
of eight fixed hues), circles for people and rounded squares for tribes
and the agent, with photo loading (Coil, new this phase). The bottom tab
bar is Chats/Payments/You/Search in the real shape (three tabs in a pill,
Search its own circle); Chats and You (the account screen Phase 2/3 built,
moved here) are real, Payments and Search are honest placeholders.

**Also built, in a second pass on the same real account:** a tribe's real
name and photo now come from its own registry (`Engine.TribeInfo.fetch`,
`GET https://<tribeHost>/tribes/<pubkey>`), not from any message — Android
had none of this before; a tribe showed by its pubkey always. Found and
fixed a second real bug in `contactHint`'s callers: both skipped every
`fromMe` message outright, but PeelCore's own `discoveredTribe` explicitly
considers `fromMe` too (`trustSender || message.fromMe`) — and on this
account several tribes' *only* stored message was one of this account's
own, echoed back by the tribe carrying `host`, so skipping every `fromMe`
row missed those tribes entirely, not just mis-sorted them. See Journal.

**Open:**

- Unread, mute levels and read-state badges are built — see
  [phases/06-read-state.md](06-read-state.md).
- Search is built — see [phases/10-search.md](10-search.md).
- A tribe's name/photo are fetched once, the first time it has none, with
  no periodic recheck — PeelCore's own `refreshTribes` keeps a joined
  tribe's edits current in the background; a tribe renamed or
  re-pictured after this account first saw it stays stale here until the
  app restarts. No retry or backoff either: a fetch that fails (offline,
  the registry briefly down) is tried again next launch, not sooner.
  The same call now also reads the tribe's own price and escrow
  (`docs/phases/05-conversation.md`'s own "Update"), so a price the
  tribe changes later goes just as stale as a renamed/re-pictured one
  would.
- The raw count of tribe-signalling chats in this account's own history
  (17, by a direct count of `host`-bearing messages) is still higher than
  what iOS reports (15) even after the `fromMe` fix. The likely remaining
  gap: PeelCore's `discoveredTribe` also requires `trustSender` for a
  non-`fromMe` message outside a live child-index match — which itself
  requires `state["c/<claimed>"]` to already exist, i.e. the *core's own*
  record of having decoded something from that signer before, not just
  the claim in that one message's own JSON. `Engine.mapMessage`'s own
  doc comment already names this simplification deliberately (a
  restore's pages are an authenticated fetch, unlike a live raw publish
  anyone could spoof), so this is a known, accepted gap, not something
  this round changed — flagged here in case the count still doesn't
  match once this fix is retested.
- Join a tribe / Create a tribe / Add contact / Invite someone are drawn,
  same place as the real app, and do nothing — each opens a real flow
  that isn't built.

**Update (2026-10-29), from a full iOS-vs-Android audit:** two real
fixes.

A dashboard row showed only its unread-count badge — a muted or
mentions-only chat with nothing unread looked identical to a normal
one, even though `chatState.muteLevel` was already stored and read
(Phase 6). `ChatRow` in `ChatsScreen.kt` now shows PeelCore's own
trailing glyph in that case (bell-slash for muted, "@" for mentions
only — `PhoneComponents.swift`'s `ChatListRow`), which needed
`ConversationRow.muted: Boolean` widened to `muteLevel: Int` so the two
mute levels above "all" stop collapsing into one. Still not ported:
PeelCore's "New" badge for a contact who added you with nothing
exchanged yet (`ChatReadState.isNew`) — this port tracks no such flag
at all, a bigger gap than the glyph alone.

Separately, `MessageRowView`'s own avatar/name colour in
`ConversationScreen.kt` keyed every row's hue off `sender.pubkey` —
wrong for a tribe member specifically, since a tribe's forwarded
messages often carry the tribe's own shared pubkey there, not the
member's (the same unreliability this phase's own `contactHint` had to
work around for tribe *detection* — see above). PeelCore's
`PersonHue.colourKey` ignores pubkey for a tribe member on purpose,
keying only on the trimmed alias. `hueKey` now does the same: alias for
a tribe member, pubkey/chat otherwise.
- No chat screen. Tapping a row does nothing yet; there is nowhere to
  send a message or read one beyond the list.
- No reactive query layer (no Room, no Flow-backed DB): the dashboard
  re-reads `Storage.conversations()` on a 3-second poll plus whenever
  `restoreProgress` changes, not the instant a row actually changes.
- Payments and You's own richer content (balance, settings, profile) are
  both still just what Phase 2/3 already had — a stub and the dev account
  screen, respectively.

**Decided:**

- `isTribe` stays a single column on the existing `contact` table, not a
  separate `tribe` table (PeelCore's own `v6` migration) — nothing here
  needs price, escrow, membership or roles yet, and a real `Tribe` table
  is better sized once something actually reads those fields.
- `Storage.upsertContact` enforces "a tribe's name/photo never come from a
  message" itself, not just `Engine.contactHint`'s caller discipline — see
  the Journal for why a per-message check wasn't enough on its own.
- One backfill pass, gated by a setting, rather than requiring a fresh
  restore: the account this phase was built and tested against had
  already been restored once under Phase 3, and a second ~120-round-trip
  restore just to pick up local classification logic would have been a
  needless, very real wait for something that's actually a pure local
  re-derivation from data already on the device.

## Journal

**Live messages were committing state but never storing anything.**
Phase 3's persistent listener (`startListening`) ran every inbound
message through `handle` and committed its state, but never called the
storage half `Engine.page` already had — a live message landing after a
restore finished would update core state and simply vanish otherwise.
Pulled that into one `storeMessages`, called from both places.

**"Tribes aren't showing at all."** First guess was the restore itself.
Checked the device directly (`run-as` + a local SQLite copy) rather than
guess from the UI: `SELECT COUNT(*) FROM contact WHERE isTribe=1` came
back `0`, but the `message` table already had 31 rows whose `sender` JSON
carried a `host` field — PeelCore's own tribe signal, which this
Kotlin port hadn't detected until this phase. The history was already
there from the Phase 3 restore; the classification logic that would have
recognised it as a tribe simply didn't exist yet when that restore ran.
That is what `backfillContactsIfNeeded` is for: a local-only, one-time
re-scan of `message` through the current `contactHint`, no server
round trip needed to fix something that's purely about how this device
reads data it already has.

**"Rasul is showing with Tom Smith's photo."** Not cross-contamination
between two different contacts — checking the raw `sender` JSON for both
pubkeys showed each one correctly storing what its *own* messages said.
The real bug was one level up: `Engine.contactHint`'s first version
checked `sender.pubkey == chat` *before* checking for `host`, and it
turns out tribe messages satisfy that too — confirmed directly against
the account's own restored data, one chat (one pubkey) with messages
from "Tom_T", then "Paul", then others, all under the same `chat`,
because a tribes server forwards every member's message under its own
channel identity, not the member's. Reading `host` first and skipping
alias/photo extraction entirely for anything it marks a tribe fixed the
*new* case; a second problem remained — a real restore has some of a
tribe's own messages (delivery/control messages, by their shape) that
carry no `host` field at all, so a later one could still slip through as
an ordinary "pubkey matches chat" direct-contact update and attribute its
author's alias/photo to the tribe. No message-level check can guarantee
it catches every tribe message, so the actual fix is structural: made
`Storage.upsertContact` itself refuse to touch `alias`/`photoUrl` once a
chat is marked a tribe, whatever a caller asks it to set them to, with a
test exactly for that (a tribe row given a later "update" with a
person's name and photo, asserting both are still null afterward).

**"Still 9 tribes, not 15, and some have no name."** Re-read
`Engine+Tribes.swift` and `Engine.swift`'s whole `message(from:)`/`commit`
loop line by line before touching anything, since the first round's fix
had already looked right and still undercounted. Two separate things
were true at once. First, the easy one: a tribe's real name was never
going to appear — PeelCore reads it from `TribeInfo.fetch` (`GET
/tribes/<pubkey>` on the tribes host), nowhere near a message, and
nothing on Android called it yet. Second, the one that actually explained
the missing tribes: `discoveredTribe`'s own guard is
`trustSender || message.fromMe` — it explicitly looks at this account's
*own* messages too, not just what other members sent — but both of
Android's callers (`storeMessages`, `backfillContactsIfNeeded`) skipped
every `fromMe` row before `contactHint` ever saw it. Checked directly
against the account's own stored history rather than guess: three tribes
had exactly one message total, `fromMe`, carrying `host` (this account's
own join or post, echoed back by the tribe) — the *only* evidence those
chats were tribes at all, and it was being thrown away before
`contactHint` ran. A broader query found the same pattern mixed into
several more tribes that also had other members' messages: the `fromMe`
row was skipped same as before, it just happened not to be the only
evidence for those. Dropping the skip fixes both. Checked whether this
could misfire on a genuine 1:1: `contactHint`'s direct-contact branch
fires when `sender.pubkey == chat`, and that turns out to be *true* for
44% of this account's own `fromMe` messages generally (its `sender`
field seems to carry the chat partner's identity, not necessarily ours) —
but since `host` is still checked first, this is harmless for a real
contact: it just extracts the same alias/photo a received message from
them would, from our own side of the conversation.

**The agent's chat needed its own identity, derived, not looked up.**
PeelCore finds the agent's chat by deriving its pubkey
(`Sphinxrs.agentSeed(seed, 1)` → `pubkeyFromSeed`) and matching it
against the account's own conversations — the agent itself (the Strut
bridge, device requests, the rest of `Engine+AgentAccount.swift`) is a
large separate feature nowhere near in scope here, but the two FFI calls
needed just to name its chat were already bound, so pinning its row
(Peel's own launcher icon as its avatar, an "AGENT" tag, always first)
cost two lines in `Engine`, not a new subsystem.

**The avatar colour algorithm got its own test, independent of itself.**
Hand-rolled FNV-1a is exactly the kind of thing that compiles, runs, and
silently picks a *consistent* wrong hue instead of the one every other
device agrees on. Cross-checked three pubkeys against a from-scratch
Python FNV-1a-32 implementation, not this Kotlin one, and hard-coded
those as the test's expected values — the point is a mistake in the
Kotlin code can't also be in the oracle it's checked against.

## Done

- `engine/Engine.kt`: `storeMessages` shared by `page` and the inbound
  listener; `contactHint` checks `host` before `pubkey == chat`, called
  on `fromMe` rows too; `backfillContactsIfNeeded`, gated by
  `Setting.CONTACTS_BACKFILLED`/`BACKFILL_VERSION` (now 3, across the two
  fixes above); `agentPubkey`; `refreshTribeNames`, a tribe's name/photo
  from its own registry once it has none.
- `engine/TribeInfo.kt`: `GET /tribes/<pubkey>` on the account's tribes
  host, trimmed to `name`/`img`.
- `storage/Storage.kt`: `contact.isTribe` (v3 migration);
  `upsertContact` enforces "a tribe's name/photo never come from a
  message" itself; `updateTribeInfo`, the one call that's allowed to set
  them (no-op for a pubkey not already a known tribe); `conversations()`
  (the newest-message-per-chat query); `messageSenders()` (the backfill's
  read).
- `ui/components/Avatar.kt`: `PersonHue` (the real hash-to-colour
  algorithm, tested against an independent implementation), `Avatar`
  (photo, or initials on a hue, circle or rounded square),
  `ConversationAvatar`, `PeelLogo` (the agent's, reusing the launcher's
  own icon).
- `ui/dashboard/ChatSections.kt`: the real trim-to-5/"Show all N" logic,
  tested, minus Unread and search.
- `ui/dashboard/ChatsScreen.kt`: the Chats tab — title, connection bolt,
  Tribes/Direct messages sections, the agent's pinned row, empty states.
- `ui/dashboard/MainTabs.kt`: the bottom tab bar; `ui/you/YouScreen.kt`
  (renamed from the old `HomeScreen`, unchanged otherwise).
- `MainActivity.kt` now routes into `MainTabs`, not a bare account screen.
- Coil (`coil-compose`, `coil-network-okhttp`) and
  `material-icons-extended`, new dependencies this phase needed.
- Tests: `PersonHueTest`, `ChatSectionsTest`, and `StorageTest` cases for
  `conversations()`, the tribe-name invariant and `updateTribeInfo`. 40
  tests, all passing.

## Learned

- A signal that distinguishes two kinds of message (here, `host` marking
  a tribe) has to be checked *before* a signal that happens to be true of
  both, not after — `sender.pubkey == chat` turned out to hold for both a
  direct contact's own messages and every tribe message alike, so
  checking it first made every tribe look like a direct contact whose
  name kept changing.
- A blanket "skip our own messages" guard, reasonable where a bug is
  first noticed, can throw away the only evidence for something else
  entirely — here, several tribes' sole stored message was one of this
  account's own, echoed back carrying the one field that revealed it was
  a tribe at all. Checking the reference implementation's actual guard
  (`trustSender || message.fromMe`) rather than assuming "skip `fromMe`"
  was obviously safe would have caught this the first time.
- When Android's own count doesn't match the reference app's, counting
  raw signals directly in the stored data (here, chats with a
  `host`-bearing message) separates "my classification logic is wrong"
  from "the reference app is filtering on something my data doesn't even
  carry yet" — the second kind isn't fixable by changing the
  classification, only by porting the thing it's filtering on.
- Not every message belonging to a tribe necessarily carries the one
  field that marks it as a tribe's — delivery/control messages in a real
  restore didn't. A classification that depends on a field only being
  present on some of a category's instances needs the invariant enforced
  somewhere it can't be skipped (here, at the write, not only at the
  read), not just the obvious check added where the bug was first found.
- When a field has already been restored incorrectly (or not classified
  at all) because the logic to read it right didn't exist yet, a
  local-only backfill over what's already stored is worth reaching for
  before a full re-fetch — the data was already there, only the reading
  of it needed to change.
- A hash-based algorithm ported from another language is worth a test
  whose expected values come from a *third*, independent implementation,
  not just "it's deterministic" — deterministic and correct are different
  claims.

### Last-message preview on rows (port of peel #513)

Tribe and Contact rows show the last message on one line under the name
(12sp, muted; primary, semibold and an accent dot when unread; not on the
agent row). `engine/ConversationPreview.kt` is the pure formatter (tested in
`ConversationPreviewTest`): "You: " for your own, the sender's nickname in a
tribe, labels for Photo/Video/Voice message/File/GIF/Payment/Payment request/
Call/Message deleted. `Storage.conversations()` joins a separate newest-message
row for it (it also takes deleted messages and invoices, which the `lastContent`
row skips). Android has no `tertiaryText` colour, so the read colour is
`secondaryText` at 80% alpha. Not ported: Giphy's host allowlist.
