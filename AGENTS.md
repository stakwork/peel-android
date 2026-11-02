# Agent Guide

Read `docs/VISION.md` before anything else, then `docs/PLAN.md`
(architecture and phase index), then the phase you are working on under
`docs/phases/`. A phase with work done opens with a short status (built,
open, decided) above its journal; update the status and the journal when
you finish something. Same convention the `peel` repository (Mac/iPhone)
uses for its own `AGENTS.md`/`docs/` — read that repository's `AGENTS.md`
too when porting something from it, which is most things so far.

This file is a seed, not a finished map: it covers what exists today and
grows the way `peel`'s did, as the project does. Don't pad it with
sections for code that isn't written yet.

## Project layout

- `app/` — the Compose app (`com.sphinx.peel`).
  - `account/` — `Account` (pure helpers over `sphinxrs`: generate,
    validate, the word check), `Pin`, `SecretStore`/`PinBox` (the twelve
    words sealed under a PIN). Ported from `peel`'s
    `Engine+Account.swift` and `SecretStore.swift`.
  - `storage/` — `Storage` (`coreState`, `setting`; one SQLite file per
    account, written through `Storage.Commit`), `Setting`. Ported from
    `peel`'s `Storage.swift`/`Storage+Migrations.swift`/`Storage+Writes.swift`.
    Plain `SQLiteOpenHelper`, not Room: see `docs/phases/01-foundation.md`
    for why.
  - `engine/` — `Engine` (the core contract: `run` calls a core function,
    commits what it returns), `StateCodec` (the core's MessagePack
    state-map format), `UniqueClock`, `ServerAddress`, `Transport` (MQTT,
    HiveMQ client, built but not connected to anything). Ported from
    `peel`'s `Engine.swift`, `StateCodec.swift`, `Transport.swift`.
  - `ui/` — Compose screens and the pieces they share.
    - `theme/` — `Theme`, colours taken from `peel`'s
      `Apps/Shared/Theme.swift`.
    - `components/` — `PinPad`, `NumberedWords`, `WordEntry`,
      `WordCheckBoxes`. Ported pixel-for-pixel from `peel`'s
      `PhoneControls.swift`/`PinPad.swift`.
    - `onboarding/`, `lock/`, `home/` — new account or restore, the PIN
      lock screen, a placeholder account screen (no chat list yet).
  - `push/` — FCM (`PushRegistrar`, `PeelMessagingService`), `Notifications`
    (channels, deep-link intents), `MessageNotifier`, `PushKeyStore`.
    `call/CallService` is the call foreground service; `engine/ConnectionWatcher`
    reconnects on network change/app start. `docs/phases/16-push-and-reliability.md`.
  - `MainActivity.kt` — routes between those three screens.
- `sphinxrs/` — generated UniFFI Kotlin bindings to the Rust core
  (`uniffi.sphinxrs`) plus the native `libsphinxrs.so`. Do not hand-edit;
  see the repo `README.md`, "Regenerating the sphinxrs bridge".

## Conventions

- **Port, don't invent.** Before writing a new piece of client logic
  (anything that isn't pure Compose UI), check whether `peel`'s Mac/iPhone
  apps already have it and read that file first. The wire protocol, the
  server's expectations and the security properties (the PIN-sealing
  scheme, TLS defaults) are already decided there; Android matching them
  is the point, not a free choice each time.
- **Say what's not ported, and why, in the same file or its doc.** Several
  files here are deliberately a fraction of their Swift original (`Engine`
  has no message/contact/tribe handling yet because `Storage` has no
  tables for them). Don't let that scope-narrowing go unstated: a future
  session (or reviewer) needs to know it's a known gap, not a missed spot.
- **Test what doesn't need the native library; say plainly what does.**
  `Account` and `Engine` can't be unit-tested on a plain JVM yet (no host
  build of `sphinxrs` to load); `StateCodec`, `ServerAddress`, `Pin` and
  the pure parts of `Account` can and do have tests. Don't skip a test
  because "it's all tied up with sphinxrs anyway" without checking
  whether the specific logic actually needs it.
- **Update `docs/` when you land something**, the phase file's Status and
  Journal at minimum. A session that builds a feature and doesn't record
  it is lost context for the next one.
