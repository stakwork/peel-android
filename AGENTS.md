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
  - `account/` also holds the lock hardening (`docs/phases/17-lock-hardening.md`):
    `KeystoreKeys.kt` (Keystore seams: `KeyWall`, `StateSigner`, `BiometricKeyVault`
    + Android impls), `Lockout.kt` (pure wrong-PIN throttle), `PinTimeout.kt`,
    `SensitiveClipboard.kt`. `FileSecretStore` wraps the PIN blob in the Keystore
    (`secret.<a>.v2`) and migrates legacy `.v1`. `ui/SecureWindow.kt` = FLAG_SECURE;
    `ui/lock/BiometricUnlock.kt` = BiometricPrompt. Keep new Keystore use behind
    those interfaces so it stays JVM-testable.
  - `engine/` — `Engine` (the core contract: `run` calls a core function,
    commits what it returns), `StateCodec` (the core's MessagePack
    state-map format), `UniqueClock`, `ServerAddress`, `Transport` (MQTT,
    HiveMQ client, built but not connected to anything). Ported from
    `peel`'s `Engine.swift`, `StateCodec.swift`, `Transport.swift`.
  - `engine/RestoreCursor.kt` — a restore's resumable position
    (`Setting.RESTORE_CURSOR`); see `docs/phases/03-restoring.md`.
  - `engine/Payments.kt`, `Invoice.kt`, `Sats.kt` — money: balance checks,
    invoices, payment history, pay/request/send (ported from `Engine+Payments.swift`,
    `Invoice.swift`, `Sats.swift`); see `docs/money-parity.md`.
  - `ui/payments/` — the Payments tab, Receive/Send, the QR scanner (CameraX +
    ZXing) and the in-chat payment and request rows.
  - `engine/MessageMarkup.kt`, `Mentions.kt` — message markdown parser (pure, JVM-tested) and
    mention helpers; `ui/components/MessageText.kt` draws it (`docs/phases/18-markdown.md`).
  - `engine/ChatActions.kt` (`MuteLevel`, `ReadToggle`) and `engine/SendRetry.kt` (pure
    retry/auto-resend rules; JVM-tested) back the chat-list actions and failed-send
    Retry/Discard (`docs/phases/19-chat-actions-send-retry.md`); UI in
    `ui/dashboard/ChatRowActions.kt` and `UndeliveredLine.kt`.
  - `engine/Invite*.kt`, `ContactCode.kt`, `InviterFlow.kt`, `Invites.kt` and `account/Signup.kt` — invites and
    contacts (`docs/phases/21-invites.md`): a new account only signs up with an invite; `addContact`,
    `makeInvite`, `cancelInvite`; `run(ordered = true)` publishes in PeelCore's `publishPlan` order.
  - `engine/TribeLink.kt`, `TribeForm.kt`, `TribeMembers.kt`, `Tribes.kt` — tribe join/create/edit/leave/delete,
    members and requests, kick, pin (`docs/phases/23-tribe-management.md`): pure parsers/builders (JVM-tested) and
    `Engine` extensions over the core's tribe calls; UI in `ui/tribes/`.
  - `ui/` — Compose screens and the pieces they share.
    - `theme/` — `Theme`, colours taken from `peel`'s
      `Apps/Shared/Theme.swift`.
    - `components/` — `PinPad`, `NumberedWords`, `WordEntry`,
      `WordCheckBoxes`. Ported pixel-for-pixel from `peel`'s
      `PhoneControls.swift`/`PinPad.swift`.
    - `onboarding/`, `lock/`, `home/` — new account or restore, the PIN
      lock screen, a placeholder account screen (no chat list yet).
  - `push/` — FCM (`PushRegistrar`, `PeelMessagingService`), `Notifications`
    (channels, deep-link intents), `MessageNotifier`, `PushKeyStore`,
    `PushApp` (own hub app id), `PushNames`/`PushNameStore`/`PushNameSync`
    (iOS-style chat naming of data-only pushes). Firebase `google-services.json`
    goes in `app/` (git-ignored).
    `call/CallService` is the call foreground service; `engine/ConnectionWatcher`
    reconnects on network change/app start. `docs/phases/20-push-and-reliability.md`.
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
