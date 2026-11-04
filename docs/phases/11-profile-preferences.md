# Phase 11 — Profile and preferences sync

## Status (2026-11-02): sync widened

Syncs now (both directions, `PreferencesMerge` + `Engine.syncPreferences`,
same rules as iOS): `tip_amount` and `peel.payment_confirmation_msat`
(Confirm payments; only taken from the file when stricter, like iOS).
The default boost is 100 sats again (iOS's `defaultBoostAmountMsat`);
the earlier "10 sats, the user's choice" note below is superseded.
`PreferencesFile` parsing now refuses a file the official apps could not
read, and dates are read like iOS (`Double` seconds, capped a day ahead).
Left: `pin_timeout`, `biometric_enabled`, `ai_agent_config`, `timezone`,
`peel.time_zone`, `chat_color` — Android has no matching feature. Not
unit-tested: the engine glue (needs native lib/server). See
`docs/money-parity.md` for the key table.

## Status (2026-10-29)

**Built:** the "You" tab stopped being a dev placeholder (pubkey,
connection state, "Forget this account") and gained a real profile
header (avatar, an editable name, a balance), an Agent row, and an
"Account" card with Twelve words, Change PIN, Boost amount and
Server. Boost amount is the first real cross-device preference this
port has: changing it from Profile syncs to this account's other
devices (iOS, Mac, this port, the official Android app) through the
same encrypted preferences file on the memes server those apps
already read and write, not a new, Android-only mechanism.

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

**Update (2026-10-29), from the real iOS screenshots:** four more
rows, closing four of the "all real rows on the actual app's own You
screen, none built here" items below — balance, the Agent row, the
Server row, Twelve words and Change PIN. Still not Stadeum, Face ID,
"Ask for the PIN" or Confirm payments; see "Open" for why each stayed
out this round.

**Balance.** `Setting.BALANCE_MSAT` existed since Phase 1 (ported
wholesale from PeelCore's own `Setting` enum) but nothing had ever
written to it. Every core call's `RunReturn` can carry a `newBalance`
— PeelCore's own `Engine.swift` commits it unconditionally, right
alongside the state and clock every result already updates, so that's
where it went here too: `Engine.commitLocked` now writes it into
`Setting.BALANCE_MSAT` whenever a result carries one, and a new
`Engine.balanceMsat()` reads it back for Profile to show under the
name. PeelCore's own `newBalance` handling also runs
`followUps.reconcile` — comparing the jump against any open invoice,
real Payments-feature bookkeeping this port has none of yet — which
is why that part stayed out; the number alone needed nothing from it
to be worth showing.

**The Agent and Server rows** needed no new data at all, only
surfacing what already existed: `engine.agentPubkey` (Phase 4, derived
for exactly this) and whether that pubkey is already a `contact` here
decide "Ready" vs. "Set up on your Mac" (this port still has no
agent-setup flow of its own — bridge, device request — so that's
genuinely accurate wording, not a placeholder); the broker's own
`ServerAddress.toString()` is what `connectAndRegister` now also
writes to `Setting.SERVER` (another Phase-1-scaffolded, never-written
setting, PeelCore's own `Engine.setServer`) right where it resolves
the config each connect, read back by a new `Engine.serverLabel()`.
`AgentTag()` moved out of `ChatsScreen.kt`'s own private copy into
`ui/components/Avatar.kt`, next to `PeelLogo` it's always drawn
beside, so Profile's Agent row could use the exact same tag instead of
a second copy of four lines of `Text` styling.

**Twelve words and Change PIN** both turned out to need less new
infrastructure than their iOS versions suggest: `SecretStore.changePin`
already existed (`docs/phases/01-foundation.md`), just uncalled from
anywhere, and `PinPrompt`/`PinPad` (`ui/components/PinPad.kt`,
built for the lock screen) were exactly the keypad both of these new
screens needed too. Change PIN ported PeelCore's own three-step shape
(old, new, confirm) faithfully, including that a *wrong* old PIN is
only ever discovered at the very end — `SecretStore.changePin`
dispatches `SecretStoreFailure.WrongPin` only once it actually tries
to open the sealed words with it, same as PeelCore's own
`model.changePIN` does, not something worth checking a step early
just because it reads simpler. Twelve words trimmed PeelCore's own
`WordsScreen` by one whole branch: that screen's "not checked yet,
check three words first" path turned out to be unreachable on this
port by construction — a fresh signup's own word-check is mandatory,
not skippable (`OnboardingScreen.kt`'s `checkWords`, no bypass), and a
restore's "typed all twelve back in" is PeelCore's own stronger proof
of the same thing (`needsRestore ? "1" : "0"`, see the found bug
below) — so every Android account reaches this screen already
confirmed, and the row/screen only needed the "show words" branch.

**Found and fixed while building that row:** a restored account's own
`Setting.WORDS_CONFIRMED` was never set at all.
`OnboardingScreen.kt`'s `Path.RESTORE` branch wrote
`Setting.NEEDS_RESTORE` but, unlike PeelCore's own `Account.swift`
(`storage.set(needsRestore ? "1" : "0", for: .wordsConfirmed)`), never
touched `WORDS_CONFIRMED` — so a restored account's Twelve-words row
would have shown "Not checked yet" forever, and (worse, had the
"check three words" branch been built instead of trimmed per the
paragraph above) might have re-prompted an already-proven account to
check words again. A real, previously invisible gap this round's own
"every account reaches this screen confirmed" assumption depended on
— caught by needing that assumption to actually be true, not by a
report or a test. Fixed: the `RESTORE` branch now writes both settings
in the one commit.

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
- Face ID, "Ask for the PIN", Confirm payments, the Stadeum row and
  Debug — still not built; balance, the Agent row, Server, Twelve
  words and Change PIN closed the rest of this item (see the
  "Update" above). Each of these needs its own piece: Face ID needs
  the biometric-unlock gap `docs/phases/01-foundation.md` already
  named, plus a second synced preference (PeelCore's own
  `biometricsOffered`, so another device can be told Face ID is on
  somewhere) that `PreferencesFile` would need generalising for, per
  this file's own first "Open" item; "Ask for the PIN" needs a
  re-lock-on-timeout concept this port has none of at all today (right
  now, once unlocked, an account simply stays unlocked); Confirm
  payments has a real balance to read now, but nothing to gate with it
  yet — no payment-sending flow (direct or boost) checks any threshold
  before sending, so the row would have nothing real to confirm;
  Stadeum and Debug need their own features entirely (the Workspaces/
  Hive integration, a crash reporter — neither exists on this port at
  all).
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
- (Superseded 2026-11-02: the boost default is back to 100 sats, as on
  iOS; it had been 10 by an earlier explicit choice.)

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

**Also done (2026-10-29), balance, Agent, Server, Twelve words, Change
PIN:**

- `engine/Engine.kt`: `commitLocked` now writes `result.newBalance`
  into `Setting.BALANCE_MSAT` whenever a result carries one; new
  `balanceMsat()` (a local read) and `serverLabel()` (ditto, for
  `Setting.SERVER`); `connectAndRegister` now persists the resolved
  `ServerAddress` to `Setting.SERVER` right where it resolves it.
- `ui/components/Avatar.kt`: `AgentTag()` moved here from
  `ChatsScreen.kt`'s own private copy, so Profile's Agent row and
  Chats' agent row share the one implementation.
- `ui/onboarding/OnboardingScreen.kt`: the `RESTORE` branch now also
  writes `Setting.WORDS_CONFIRMED to "1"`, matching PeelCore's own
  `needsRestore ? "1" : "0"` rule — a real, previously unfixed gap,
  see the Status section's own "Found and fixed" paragraph.
- `ui/you/YouScreen.kt`: balance shown under the name; a new "Agent"
  section (`PeelLogo`, `AgentTag`, "Ready"/"Set up on your Mac" from
  whether `engine.agentPubkey` is already a `contact`); the Account
  card gained Twelve words, Change PIN and Server rows alongside the
  existing Boost amount one; `TwelveWordsScreen` (a `PinPrompt`, then
  the words masked behind "Reveal"/"Hide", auto-hiding after 30
  seconds or on leaving the screen/app — a local `ON_STOP` lifecycle
  observer, not a new app-wide mechanism) and `ChangePinScreen` (old/
  new/confirm, `PinPrompt` again, `SecretStore.changePin`) — both
  reached from the Account card, both leaving through a plain `onBack`
  the way `MainTabs.kt`'s own `openChat` already does, not a new
  navigation system.
- Tests: none added for this round — `Engine.balanceMsat`/
  `serverLabel`/`connectAndRegister` need the native `sphinxrs`
  library (the same existing `Engine` gap); the new screens are
  Compose UI, the same existing gap every other screen in this port
  has.

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
