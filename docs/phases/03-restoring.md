# Phase 3 — Restoring

## Status (2026-10-28)

**Built:** the full restore flow, screens included — not just the core
calls. `Storage` gained `contact` and `message` tables (a real `onUpgrade`
step, since this device already had an account in `coreState`/`setting`
before these existed). `Engine` now owns its account's connection and
restore state as `StateFlow`s (`connectionState`, `restoreProgress`) and a
`start()` that connects, then — only for an account restored from its
words, nothing local yet — pages through `getMsgsCounts` →
`fetchFirstMsgsPerKey` (contacts) → `fetchMsgsBatch` (messages), storing
each page and updating progress as it goes. This runs on `Engine`'s own
coroutine scope, not the screen's, so it keeps going whether or not
anything is watching it — the same shape as PeelUI's `AppModel.attach` +
`Engine.start()`.

The `Engine` for an account is now made exactly once — by onboarding or
by the lock screen — and handed forward through navigation
(`MainActivity`'s `Screen.Restoring`/`Screen.Home` now carry it), instead
of each screen building its own. That is what lets `HomeScreen` drop its
"Connect to server" button entirely: by the time it can appear,
`Engine.start()` already ran, so it just shows whatever
`connectionState` already is.

Onboarding also gained what PeelCore's `Onboarding.swift` calls
`progress`: a segmented bar and "Step X of Y" under a circular back
button, on every step that has one (Android draws its own, having no
system back button inside a single Composable screen the way SwiftUI's
`NavigationStack` gets for free). A new `RestoringAccountScreen` is the
three-line checklist (Account/Contacts/Messages) from the iPhone
screenshots, each line's state and detail read straight off those two
`StateFlow`s. Found and fixed a real bug along the way: `PinDots` drew
its empty dots in the exact same colour as the screen behind them
(`Theme.colors.background`), so the six-dot row was invisible until the
first digit landed — never previously caught because nobody had looked
for it on a real screen where it's obvious, it had no test of its own
and `Theme.colors.background` vs `Theme.colors.fieldFill` read the same
in code review.

**Open:** no full `Engine.commit()` parity — `peel`'s version also
carries tribe membership, replay detection and payment settlement, which
is its own large system (see the Journal). This phase stores real
contacts and messages correctly for the direct-message case; a tribe's
messages land as a generic chat under the tribe's own pubkey, not a
`tribe` row with membership/roles. No chat UI reads `contact`/`message`
yet — `HomeScreen` still says so explicitly. No reconnect-on-drop, no
keep-alive, no resumable restore (a restore that fails partway starts
over from the beginning next time, not from where it stopped —
PeelCore's `HistoryCursor` tracks a resumable cursor; this phase's
`Engine.page` does not). An incoming message's claimed sender is trusted
directly during a restore, not checked against `state["c/<pubkey>"]`
first the way PeelCore's live-message path does — reasonable only
because this runs over the restore's own authenticated history fetch,
not a live, unauthenticated publish stream (see the Journal).

**Decided:** store messages/contacts even from the "contacts" phase
(`fetchFirstMsgsPerKey`) the same way as the "messages" phase — PeelCore's
`commit()` does not treat them differently either, both just feed
`result.msgs`. A contact's alias/photo come from parsing the newest
message's own `sender` JSON in Kotlin at insert time (`org.json`), not
from a SQL `json_extract` after the fact — Android's bundled SQLite's
JSON1 support is not guaranteed back to `minSdk` 26, so this avoids
depending on it at all.

## Journal

**What `peel`'s real restore does.** Read `Engine+History.swift`,
`HistoryCursor.swift` and the relevant slice of `AppModel.swift` in full
before writing anything, rather than guessing the shape from the
screenshots alone. `HistoryCursor` is a proper resumable state machine —
it tracks a live-message gap (`liveAbove`) against the catch-up cursor
(`from`), a stall watchdog, queued runs — built for an `Engine` that stays
connected indefinitely and both restores and gets live messages through
the same pipeline. Also found that `commit()`, the function that turns a
`RunReturn` into stored rows, is genuinely large: tribe membership
changes, replay detection (a tribe member echoing back a tag), held
messages for tribes not yet known, discovered-tribe detection, payment
settlement tied to messages. Porting all of it was never realistic for
this phase — it was designed and built for the full online pipeline this
phase isn't attempting yet.

**What got cut, and why that's still honest.** Scoped down to: connect,
ask for counts, page contacts then messages, store each page, done.
`HistoryCursor`'s resumability and stall-watchdog became a plain
start-to-finish loop with no persisted cursor — a restore that fails
partway starts over, rather than resuming. The `c/<pubkey>` trust check
`commit()` makes before filing an incoming message under its claimed
sender's chat: kept the `fromMe` half (cheap, local, still matters), but
the incoming half trusts the claim directly. That check exists in
PeelCore to guard a *live* MQTT publish stream, where anyone who can
publish to a topic could claim to be anyone; a restore's pages come back
over this device's own authenticated, TLS, signed-login connection to
its own server, fetched by explicit index — a materially different trust
boundary, not the same one with a shortcut taken.

**One `Engine`, not one per screen.** Onboarding and `HomeScreen`
previously each made their own `Engine` from the same mnemonic — fine
when the only thing an `Engine` did was answer `isRegistered`, broken the
moment `start()` needed to run once and keep a coroutine scope alive
across screens ("Open Peel" leaving a restore running in the background
is the whole point of that screen). Settled on building the `Engine`
exactly where the mnemonic first becomes available — onboarding's PIN
confirm, or the lock screen's PIN check — and threading it through
`MainActivity`'s `Screen` sealed class from there. This is also what let
the "Connect to server" button go away entirely: `HomeScreen` no longer
owns any connection logic, it only reads `Engine.connectionState`.

**Engine's own scope, and why `run`'s mutex had to stay single-locking.**
`start()` launches on a `CoroutineScope(SupervisorJob() + Dispatchers.Default)`
that belongs to the `Engine` instance itself, cancelled only by
`Engine.cancel()` ("Forget this account" calls it before wiping
`Storage`, so a page mid-flight can never write to a database that's
about to be deleted). `runRestore`/`page` call `run` like any other
caller — one call, one lock, released before the next — rather than
holding the lock across a whole phase's pages; a long restore must not
block something else that needs the engine (there isn't anything else
yet, but holding a lock no longer than the work that needs it is the
right default regardless).

**The onboarding step chrome.** `Onboarding.swift`'s `progress(_:)` and
`numbered` read cleanly as data (which steps count, and how confirming a
PIN or checking words counts as the step before it) — ported that logic
directly, with Android's own numbering since there's no invite/name step
yet (`[set PIN, show words]` for a new account, `[enter words, set PIN]`
for a restore, both two steps). The back button has no SwiftUI
`NavigationStack` to lean on, so `OnboardingScreen` grew an explicit
`back()` mapping instead of a real stack — adequate for a flow this
linear; a deeper step tree would want an actual stack.

**The invisible PIN dots.** Caught only by actually looking at a
screenshot of the PIN screen, not by reading the code — `PinDots`' empty
state used `Theme.colors.background`, textually distinct from
`Theme.colors.fieldFill` but visually identical in context, since the
field it's measured against *is* that same background. A one-line fix
(`fieldFill` instead), but a reminder that a themed-colour mismatch like
this reads correctly in a diff and is invisible — in both senses — until
someone looks at the actual screen.

## Done

- `storage/Storage.kt`: `contact` and `message` tables (DB version 2, a
  real `onUpgrade` from the version 1 devices already running on this
  machine), `insertMessages`, `upsertContact`, `markContactsReady`.
- `engine/ConnectionState.kt`, `engine/RestoreProgress.kt`: the state
  `Engine` publishes, mirrored from PeelUI's `AppModel`.
- `engine/Engine.kt`: `connectionState`/`restoreProgress` `StateFlow`s,
  `start()`, `cancel()`, `runRestore`/`page` (the contacts-then-messages
  loop), `mapMessage`/`contactHint` (the trimmed `Msg` → stored-row
  mapping).
- `ui/onboarding/OnboardingScreen.kt`: step indicator, back button and
  back navigation; builds the account's one `Engine` and calls `start()`.
- `ui/onboarding/RestoringAccountScreen.kt`: the three-line restore
  checklist, reading `Engine`'s `StateFlow`s directly.
- `ui/home/HomeScreen.kt`: no more "Connect to server" button — shows
  `Engine.connectionState` as soon as it can appear.
- `ui/components/PinPad.kt`: fixed `PinDots`' invisible empty state.
- `MainActivity.kt`: `Screen.Restoring`/`Screen.Home` now carry the one
  `Engine` made for the account, instead of each screen building its own.

## Learned

- Android's bundled SQLite's JSON1 extension is not something to lean on
  below a known-good API level; parsing JSON in Kotlin at write time
  sidesteps the question entirely for the handful of fields this needed.
- A resumable state machine (`HistoryCursor`) and a single straight-through
  loop look similar on the page but are not the same amount of work —
  worth naming the gap explicitly (here: no persisted cursor, restart
  from zero on failure) rather than letting a simplified port imply more
  resilience than it has.
- A trust check written for one channel (a live, unauthenticated publish
  stream) does not automatically apply to another (an authenticated,
  by-index history fetch) just because both produce the same `Msg` shape
  — worth asking what a check actually guards against before deciding
  whether skipping it in a different context is a real gap or a
  reasonable scope cut.
- A themed colour token used in the wrong place (`background` where
  `fieldFill` was meant) type-checks, compiles, and reads fine in a diff;
  only looking at the actual rendered screen caught it.
