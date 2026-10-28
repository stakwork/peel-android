# Phase 2 — Connecting

## Status (2026-10-28)

**Built:** `Engine.connect(server, inviteCode)`, the first real network call
this app makes. It fetches nothing by itself — the caller resolves a
`ServerAddress` first, normally from `ServerConfig.fetch(network)`, which
reads the same public config endpoint `peel`'s Mac/iPhone apps use
(`https://config.config.sphinx.chat/api/config/<network>`, `default_lsp`
field). `connect` then signs a fresh login, opens a `Transport`, runs
`initialSetup` again (idempotent: the same call onboarding already made
offline), subscribes and publishes whatever its `RunReturn` names, adds
the tribe-management topic, and spends up to ten seconds feeding whatever
the broker sends back through the core's `handle`, stopping early once
`isRegistered` turns true. `HomeScreen` has a "Connect to server" button
that calls it and shows one of: connecting, registered, connected without
a registration answer yet, or the error.

**Verified for real**, not just compiled: on a Pixel running GrapheneOS,
restoring an existing account from its twelve words (one already in use
on the Mac/iPhone apps, with zero local history on this device) and
tapping "Connect to server" reached production and came back "Connected
and registered with the server" — the config fetch, the TLS/MQTT login,
`initialSetup`'s register, and the server's answer all round-tripped for
an account this device had never seen before.

**Open:** this is "does the account reach the server and get registered,"
not the online pipeline `peel`'s `Engine.cameOnline()` runs next —
`fetchPings`, read/mute sync, and (for a restored account) the
contacts-then-messages history replay. None of that is here: there is
nowhere to put what it would return yet, no `message`/`contact`/`tribe`
tables. No reconnect-on-drop, no keep-alive ping loop either — `connect`
is a single attempt, called once from the UI.

**Decided:** the production config endpoint, not a hardcoded broker
address — same source `peel` itself treats as authoritative, so a change
there (a new LSP, a migration) does not need an app update to follow. The
register-wait is a plain ten-second timeout, not a retry loop or a
subscription confirmation callback: simplest thing that can tell "it
answered" from "it didn't," good enough for a first real-account test.

## Journal

**The one missing piece from Phase 1.** `Engine`/`Transport` compiled
against the real HiveMQ client and the real `sphinxrs` bridge, but
`Engine` made no call that needed either — until the question became
"restore an existing account from its words and see if it actually
reaches the production server." Found exactly how `peel`'s Mac/iPhone
Engine.swift does this (`establish()`): it does not hardcode a broker. It
asks `https://config.config.sphinx.chat/api/config/bitcoin` for one at
every signup and restore, live — confirmed by curling it directly:
`{"default_lsp":"mqtt-broker.v2.sphinx.chat:8883", ...}`. Ported that as
`ServerConfig` (`engine/ServerConfig.kt`), with `parse` split out as the
one part that is actually testable without a live socket.

**Login is a signed timestamp, not a password.** `clientID` is the
account's `xpub`; `username` is a fresh unique time; `password` is that
same time signed by the account's root key (`rootSignMs`) — so a
connection is never reused, reconnecting always means a new login.
`Engine.connect` derives all three fresh from the seed, same as
`establish()`.

**One lock, not two.** `connect` needed to run several core calls
(`setNetwork`, `initialSetup`) and wait on the network in between, all
under the same hold of `Engine`'s state the way Swift's actor isolation
covers the whole of `establish()` in one go. `run`'s mutex is not
reentrant, so `run` got split into itself and a lock-free `commitLocked`
that both `run` and `connect` call while already holding the lock.

**The listener starts before the register publish, not after** — matched
deliberately to `establish()`'s own ordering (its `listener` Task exists
before its register call goes out), not something stronger: both still
have the same small window between "the socket is open" and "something is
actually collecting it" where a very fast answer could be missed. Good
enough for a first test; a production reconnect path would need to close
that gap for real.

**What proves "it connects."** Not just "the socket opens" — TLS and the
login could succeed and the server still reject a malformed `initialSetup`
payload, or never answer at all. Settled on: open the connection, publish
the register, and watch for up to ten seconds for *anything* that, once
run through `handle`, sets `my_scid`/`server_pubkey` in local state. If
that happens, the account that owns these twelve words is, right now, a
real account the production server recognizes.

**Confirmed against production, on real hardware.** Restored an account
already in use on `peel`'s Mac/iPhone apps onto a Pixel running
GrapheneOS, with nothing local but the twelve words — no prior
`coreState` on this device at all. "Connect to server" came back
"Connected and registered with the server" on the first try: the config
fetch, the TLS handshake, the signed login, and the register round trip
through `initialSetup` all worked end to end against the real broker, not
a mock.

## Done

- `engine/ServerConfig.kt`: the public config fetch (`default_lsp`,
  `tribe_host`), `parse` tested on its own.
- `engine/Engine.kt`: `connect(server, inviteCode)` — login, transport,
  `initialSetup`, its topics, a bounded wait on the answer; `disconnect()`;
  `run` split into itself and `commitLocked` so both can share one lock
  hold.
- `ui/home/HomeScreen.kt`: a "Connect to server" button and a status line
  (connecting / registered / connected-no-answer / error); "Forget this
  account" now disconnects first.
- Tests: `ServerConfigTest` (2, Robolectric — `org.json.JSONObject` needs
  it on a plain JVM test, same reason `StorageTest` needs it for SQLite).
  25 tests total, all passing; `assembleDebug` green.

## Learned

- Sphinx's official apps resolve their broker from a small public HTTPS
  config service, not a baked-in address — worth checking for before
  assuming something is hardcoded just because a from-scratch rewrite
  would reasonably hardcode it for a first pass.
- Robolectric's sandboxed classloader can leave the JVM's
  `java.security` provider list in a state that breaks a later, plain
  (non-Robolectric) test in the *same forked JVM* — `PinBoxTest`'s PBKDF2
  started throwing `NoSuchAlgorithmException` only once `ServerConfigTest`
  ran first in the same fork. Fixed with `forkEvery = 1` on the unit test
  task (`app/build.gradle.kts`); worth remembering before assuming a
  flaky-looking crypto failure is about the crypto.
- `kotlinx.coroutines.Mutex` is not reentrant: a method that needs to hold
  a lock across several calls that each used to take the lock themselves
  has to split "take the lock" from "do the thing," the way `run` and
  `connect` now share `commitLocked`.
