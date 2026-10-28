# Phase 1 — Foundation

## Status (2026-10-27)

**Built:** the project scaffold (Kotlin + Compose, AGP 9's built-in Kotlin
support), a working `sphinxrs` FFI bridge (UniFFI Kotlin bindings, native
`libsphinxrs.so` for `arm64-v8a`/`x86_64`, built locally with `cargo-ndk`),
onboarding (new account or restore), PIN-sealed mnemonic storage, a lock
screen, the `Storage` key-value layer (`coreState`, `setting`), and an
`Engine` that runs one core call (`initialSetup`) and commits what it
returns. Onboarding now calls that `Engine.initialSetup` itself (new
account or restore, right after sealing the mnemonic), so creating or
restoring an account prepares real core state, not just a sealed mnemonic
sitting next to an empty database; the home screen opens the same `Engine`
and shows its `accountKey` and `isRegistered` (always false so far — no
server has run to set it). "Forget this account" wipes both `SecretStore`
and `Storage`, so a new account on the same device starts from nothing.
`Transport` (MQTT, HiveMQ client) exists and compiles against the real
library but has never connected to anything.

**Open (as of this phase; see [phases/02-connecting.md](02-connecting.md)
for what changed next):** no server connection — `Engine` makes no call
that needs one, so `Transport` is unexercised. No chat UI, no
`message`/`contact`/`tribe` tables. `Account` and `Engine` have no unit
tests: both need the native `sphinxrs` library, and there is no host
(macOS) build of it for a plain JVM test (even under Robolectric) to
load, only the Android `.so`s.

**Decided:** `SQLiteOpenHelper` over Room/SQLDelight for `Storage` — two
key-value tables don't need an ORM, and it avoids pulling KSP into AGP 9's
built-in-Kotlin setup for no real benefit yet (see the Journal). AndroidX
library versions pinned one cycle behind "latest", because the literal
latest already targets a `compileSdk` not yet released stable. AGP 9.4.1
with built-in Kotlin, not AGP 8.x with the separate `org.jetbrains.kotlin.android`
plugin — AndroidX now requires it.

## Journal

The build log, kept as written.

Started from a chat asking what to build for a Peel Android client, given
the Mac/iPhone PR history as a guide: Mac went core engine → Keychain/PIN
onboarding → chat UI, in that order, so Android followed the same order
for its first three sessions.

**Scaffold.** `com.android.application`/`com.android.library`, AGP 9.4.1
(its built-in Kotlin support, so no `org.jetbrains.kotlin.android` plugin),
Gradle 9.8.2, Kotlin 2.4.21, Compose. AGP 9 was the first real snag: the
newest AndroidX releases (`core-ktx` 1.19.x, `activity-compose` 1.13.x)
already require it and refuse the older `kotlin-android` plugin outright.
A second snag followed from the fix: AndroidX's literal "latest" versions
target `compileSdk` 37, which isn't a released stable SDK yet (only 36 is
installed) — versions were pinned one cycle back instead of chasing it.

**The sphinxrs bridge.** `stakwork/sphinx-rs`'s `sphinx-ffi` crate already
has a `build-android.sh`, but it uses `cross` (Docker) and builds all five
ABIs. Built locally instead: `cargo-ndk` (no Docker needed), NDK via
`brew install --cask android-ndk`, two ABIs (`arm64-v8a` for a real
device, `x86_64` for the emulator). Verified with a real value, not just
"it compiles": derived the keys for the standard "abandon ... about" test
mnemonic through the bridge and checked the pubkey against the same
vector `peel`'s own Swift tests use.

