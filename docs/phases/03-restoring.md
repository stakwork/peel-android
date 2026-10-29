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

The first version of this compiled, ran, and did nothing: `getMsgsCounts`
(and the two page calls) don't return their answer as their own
`RunReturn` — they ask the server, and the real answer comes back later
as an inbound MQTT message, the same asymmetry `connect`'s register
already has. `Engine` now runs one persistent inbound listener for the
whole life of a connection (`startListening`, started once from
`connect`), broadcasting every `handle`d result on a `SharedFlow`
anything can wait on (`askAndAwait`) — not a listener per request, and
not the original bounded ten-second window that only covered the
register wait. Confirmed against the real account this was built
against: the first correct answer back from production was `{"total":
11653, "first_for_each_scid": 214, ...}`, matching the scale the iPhone
app's own restore screen showed for the same account.

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

**Also built, once a chat screen (Phase 5) made the gap obvious:**
`Engine` now does what PeelCore's own `cameOnline` does on *every*
successful connection, not only a fresh restore's — `catchUpMessages`
asks `fetchMsgsBatch` for everything from where this device's own
history stands (`Storage.maxServerIndex`, read back from what is
already stored rather than kept as a separate setting) forward, and
`resume()` (called from `MainActivity`'s lifecycle observer on every
return to the foreground) reconnects and runs it again. Before this, a
message that arrived while the app was closed, or while it sat
backgrounded with its connection quietly dropped, was never fetched —
only restoring an account's full history, once, ever asked the server
for anything; after that first restore, this device only ever heard
whatever arrived over a live connection it happened to still be holding.
See the Journal for how that was found and the real bug underneath it
(`startListening`'s one-time guard, which made *any* reconnect broken,
not just the missing catch-up).

