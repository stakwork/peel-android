# Peel for Android

Last updated 2026-11-02 (Phase 15). This is the architecture and the phase index, the
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
│ onboarding, lock, dashboard       │
│ (Chats/Payments/You/Search tabs)  │
├──────────────────────────────────┤
│ app/account/                     │
│ Account (pure helpers), PIN-     │
│ sealed SecretStore               │
└──────────────┬────────────────────┘
               │
┌──────────────▼──────────────────────────────────────────┐
│ app/engine/Engine — runs core calls, commits the state   │
│ they return; `start()` connects, and for a restored       │
│ account pages through its contacts and messages,          │
│ publishing connectionState/restoreProgress as StateFlows   │
│ for any screen to read. Every connection (not only a       │
│ fresh restore's) catches up on history since; `resume()`   │
│ reconnects and catches up again on returning to the        │
│ foreground — not yet a real keep-alive (no way to detect    │
│ a drop short of reconnecting blind)                         │
│       │                                                   │
│       ├── app/storage/Storage (SQLite): coreState,        │
│       │   setting, contact, message                       │
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
`Engine.connect` additionally performs the network half of `initialSetup`'s
result (subscribe, publish, and whatever answers come back over
`Transport`). `Engine.start`'s restore pipeline wires up three more calls
— `getMsgsCounts`, `fetchFirstMsgsPerKey`, `fetchMsgsBatch` — committing
each one's state the same way `run` always has, plus (new in Phase 3)
storing the messages and contacts each one's `RunReturn` carries.
`Engine.sendMessage` (Phase 5) is the first call this account originates
rather than only receives: still through `run`, so it still
commits+publishes under the one lock, but its own row is built by hand
from what was just sent rather than read back out of the result.

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

Two more, added in Phase 3 for the restore pipeline, ported from `peel`'s
`Storage+Migrations.swift` `v1`/`v2`/`v3` (the columns a restore actually
fills, trimmed):

- `message`: one row per message a restore or a live fetch brings back;
  `status` (Phase 5) is a sent row's own delivery state
  (`PENDING`/`DELIVERED`/`FAILED`), always null for anything not sent
  from this device; `deleted` (Phase 14) is set only by
  `Storage.applyDeletes`, a type-17 message naming this one and
  confirmed delivered — never by a caller building a row by hand.
- `contact`: a pubkey, its alias/photo (parsed from its own messages, not
  core state — names and photos never are, and never for a tribe, whose
  real name/photo come from the tribes server, Phase 4), whether
  it's a tribe (`isTribe`, Phase 4 — the signal is a tribes server
  marking what it forwards, not anything the chat itself declares),
  whether the core (or, for a tribe, just being seen at all) now
  recognises it (`ready`), and — for a tribe, from the same registry
  call as its name/photo — what it charges per message
  (`pricePerMessageMsat`/`escrowMsat`, Phase 5/9/11).
- `chatState` (Phase 6): each chat's own read marker and mute level —
  synced from the server (`getReads`/`getMutes`), not decided locally;
  `readPending` marks one moved here that hasn't reached the server yet.
- `preference` doesn't exist as a table of its own yet (unlike
  PeelCore's general `PreferenceRow` registry) — Phase 11's one synced
  preference (the boost amount) lives as two rows in `setting` instead;
  a general registry is real future work once there's a second key to
  sync.

No `tribe` (the rich kind — escrow terms, membership, roles; what this
port needs of a tribe's price lives on `contact` instead, trimmed the
same way `isTribe` already was), `payment`, `invoice` or
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
| 3 | Restoring: `contact`/`message` tables, `Engine.start`'s contacts-then-messages pipeline, the restore screens | built; see the file for what's open | [phases/03-restoring.md](phases/03-restoring.md) |
| 4 | Dashboard: live message storage, tribe detection, the Chats list and tab bar | built; see the file for what's open | [phases/04-dashboard.md](phases/04-dashboard.md) |
| 5 | Conversation: the chat screen, grouped message rows, sending a text message | built; see the file for what's open | [phases/05-conversation.md](phases/05-conversation.md) |
| 6 | Read state: unread counts, mute levels, synced read markers | built; see the file for what's open | [phases/06-read-state.md](phases/06-read-state.md) |
| 7 | Reply: replying to a message from the chat screen | built; see the file for what's open | [phases/07-reply.md](phases/07-reply.md) |
| 8 | Attachments: receive and show images/video/audio/PDF/files; send images (Photo/Camera) | built; see the file for what's open | [phases/08-attachments.md](phases/08-attachments.md) |
| 9 | Threads and boosts: collapsed "N replies" + a thread sheet, boost chips | built; see the file for what's open | [phases/09-threads-boosts.md](phases/09-threads-boosts.md) |
| 10 | Search: the bottom bar's Search tab, filtering the chat list by name | built; see the file for what's open | [phases/10-search.md](phases/10-search.md) |
| 11 | Profile and preferences sync: an editable name, the boost amount, synced via the memes-server preferences file | built; see the file for what's open | [phases/11-profile-preferences.md](phases/11-profile-preferences.md) |
| 12 | Calls: call-link detection and validation, the call card, starting a call, the presence feed | superseded same day by Phase 13's in-app join; see that file | [phases/12-calls.md](phases/12-calls.md) |
| 13 | LiveKit calls: a real in-app join (LiveKit Android SDK, a tile grid, mic/camera/leave) — no persistent background call, screen share or in-call chat yet | built; see the file for what's open | [phases/13-livekit-calls.md](phases/13-livekit-calls.md) |
| 14 | Message menu: Copy, Copy link, Delete (a real for-everyone delete, not a local hide) — no tribe-owner delete yet | built; see the file for what's open | [phases/14-message-menu.md](phases/14-message-menu.md) |
| 15 | Calls keep running in the background: a call survives leaving its screen, a banner (dot, title, running time, mute, leave) on every other screen — no cross-chat switch confirmation, no foreground service for real OS backgrounding | built; see the file for what's open | [phases/15-call-background.md](phases/15-call-background.md) |
| 17 | Lock hardening: Keystore wall under the PIN blob, biometric unlock, wrong-PIN throttle, `pin_timeout`/`biometric_enabled` (synced), FLAG_SECURE, clipboard hygiene | built, not device-tested; see the file | [phases/17-lock-hardening.md](phases/17-lock-hardening.md) |
| 18 | Markdown in message text (blocks, emphasis, code, https/named links, mentions), ported from `MessageMarkup.swift` | built, not device-tested; see the file | [phases/18-markdown.md](phases/18-markdown.md) |
| 19 | Chat-list actions (mute levels, mark read/unread, swipe, long-press menu) and Retry/Discard of failed sends with reconnect auto-resend, ported from `ChatsTab.swift`/`Engine+Messages.swift` | built, not device-tested; see the file | [phases/19-chat-actions-send-retry.md](phases/19-chat-actions-send-retry.md) |
| 20 | Invites (signup only with an invite, make/cancel invites) and add contact, key exchange, ported from `Account.signup`/`Engine+Contacts.swift` | built, unit-tested, not device-tested; see the file | [phases/20-invites.md](phases/20-invites.md) |

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
