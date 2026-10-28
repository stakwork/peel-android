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

**Open:**

- No Unread section, no mute levels, no read-state badges at all —
  `ChatReadState`/`chatState` is a whole table and a server-sync
  subsystem (`fetchReadsAndMutes`) this phase doesn't touch. Every chat
  sorts by its newest message alone.
- No search — the bottom bar's Search tab is a placeholder; PeelCore's
  own search is `ChatSections` again with a query, so it's a small
  addition once there's a reason to prioritise it over everything else
  still open.
- A tribe's real name and photo are not available at all — they come from
  the tribes server itself (`TribeInfo.fetch`, its own HTTP call to the
  `tribe_host` `ServerConfig` already reads), not from any message. A
  tribe shows by its pubkey (`ConversationRow.name`'s fallback) until that
  call exists.
- Join a tribe / Create a tribe / Add contact / Invite someone are drawn,
  same place as the real app, and do nothing — each opens a real flow
  that isn't built.
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
  listener; `contactHint` checks `host` before `pubkey == chat`;
  `backfillContactsIfNeeded`, gated by `Setting.CONTACTS_BACKFILLED`;
  `agentPubkey`.
- `storage/Storage.kt`: `contact.isTribe` (v3 migration);
  `upsertContact` enforces "a tribe's name/photo never come from a
  message" itself; `conversations()` (the newest-message-per-chat query);
  `messageSenders()` (the backfill's read).
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
- Tests: `PersonHueTest`, `ChatSectionsTest`, and new `StorageTest` cases
  for `conversations()` and the tribe-name invariant. 37 tests, all
  passing.

## Learned

- A signal that distinguishes two kinds of message (here, `host` marking
  a tribe) has to be checked *before* a signal that happens to be true of
  both, not after — `sender.pubkey == chat` turned out to hold for both a
  direct contact's own messages and every tribe message alike, so
  checking it first made every tribe look like a direct contact whose
  name kept changing.
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