**Onboarding and `SecretStore`.** Ported from `Onboarding.swift`,
`PinPad.swift`, `PhoneControls.swift` and `SecretStore.swift`, minus the
invite step and the restore-progress screen (both need a server
connection, which doesn't exist yet). `PinBox`'s PBKDF2 derivation (same
2,500,000 iterations as the Swift one, "slow on purpose") turned out slow
enough on a phone-class CPU to need moving off the main thread —
`PinBoxTest` running on this Mac takes ~5s per seal/open pair, which is
what caught it before a device ever ran it.

**Storage.** `coreState` and `setting`, ported from `Storage.swift` /
`Storage+Migrations.swift` `v1`. Room was the first instinct (it's the
default for a new Android project), but for two key-value tables it would
have meant adding KSP as a second annotation-processor toolchain on top of
AGP 9's already-new built-in Kotlin, for no benefit these two tables
need. Plain `SQLiteOpenHelper` instead — closer to what `Storage.swift`
itself does for `coreState` anyway (raw SQL, not GRDB's record macros).
Robolectric added so `StorageTest` exercises the real
`android.database.sqlite`, not a fake, without needing a device.

**Engine and Transport.** `StateCodec` (the core's MessagePack state-map
format) and `UniqueClock` ported byte-for-byte and test-for-test from
`StateCodec.swift` and `Engine.swift`'s `UniqueClock`. `Engine.run`
mirrors `Engine.swift`'s `run`/`commit` envelope, scoped down to what
`Storage` holds: no message/contact/tribe/payment bookkeeping, which is
most of what `Engine.swift`'s real `commit` does. `ServerAddress` and
`Transport` (MQTT) ported from `Transport.swift` onto the HiveMQ MQTT
client (Eclipse Paho's Android client needs a bound `Service`; this
doesn't). Pulling it in needed a packaging fix (Netty's jars collide on
two `META-INF` files) and the `INTERNET`/`ACCESS_NETWORK_STATE`
permissions. Compiles and type-checks against the real library; not
connected to a broker, real or local — `Engine` makes no call that needs
one yet.

**Onboarding meets the Engine.** Onboarding and `Engine`/`Storage` were
built in the same session but never actually touched each other:
`OnboardingScreen` sealed the mnemonic and stopped there. Closed the gap:
`OnboardingScreen` now builds an `Engine` and calls `initialSetup` right
after `SecretStore.save`, off the main thread with it (same
`Dispatchers.Default` block — it's a core call plus a transaction, not
free). `Engine` picked up `deviceID` along the way (read-or-create, like
`Engine.swift`'s `init`), since `initialSetup` needs one and nothing had
owned it yet. `HomeScreen` now opens the real `Engine` instead of
re-deriving a pubkey by hand, and shows `isRegistered` honestly — false,
since nothing has connected to a server to set it. Forgetting an account
had to learn to wipe `Storage` too: with two separate stores keyed by the
same default account name, forgetting only the mnemonic would have let a
freshly created account inherit the previous one's `coreState` — a
different seed instead of none at all. `Storage.deleteAccount` (close the
helper, `Context.deleteDatabase`) and a test for it closes that.

**Clipped descenders in the recovery-word boxes.** Found on a real
device, restoring a real account: letters with descenders (g, p, y) were
cut off at the bottom of each numbered word box. Material3's `TextField`
carries its own ~56dp minimum height and internal content padding, which
didn't fit inside the 48dp row `NumberedWords` already draws a background
and corner radius for. Replaced it with a bare `BasicTextField` (no
chrome of its own) behind a small shared `WordField` helper, used by both
`WordEntry` and `WordCheckBoxes`.

**The wrong PIN screen, in two ways.** Two separate problems, found from
actually looking at a device rather than the diff. First, onboarding's
PIN step used the full drawn `PinPad` (the lock screen's circular
keypad) with no width modifier, so it wrapped its content and sat at the
left edge instead of centering — the second, deeper problem: `PinPad`
never belonged in onboarding at all. Reading the actual Swift source
found two distinct components where this port had used one — `PinPad.swift`'s
doc comment says plainly it's "the PIN as the lock screen asks for
it... styled like the system's passcode keys," while onboarding uses
`PinBoxes` (`PhoneControls.swift`): an invisible real text field wired
to the OS's own number-pad keyboard, under six boxes that just show a
dot once a digit lands. Built `PinBoxes` to match and swapped onboarding
to it; `PinPad` now stays exactly where it was always meant to be, the
lock screen.

## Done

- Gradle project: `app` (Compose UI) and `sphinxrs` (generated UniFFI
  bindings, native libs) modules, version catalog, AGP 9.4.1.
- `sphinxrs` bridge: generated Kotlin bindings, `libsphinxrs.so` for
  `arm64-v8a`/`x86_64`, verified against a known test vector.
- `account/Account.kt`: generate, validate, normalise, the word check —
  pure helpers over `sphinxrs`, ported from `Engine+Account.swift`.
- `account/SecretStore.kt`: `PinBox` (PBKDF2 + AES-GCM, `peel`'s
  Keychain blob format) and a file-backed `SecretStore`.
- `ui/onboarding`, `ui/lock`, `ui/home`: new account, restore, PIN
  lock, an account screen that opens a real `Engine`. Onboarding's PIN
  step uses `ui/components/PinPad.kt`'s `PinBoxes` (system keyboard,
  boxed dots); the lock screen keeps `PinPad`'s drawn keypad — the same
  split PeelCore's own `PinBoxes`/`PinPad` make.
- `storage/Storage.kt`, `storage/Setting.kt`: `coreState` and `setting`,
  one SQLite file per account, atomic `Commit`, `deleteAccount`.
- `engine/StateCodec.kt`, `engine/UniqueClock.kt`, `engine/Engine.kt`: the
  core contract envelope, `deviceID`, one call wired (`initialSetup`) —
  and now actually called, from onboarding.
- `engine/ServerAddress.kt`, `engine/Transport.kt`: MQTT connection on
  the HiveMQ client, not yet exercised.
- Tests: `PinBoxTest`, `AccountTest`, `StorageTest` (Robolectric, now
  including `deleteAccount`), `StateCodecTest`, `ServerAddressTest`. 23
  tests, all passing.

## Learned

- AGP 9's built-in Kotlin support (no `org.jetbrains.kotlin.android`
  plugin) is not optional once AndroidX's newest releases are in the
  dependency graph; they declare a minimum AGP version that requires it.
- `cargo-ndk` is a complete substitute for `sphinx-ffi`'s own
  `build-android.sh` (which uses `cross`/Docker) when Docker isn't
  running and only one or two ABIs are needed for development.
- `rust-toolchain.toml` pins a toolchain per `rustup target add` needs its
  own `--toolchain` flag if it differs from the default; targets added to
  `stable` don't carry over.
- PBKDF2 at a deliberately slow iteration count is slow enough on a real
  CPU (not just "technically blocking") to need its own main-thread
  check, not an assumption that "it's just crypto, it'll be fast."
- Android's per-app private storage (`context.filesDir`) is already
  sandboxed the way macOS's access-group-scoped Keychain is; it is a
  reasonable `SecretStore` backing on its own, with the PIN-derived
  encryption as the real wall either way.
- Netty (via the HiveMQ MQTT client) ships `META-INF/INDEX.LIST` and
  `META-INF/io.netty.versions.properties` in multiple jars; Android's
  resource merging needs both excluded explicitly.
- Two stores that both default to the same account name (`SecretStore`,
  `Storage`) look like one account from the outside, but "forget the
  account" has to clear both on purpose — nothing enforces that they stay
  in sync, and leaving one behind doesn't fail loudly, it just quietly
  hands the next account someone else's `coreState`.
- Material3's `TextField` is not a plain text box: it has its own
  minimum height and padding baked in for a label/placeholder layout it
  isn't using here, and that's invisible until a real device clips real
  text against a container sized for something shorter. `BasicTextField`
  inside an already-styled container avoids fighting a second set of
  chrome for one that was never wanted.
- A porting note that later turned out wrong ("ported from PinPad.swift")
  is worth re-checking against the actual current source the moment
  something about the result looks off, not patching around — the real
  problem here wasn't sizing, it was having copied the wrong component
  for the screen in the first place.
