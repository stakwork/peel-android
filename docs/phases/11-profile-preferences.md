# Phase 11 — Profile and preferences sync

## Status (2026-10-29)

**Built:** the "You" tab stopped being a dev placeholder (pubkey,
connection state, "Forget this account") and gained a real profile
header (avatar, an editable name) and an "Account" card with a
working "Boost amount" setting — the first real cross-device
preference this port has. Changing it from Profile syncs to this
account's other devices (iOS, Mac, this port, the official Android
app) through the same encrypted preferences file on the memes server
those apps already read and write, not a new, Android-only mechanism.

**The sync mechanism:** read `Engine+Preferences.swift`,
`Preferences.swift` and `PreferencesFile.swift` in full before writing
a line — the real file (`{"items": [{"key", "identifier", "date",
"value"}], ...other fields}`) is RNCryptor-v3 encrypted under the
account's own node secret key (`Sphinxrs.nodeKeys("bitcoin",
seed).secret` — already had `MediaCrypto.encrypt`/`decrypt` built and
independently verified for attachments, Phase 8; this is the same
cipher, a different password), fetched/uploaded over `GET`/`POST
/preferences` on the memes server (the same host, the same bearer-token
sign-in `Engine.kt` already had for attachments). PeelCore's own
version syncs ten-plus keys through a general per-key merge-policy
registry (colours, Face ID, the agent's credential, feed
subscriptions, a per-device confirmation threshold…); this port's own
`PreferencesFile` only ever reads or writes **one** item in the whole
file — `"tip_amount"`, the official key for the boost amount, whole
sats, never msat — and passes every other item and every other
top-level field through completely untouched. A general multi-key
registry is real future work (see "Open"), not something this shape
should grow into by accretion.

**Update (2026-10-29), from a full iOS-vs-Android audit:** one real
bug, found by tracing `setBoostAmount` against PeelCore's own
`setPreference`. PeelCore guarantees every write's date is strictly
after whatever was stored before — `max(now, old.updatedAt + 1)`,
never `now` alone — specifically so two edits land at two different
dates even if they happen in the same wall-clock second.
`setBoostAmount` used `now` alone. Concretely: set the boost to 10,
immediately change your mind and set it to 20 (same second, same
device, no other device involved at all) — the first `setBoostAmount`
uploads `tip_amount=10@T`; the second stores `20@T`, then
`syncBoostAmount` fetches the file it just wrote, compares `T > T`
(false), and takes the branch that *applies the file over local* —
silently reverting the boost back to 10 with no error and no hint
anything happened. Fixed by giving `setBoostAmount` the same
monotonicity guarantee: `date = max(now, oldDate + 1)`, read from
`Setting.BOOST_AMOUNT_UPDATED_AT` before writing the new one. Not
unit-tested — same `Engine` native-library gap the rest of this
file's own "Done" section already names.

**Open:**

- Only `tip_amount` syncs. PeelCore's own registry also covers Face
  ID, a payment confirmation threshold, a chat/person's colour, feed
  subscriptions and the agent's credential — none of those exist on
  this port yet to sync in the first place (no biometrics, no balance
  to confirm a payment against, no colour picker, no feeds, no agent
  config UI), so there was nothing else to wire up this round. Adding
  a second synced key means generalising `PreferencesFile` into an
  actual per-key registry (PeelCore's own shape) rather than extending
  today's one-key special case — worth doing once there's a second
  real preference to sync, not before.
- No debounce, no background resync loop. PeelCore syncs 2 seconds
  after a change and again every time the app comes to the front (at
  most once a minute); this port syncs once right after `setBoostAmount`
  (immediately, not debounced — only one preference can possibly
  change, so there's no run of edits to coalesce) and once after
  `start()`/`resume()` connects, nothing periodic beyond that. A
  change made on another device while this one stays connected and in
  the foreground for a long time isn't picked up until the next
  reconnect.
- A sync failure is silent (`runCatching`) — no "not synced" indicator
  anywhere, no retry beyond the next `start()`/`resume()`/
  `setBoostAmount` call. Matches this port's own established pattern
  for background work (restore, tribe-name refresh) rather than a
  gap specific to this feature.
- No photo editing in Profile — the header shows whatever
  `Setting.PHOTO_URL` already holds (never actually set anywhere in
  this port; always blank today, so every avatar is initials-only)
  but offers no picker to change it. Name editing was the one gap
  worth closing this round (nothing anywhere could set
  `Setting.ALIAS` before this phase — every outgoing message carried
  an empty sender name); a photo needs the same upload pipeline
  attachments already have, reachable, but not pulled in this round.
- Twelve words (view), Change PIN, Face ID, "Ask for the PIN",
  Confirm payments, Server detail, the Agent/Stadeum rows and Debug —
  all real rows on the actual app's own You screen (the screenshots
  this phase was built from), none built here. Each needs its own
  piece: Change PIN already has `SecretStore.changePin` to call but no
  screen; Face ID needs the biometric-unlock gap `docs/phases/01-foundation.md`
  already named; Confirm payments needs a balance this port doesn't
  track; the rest need their own features entirely (calls, workspaces).
- No validation beyond ">0" on the boost amount — PeelCore's own
  `setBoostAmount` also requires a whole number of sats (`msat % 1000
  == 0`); this port's own dialog only ever lets whole sats be typed in
  the first place (the field strips non-digits), so the stricter check
  never has anything to catch.

## Decided

- One key, not a registry — see the Status section's own reasoning.
  Worth restating as a decision, not just an observation: a general
  `Preference<Value>` registry (PeelCore's own shape, a `key`/
  `scope`/`read`/`write`/`takes`/`uploads` struct per setting) is the
  *right* shape once there are several keys to sync, but building it
  for one is the kind of abstraction this project's own conventions
  say not to add ahead of the second real need for it.
- `Engine.boostAmountMsat()` reads straight from local storage, never
  async, never a direct read of the file — the file is only ever
  touched by `syncBoostAmount()` in the background; a boost send never
  waits on a network round trip just to know its own amount.
- `mayStartPreferencesFile` ported exactly, not simplified away: a
  "not found" from the memes server could mean a genuinely new
  account, or it could mean a transient server problem on a restored
  account that has a real file elsewhere with real data other devices
  depend on. Guarded the same way PeelCore guards it (only a
  never-restored account, on a server never before confirmed to have
  had a file, may ever ask this port to *start* one) specifically
  because getting this wrong doesn't just lose local state — a wrong
  upload could overwrite preferences the account's other devices
  already set.
- The boost menu says just "Boost", not "Boost N sats" — the user's
  own explicit call, unlike PeelCore's own menu
  (`quickBoostLabel`), which does name the amount.
- The boost default changed from 100 sats (PeelCore's own
  `defaultBoostAmountMsat`) to 10 — also the user's own explicit
  choice for this port, not a porting mistake; see
  `BOOST_AMOUNT_MSAT`'s own doc comment in `Attachment.kt`.

## Journal

**Read the real sync mechanism before assuming it needed to be
smaller than it is.** The instinct going in was that a "preferences
sync" feature would need its own large subsystem to match — and
PeelCore's own version genuinely is one (a merge-policy registry, a
debounced scheduler, secret handling via the Keychain, agent
credential adoption). But the actual *mechanism* under all of that —
fetch, decrypt, read one item, compare dates, write it back, encrypt,
upload — turned out to reuse two things this port already had fully
built and independently verified: `MediaCrypto` (Phase 8's own
RNCryptor-v3 port) and the memes server's own bearer-token sign-in.
The size PeelCore's own version has comes from the *number of keys*
it syncs, not from the sync mechanism itself being large. Scoping to
one key kept the real, novel part of this (the fetch/merge/upload
flow, `mayStartPreferencesFile`'s safety guard) while skipping the
part that was genuinely about breadth, not depth.

**`Setting.ALIAS` had never once been written.** Grepped for it before
assuming Profile just needed a "boost amount" row bolted onto an
existing name-editing screen — there wasn't one. Every message this
port has ever sent carried an empty sender name; `ALIAS` existed only
as a read, with a `?: ""` fallback, at every call site that needed it.
Worth fixing as part of "Profile" rather than filing as a separate,
easy-to-forget gap, since the screen this phase was already rebuilding
is exactly where it belongs.

**Checked whether a "not found" from the server is actually safe to
treat as "nothing to sync yet."** It isn't, in general — a restored
account very likely has a real preferences file elsewhere, and a
transient failure that looks like "not found" must never be
read as "this account has no file, I'll make one." Porting
`mayStartPreferencesFile` exactly, rather than skipping the check for
simplicity, is what keeps a bug here from being able to silently
discard another device's real data — the one piece of this feature
where "scope it down" was not the right call, because the blast
radius of getting it wrong reaches past this device.

## Done

- `engine/Attachment.kt`: `BOOST_AMOUNT_MSAT` changed from 100 sats to
  10 (the user's own default), re-scoped from "the fixed amount" to
  "the fallback before anything's been chosen or synced".
- `storage/Setting.kt`: `BOOST_AMOUNT_MSAT`, `BOOST_AMOUNT_UPDATED_AT`
  (PeelCore's own preferences-file "date" for this key, in whole
  seconds), `PREFERENCES_FILE_SEEN` (PeelCore's own
  `device.preferences_file_seen`).
- `engine/PreferencesFile.kt`: a from-scratch, one-key port of
  PeelCore's `PreferencesFile` — `parse`/`text` (round-trips every
  item and top-level field it doesn't touch byte for byte),
  `tipAmount()`/`setTipAmount(sats, date)`.
- `engine/MemesServer.kt`: `preferences(token): String?` (`GET
  /preferences`, including the exact "Preferences not found" JSON
  shape that means no file yet) and `uploadPreferences(sealedBase64,
  pubkeyField, token)` (`POST /preferences`, the same multipart shape
  `upload` already used, a `"pubkey"` field instead of `"name"`).
- `engine/Engine.kt`: `boostAmountMsat()` (local read, with
  `BOOST_AMOUNT_MSAT`'s own fallback); `setBoostAmount(sats)` (writes
  locally, dates it, triggers a best-effort sync);
  `syncBoostAmount()` (fetch, decrypt, compare dates, apply or
  upload); `mayStartPreferencesFile`; `uploadPreferencesFile`;
  `markPreferencesFileSeen`; a small `hexToBytes` helper for the
  upload's own pubkey field. `sendBoost` now reads `boostAmountMsat()`
  instead of a constant. Hooked into `start()`/`resume()`, best-effort
  (`runCatching`).
- `ui/dashboard/ConversationScreen.kt`: the boost menu item is now
  just "Boost", not "Boost N sats".
- `ui/you/YouScreen.kt`: rebuilt — an avatar (initials, no photo yet),
  an editable name (writes `Setting.ALIAS`, the first thing in this
  port that ever has), pubkey and connection state kept from before,
  a "Boost amount" row (a dialog, whole sats only, calls
  `Engine.setBoostAmount`), "Forget this account" kept as it was.
- Tests: `PreferencesFileTest` — a fresh file has no `tip_amount`;
  reads whole sats (string or raw JSON number) correctly, including a
  zero or wrong-key/-identifier item reading as none; the last of a
  duplicated item wins; `setTipAmount` replaces every copy with
  exactly one; every other item and top-level field round-trips
  untouched. 79 tests passing. `Engine.syncBoostAmount`/
  `setBoostAmount`/`sendBoost` are not unit-tested — they need the
  native `sphinxrs` library, the same gap every other `Engine` send
  function has.

## Learned

- A feature's *apparent* size (what the reference app's own version
  looks like) and its *actual* required size (what the one thing being
  built genuinely needs) can differ a lot — PeelCore's preferences
  sync is a wide, multi-key subsystem; the one key this phase needed
  reused two already-built, already-trusted pieces almost entirely.
  Reading the real mechanism before estimating the work is what
  surfaced that, not a size guess from the feature's name.
- Grepping for whether a setting this port already reads is ever
  *written* anywhere is worth doing before assuming "add a UI for it"
  is the whole task — `Setting.ALIAS` needed a writer that didn't
  exist at all, not just a new screen pointed at one that did.
- A safety guard that exists specifically to prevent data loss on
  *other* devices (`mayStartPreferencesFile`) is exactly the kind of
  thing worth porting at full fidelity even while everything else
  about a feature gets deliberately trimmed — the cost of skipping it
  isn't paid locally, which is what makes it easy to underweight.
