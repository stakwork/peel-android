# Peel for Android

Last updated 2026-10-28. This is the architecture and the phase index, the
same role `peel`'s `docs/PLAN.md` plays for Mac and iPhone. Each phase has
its own file under `docs/phases/` with what is done, what is open and what
is decided; update it when you finish something, same rule as there.

## Why

`peel`'s Mac and iPhone apps are a from-scratch native Sphinx client on a
shared Rust core, built to replace the old official clients. Android is the
third platform of the same client, on the same core
(`stakwork/sphinx-rs`), so it is indistinguishable from the others at the
protocol level: the wire format, the message JSON, the side services are
fixed by what the server and the official Android app can observe.
Everything else — Kotlin instead of Swift, Jetpack Compose instead of
SwiftUI, SQLite through a plain `SQLiteOpenHelper` instead of GRDB, the
HiveMQ MQTT client instead of `mqtt-nio` — is a platform-appropriate
reimplementation of the same design, not a literal port.

Read `docs/VISION.md` next, then the phase below.

## Architecture

```
┌──────────────────────────────────┐
│ app/ui/ (Compose)                │
│ onboarding, lock, home            │
├──────────────────────────────────┤
│ app/account/                     │
│ Account (pure helpers), PIN-     │
│ sealed SecretStore               │
└──────────────┬────────────────────┘
               │
┌──────────────▼──────────────────────────────────────────┐
│ app/engine/Engine — runs core calls, commits the state   │
│ they return; `connect` additionally logs in and runs the  │
│ network work a result asks for (register, subscribe,      │
│ publish) — not yet the online pipeline past that (no      │
│ message/contact sync, no reconnect)                        │
│       │                                                   │
│       ├── app/storage/Storage (SQLite): coreState,        │
│       │   setting                                         │
│       ├── app/engine/Transport (MQTT, HiveMQ client):      │
│       │   connects for real, one attempt at a time         │
│       └── app/engine/ServerConfig: the broker address,     │
│           fetched live, not hardcoded                      │
└──────────────┬────────────────────────────────────────────┘
               │ sphinxrs (generated Kotlin bindings, UniFFI)
┌──────────────▼──────────────────────────────────────────┐
│ Rust core: stakwork/sphinx via sphinx-rs/sphinx-ffi      │
│ keys, onions, key exchange, tribes, invoices, routing    │
└─────────────────────────────────────────────────────────┘
               │ MQTT over TLS
        broker + mixer (the LSP), tribes server, memes,
        LiveKit, hub (push), config endpoint
```

### The core contract

Same contract `peel`'s PLAN.md describes: the core is a library of pure
functions, each taking the seed, a unique time and the whole state (a
MessagePack map), returning what changed — state to save or delete,
topics to subscribe, payloads to publish, messages that arrived.
`Engine.run` commits what a call returns before anything else can run;
`Engine.connect` additionally performs the network half of one call's
result (subscribe, publish, and whatever answers come back over
`Transport`) — still only for `initialSetup`, the one call wired up so
far.

Reference implementations, in order of authority:

1. The core itself: `stakwork/sphinx` (private; ask for access, or read
   `peel`'s copy of the relevant pieces).
2. `peel`'s own Swift engine: `Sources/PeelCore/Engine.swift` and its
   extensions. The most complete reference for what a call means and what
   committing its result should eventually do.
3. The sphinxrs Kotlin bindings themselves
   (`sphinxrs/src/main/java/uniffi/sphinxrs/sphinxrs.kt`): generated, but
   the only authority on the exact Kotlin function signatures.

### Storage

One SQLite file per account (`peel-<account>.db`), via a plain
`SQLiteOpenHelper` (`app/storage/Storage.kt`) rather than Room or
SQLDelight: see that file's history for why. Two tables so far, both
ported from `peel`'s `Storage+Migrations.swift` `v1`:

- `coreState`: opaque key/value owned by the core.
- `setting`: this account's own state and configuration, one row per
  `Setting` case (`app/storage/Setting.kt`, a trimmed port of `peel`'s
  `Models.swift` `Setting` enum).

Everything one engine call changes is written through a single
`Storage.Commit` per transaction, so the account can never be
half-updated — the same rule `peel`'s `Storage.Commit`/`apply` follows.
No `message`, `contact`, `chatState`, `tribe`, `payment`, `invoice` or
`deviceRequest` tables yet; they come with the features that need them.

### Identity and secrets

The twelve words are generated or validated through `sphinxrs`
(`app/account/Account.kt`, pure helpers, no stored state), then sealed
under a six-digit PIN (`app/account/SecretStore.kt`'s `PinBox`): PBKDF2
at 2,500,000 iterations, AES-GCM, the exact byte layout `peel`'s
`SecretStore.swift` `PINBox` writes to the Keychain. Kept in this app's
private file storage, which Android already sandboxes to this app; not
wrapped in Android Keystore yet (`peel`'s Mac/iPhone equivalent is the
OS Keychain's own access-group restriction, layered on top of the same
PIN-derived encryption — Android Keystore-backed `EncryptedSharedPreferences`
would be the matching second layer here, not yet added).

### Platform

GrapheneOS is a first-class target: see `docs/VISION.md`. No dependency on
Google Play Services anywhere load-bearing. Android SDK, NDK (via
`cargo-ndk`, not `cross`/Docker) and the exact rebuild steps for the
`sphinxrs` bridge are in the repo `README.md`.

## Phases

| # | Phase | Status | File |
|---|-------|--------|------|
| 1 | Foundation: project scaffold, the sphinxrs FFI bridge, onboarding, PIN-sealed storage, the Storage/Engine/Transport skeleton | built; see the file for what's open | [phases/01-foundation.md](phases/01-foundation.md) |
| 2 | Connecting: `Engine.connect`, the config-fetched broker address, a real register attempt against production | built; see the file for what's open | [phases/02-connecting.md](phases/02-connecting.md) |

## Open questions

- Whether the account engine (storage schema, `Commit`, `Engine`) should
  grow inside `app/` as it has so far, or move to its own Gradle module
  once UI code needs to depend on it without depending on Compose (the
  `peel` Mac/iPhone split, `PeelCore` vs. `Apps/`). Not decided; revisit
  once a second UI surface (a notification, a widget) needs engine code.
- Whether to keep `Account`/`Engine`/`SecretStore` genuinely untestable on
  a plain JVM (no native lib for the host), or invest in a host (macOS)
  build of `sphinxrs` so Robolectric tests can exercise them for real. See
  `phases/01-foundation.md`, "Learned".