**Open:** restoring 11,653 messages at 100 per page is on the order of
120 sequential ask-then-wait round trips, one at a time, and that is
real and not a gap this port has that PeelCore doesn't — `HistoryCursor`
is sequential for the same reason, and has to be: a history page carries
no request id (its own comment on `advance`), so a reply can only ever
be matched to *some* outstanding ask, never to the specific one that
produced it. Firing several pages at once (what an earlier version of
this note called "pipelining") would not be an optimization here, it
would be a correctness bug on both platforms — there is no way to tell
which of several in-flight replies answers which ask. What's actually
not known yet is *why* a restore here would be slower than the iPhone's
for the same account at the same page count and the same one-at-a-time
design — added per-page timing (`Log.d("PeelHistory", ...)`, elapsed ms
each) to find out, rather than guess further; needs a real restore run
with logcat attached to get an answer. One concrete, safe fix landed
either way: `markContactsReady` was re-decoding the core's entire
contact list (`listContacts`) on *every* page of the contacts phase
instead of once after it — real waste, if a smaller one than pipelining
would have been had it been safe. No full `Engine.commit()` parity — `peel`'s version also
carries tribe membership, replay detection and payment settlement, which
is its own large system (see the Journal). This phase stores real
contacts and messages correctly for the direct-message case; a tribe's
messages land as a generic chat under the tribe's own pubkey, not a
`tribe` row with membership/roles. No chat UI reads `contact`/`message`
yet — `HomeScreen` still says so explicitly. No resumable restore (a
restore that fails partway starts over from the beginning next time, not
from where it stopped — PeelCore's `HistoryCursor` tracks a resumable
cursor; this phase's `Engine.page` does not). No keep-alive and no way
to *detect* a dropped connection (no ping, no HiveMQ connection-state
callback): `resume()` always disconnects and reconnects on its own,
regardless of whether the old connection was actually still good, rather
than finding out first — a correct but wasteful substitute for the
"notice the drop" PeelCore's keep-alive loop actually does. No gap
tracking either (PeelCore's `HistoryCursor.liveAbove`): a live message
that arrives above a gap this device hasn't caught up on yet is simply
stored where it lands, same as always, rather than held until the
catch-up underneath it fills in — not reachable without the same
reconnect/overlap machinery. An incoming message's claimed sender is trusted
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

**"None" everywhere, and no error to explain it.** The first real restore
test came back registered, but Contacts and Messages both said "None" —
`runRestore` had reached `.Finished` having asked for counts and gotten
nothing back, no exception either. Logged the raw `msgsCounts`/`error`
fields straight from the `RunReturn` to find out, which showed
`msgsCounts=null, error=null`: not a refusal, just never answered.
Re-reading `Engine+History.swift`'s `resumeHistory()` made the shape
clear — `history.awaitingCounts = true` is set *before* the ask, which
only makes sense if the real answer is not the call's own return value.
Confirmed in `Engine.swift`'s general `run()`: it always both commits
*and* publishes a result's topics, which this port's `run` never did —
it only committed. Two gaps, not one: nothing published the ask, and
nothing was listening afterward either (the one inbound collector that
existed was `connect`'s own, bounded to the ten-second register wait).

**One listener, not one per ask.** `Transport.inbound()` is a cold
`Flow` — collecting it again registers a fresh callback on the HiveMQ
client each time, and a page the user's account restore needs ~120 of
those for. Rather than re-collecting per request (and leaking a growing
pile of registered callbacks over a long restore), `connect` now starts
exactly one collector for the life of the connection, broadcasting
through a `MutableSharedFlow` any number of waiters can read from
(`askAndAwait`). Had to move it to start right after the transport
connects, before `initialSetup` even publishes its register — same
small "could still miss a very fast answer" race `connect` already
accepted for the register wait, now shared by every ask, not a new
problem introduced by this fix.

**The lock had to stop guarding "the whole conversation" and start
guarding only `state`.** The straightforward way to add the listener —
hold `mutex` while waiting for its answer — deadlocks: `connect` held
the lock for its entire body, including the register wait, and the
listener's own per-message handling needs that same lock to commit.
Split it: `connect`'s setup work happens under the lock and returns
before the register wait starts, the listener acquires the lock only
per message (same as any other caller of `run`), and `state` became
`@Volatile` since `isRegistered` now gets read from the waiting
coroutine without holding the lock at all.

**The PIN screens looked frozen because nothing said otherwise.**
Reported independently of the restore bug: both the onboarding PIN step
and the lock screen sat with no visible change for five to ten seconds
after the sixth digit, before moving on — `working` already existed on
both and disabled the keypad, but nothing rendered for it. Added a
`CircularProgressIndicator` gated on `working` to both (`PinPrompt` for
the lock screen covers both call sites that use it; onboarding's
`ConfirmPin` step directly, since it is the only one that ever sets
`working` true).

**That delay was also a real, fixable one, not just a missing spinner.**
Asked why six-digit PBKDF2 at the same 2,500,000 iterations takes
seconds here and is near-instant on the iPhone. The gap is
`SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")` itself — Android's
implementation of that algorithm name carries real per-iteration
overhead CommonCrypto's doesn't, not anything about the iteration count
or the algorithm. PBKDF2's output is fixed by RFC 8018, not the
provider, so a hand-rolled version using `Mac` directly computes the
identical key, just without that overhead — verified with a test that
derives the same PIN and salt both ways and asserts the bytes match.
PINs here are always six ASCII digits, so the usual PBKDF2-password
encoding ambiguity (UTF-8 vs. Latin-1 vs. a raw char cast) never comes
up: all three agree on `'0'`–`'9'`.

**"Forget this account" had no confirmation.** One tap, no dialog,
wiped a real account's local data — found out when the user tapped it
mid-test and watched everything disappear with no "are you sure."
Nothing was unrecoverable (the account still exists on the server; the
words restore it again), but a single tap for a one-way local wipe was
still a real gap. Added a confirmation `AlertDialog` that says plainly
what survives (the words) and what doesn't (anything only on this
device).

**Content under the status bar.** `RestoringAccountScreen` draws its
title at the very top of the screen, unlike the other screens so far
(onboarding's `Scaffold` and `HomeScreen`'s centred content both
happened to hide the same gap). `targetSdk` 36 makes edge-to-edge
mandatory — the system draws behind the status/navigation bars whether
an app asks for it or not — so every screen needs its own insets
handling, or whichever one draws nearest the top shows it first. Fixed
once, in `MainActivity`, rather than per screen: the root `Surface`
still paints the theme's background across the whole window, including
behind the bars, but a `Box(Modifier.safeDrawingPadding())` inside it is
what the actual screens render into. Splitting those two was the part
that needed care — padding the `Surface` itself instead pushed its
background color in too, leaving the status bar area showing the
window's own default (light) background rather than the app's dark one.

**"A lot of messages are missing compared to iOS/Mac."** Reported once a
real chat screen existed to actually notice it in (Phase 5) — this phase
originally shipped `start()` calling `runRestore()` only when
`Setting.NEEDS_RESTORE` was still `"1"`, exactly once per account ever.
Re-read `Engine+History.swift`'s `resumeHistory()` to check: it runs on
*every* `cameOnline()`, not only a fresh account's, and for one already
restored it calls `syncMessages()` — a `fetchMsgsBatch` from
`history.from` (`Setting.historyFrom`, persisted) forward, no
`getMsgsCounts` needed first. Android had nothing at all for this case:
once `NEEDS_RESTORE` flips to `"0"`, every later `start()` just connects
and waits for whatever MQTT happens to deliver live — anything that
arrived while this device had no live connection open to hear it (the
app closed, or just not yet launched that session) was gone for good.
Ported as `catchUpMessages`, reusing the existing `page()` loop with a
new starting index instead of always `0`. Skipped persisting a separate
`historyFrom` setting: every stored message already carries its own
`serverIndex`, so `Storage.maxServerIndex() + 1` *is* that marker, with
one accepted gap noted on that function itself (a sent message's own
optimistic row has no index until its echo, which `INSERT OR IGNORE`
then discards — makes a catch-up redo a little more work than strictly
needed, never less).

**Catch-up alone wasn't enough — found a second, worse bug while adding
it.** The user also asked about the app being backgrounded and brought
back to the foreground, not just relaunched. Wiring a reconnect for that
(`Engine.resume()`, called from a `MainActivity` lifecycle observer on
`ON_RESUME`) surfaced that `startListening`'s `listenerStarted` guard was
a one-time flag on the `Engine` instance, never reset — meaning *any*
second `connect()` on the same `Engine` (which `resume()`'s reconnect is
the first thing in this codebase to ever actually do) would silently
never attach a listener to the new `Transport` at all. Not just a missed
catch-up: every `askAndAwait` after a reconnect — any history page, any
future send's confirmation — would sit out its full timeout waiting on a
broadcast nothing was listening to produce, live messages included.
Fixed by clearing the flag in `disconnect()`, so a reconnect's `connect()`
starts a fresh collector the way the very first one did. Would never
have been exercised by `start()` alone, since that only ever connects
once per `Engine` instance; `resume()` existing at all is what forced it
into the open.

**Revisiting "the restore pipeline needs performance work."** That was
this project's own earlier framing (an open item carried from this
phase's first pass), and it assumed pipelining — asking for the next
page before the current one's answer lands — was the obvious lever,
just not pulled yet. Re-reading `HistoryCursor.swift`'s `advance` before
touching anything showed why it never pipelines either: its own comment
says a page "carries no request id," so `Engine`'s one incoming-message
listener (either platform's) has no way to tell which outstanding ask a
given reply is answering, only that *some* ask is now answered. Two or
more pages in flight at once would risk a reply landing against the
wrong one. That rules out the fix this note previously assumed was
possible; what's left is finding out why Android would still be slower
than iOS at the exact same one-at-a-time design, which needs a timed
restore to actually answer rather than more reading.

## Done

- `storage/Storage.kt`: `contact` and `message` tables (DB version 2, a
  real `onUpgrade` from the version 1 devices already running on this
  machine), `insertMessages`, `upsertContact`, `markContactsReady`.
- `engine/ConnectionState.kt`, `engine/RestoreProgress.kt`: the state
  `Engine` publishes, mirrored from PeelUI's `AppModel`.
- `engine/Engine.kt`: `connectionState`/`restoreProgress` `StateFlow`s,
  `start()`, `cancel()`, `runRestore`/`page` (the contacts-then-messages
  loop), `mapMessage`/`contactHint` (the trimmed `Msg` → stored-row
  mapping); `run`/`connect` now both commit *and* publish a result's
  network work; one persistent inbound listener per connection
  (`startListening`) broadcasting on a `SharedFlow` (`inboundResults`)
  that `askAndAwait` waits on — the actual fix for the restore pipeline,
  which asks the server and gets its answer back later, not synchronously.
  `catchUpMessages` (run on every connection once restored, not only a
  fresh one), `resume()` (reconnect + catch up, for a lifecycle observer
  to call), `connectAndRegister` (the connect-and-handle-errors half of
  `start()`, shared with `resume()`); `disconnect()` now resets
  `listenerStarted` so a reconnect's listener actually attaches;
  `markContactsReady`/`listContacts` moved to once per contacts phase,
  not once per page; `page` logs each page's own round-trip time.
- `storage/Storage.kt`: `maxServerIndex()` — where a catch-up resumes
  from, read back from what is already stored rather than a separate setting.
- `ui/onboarding/OnboardingScreen.kt`: step indicator, back button and
  back navigation; builds the account's one `Engine` and calls `start()`;
  a loading spinner on `ConfirmPin` while it does.
- `ui/onboarding/RestoringAccountScreen.kt`: the three-line restore
  checklist, reading `Engine`'s `StateFlow`s directly.
- `ui/home/HomeScreen.kt`: no more "Connect to server" button — shows
  `Engine.connectionState` as soon as it can appear; "Forget this
  account" now confirms first.
- `ui/components/PinPad.kt`: fixed `PinDots`' invisible empty state;
  `PinPrompt` (the lock screen) shows a spinner while `working`.
- `account/SecretStore.kt`: `PinBox.derive` is now a hand-rolled RFC 8018
  PBKDF2-HMAC-SHA256 instead of going through
  `SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")` — several times
  faster on this device for the same 2,500,000 iterations, verified
  byte-identical to the standard provider by test.
- `MainActivity.kt`: `Screen.Restoring`/`Screen.Home` now carry the one
  `Engine` made for the account, instead of each screen building its own;
  `safeDrawingPadding` so content clears the status/navigation bars on
  every screen, in one place; `ResumeOnForeground`, a lifecycle observer
  that calls `Engine.resume()` on every `ON_RESUME` after the first.

## Learned

- A note written at the end of a phase, naming what's left as "just
  unoptimized," is itself a claim worth re-checking before acting on it
  later — this project's own earlier "no pipelining yet" turned out to
  describe a correctness constraint of the protocol (no request id on a
  history page), not a missed optimization, once the reference
  implementation's own handling of the same limit was actually read
  rather than assumed absent.
- "Restore once, then live messages only" quietly assumes the app is
  never closed and never loses its connection between launches — worth
  checking a reference implementation's own `cameOnline`/equivalent for
  whether it treats *every* connection as a chance to catch up, not only
  the first, before shipping a port that only does the latter.
- A guard meant to make a function idempotent *within one connection's
  lifetime* (`startListening`'s `listenerStarted`) can quietly become a
  guard against ever running again *at all* once something finally
  calls the enclosing function a second time — the bug was real the
  whole time, just never exercised, because nothing before `resume()`
  ever reconnected the same `Engine` instance more than once.
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
- A call that returns a `RunReturn` is not necessarily answered by that
  same `RunReturn` — `history.awaitingCounts` in PeelCore's own source
  was the tell that the real answer comes back later, over the
  connection, not as the call's own result; worth reading that kind of
  signal as a clue about the protocol's actual shape, not just a naming
  choice.
- `Transport.inbound()` being a cold `Flow` makes "collect it again for
  this one ask" an easy first instinct that doesn't scale — a
  connection-lifetime listener broadcasting to every waiter is the right
  shape once there is more than one thing that might need an answer.
- Android's own crypto provider for a named algorithm (here,
  `PBKDF2WithHmacSHA256`) is not guaranteed to be fast just because the
  algorithm itself is standard and the iteration count is fixed — worth
  checking a suspiciously slow "the spec says do X" step against a
  hand-rolled version before assuming the cost is inherent to X.
- `targetSdk` 35+ makes edge-to-edge non-optional; a screen that draws
  content at the very top is the one that notices first, but every
  screen needs the same insets handling, so it belongs at the root, once.
