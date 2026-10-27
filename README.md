# Peel (Android)

A from-scratch native Sphinx client for Android, Kotlin + Jetpack Compose,
sharing the same Rust core as the Mac and iPhone apps in the `peel`
repository. See that repository's `docs/PLAN.md` for the architecture this
is meant to grow into (PeelCore's `Engine`, GRDB-equivalent storage, MQTT
transport) and `docs/VISION.md` for why.

## Layout

- `app/` — the Compose UI and app module (`com.sphinx.peel`).
  - `account/` — `Account` (pure helpers over sphinxrs: generate, validate,
    the word check), `SecretStore` and `PinBox` (the twelve words sealed
    under a PIN, ported from PeelCore's `SecretStore.swift`: PBKDF2 at
    2,500,000 iterations, AES-GCM, same byte layout as the Mac/iPhone
    Keychain blob). Kept in the app-private file store, not wrapped in
    Android Keystore yet.
  - `ui/onboarding`, `ui/lock`, `ui/home` — new account or restore, the PIN
    lock screen, and a placeholder account screen (shows the derived
    pubkey; no server connection yet). Ported from PeelCore's
    `Onboarding.swift` and `PinPad.swift`, minus the invite step and the
    restore-progress screen, which need the account engine.
  - `ui/components` — `PinPad`, `NumberedWords`, `WordEntry`,
    `WordCheckBoxes`: the shared pieces those screens are built from.
  - `storage/` — `Storage`: the `coreState` (opaque entries the Rust core
    owns) and `setting` (this account's own config) tables, one SQLite
    file per account, written through a single `Commit` per transaction.
    Ported from PeelCore's `Storage.swift` / `Storage+Writes.swift`; no
    `message`, `contact` or `tribe` tables yet.
  - `engine/` — `Engine`: the core contract (docs/PLAN.md, "Why") — a core
    call takes the seed, a unique time and the whole state, and returns
    what changed, committed to `Storage` before the call returns. Ported
    from PeelCore's `Engine.swift`, scoped to what `Storage` holds: no
    message, contact, tribe or payment bookkeeping yet, so this covers a
    fraction of what the Mac/iPhone `commit` does. `StateCodec` is the
    core's MessagePack state-map format; `UniqueClock`, the per-account
    clock every call needs. `ServerAddress` and `Transport` (the MQTT
    connection, on the HiveMQ MQTT client) are ready but unused: nothing
    in `Engine` makes a server call yet, so there is nothing to connect
    for. Not exercised against a broker — see "What's not here yet".
- `sphinxrs/` — the generated UniFFI Kotlin bindings to the Rust core
  (package `uniffi.sphinxrs`) plus the native `libsphinxrs.so` for
  `arm64-v8a` and `x86_64`. Generated, not hand-written; see below to
  rebuild it.

## What's not here yet

No server call: `Engine` can run the core's local-only calls (right now,
just `initialSetup`) and commit what they return, but nothing in it talks
to a broker, so `Transport` has never connected to anything, real or
local. An account made or restored here is real (the same core, the same
twelve words, properly sealed under a PIN) but local only, and the home
screen is a placeholder that re-derives the account's own pubkey to prove
the bridge works, not a chat list. See the `peel` repository's
`docs/PLAN.md` for the architecture this grows into next.

Also not here: anything that needs the native sphinxrs library to test
against (a plain JVM unit test, even under Robolectric, has no macOS build
of it to load — only the Android `.so`s), so `Account` and `Engine`'s own
logic are exercised only through what's pure Kotlin (`StateCodec`,
`ServerAddress`, the word-splitting helpers). Building a host `.dylib` and
pointing JNA's `jna.library.path` at it would unlock real unit tests for
the rest; not done, since it's a test-infrastructure decision on its own.

## Build

Needs the Android SDK (`local.properties` → `sdk.dir`) and a JDK 17.

```sh
./gradlew assembleDebug
./gradlew installDebug   # with a device or emulator connected
```

## Regenerating the sphinxrs bridge

`sphinxrs/src/main/java/uniffi/sphinxrs/sphinxrs.kt` and the `.so` files in
`sphinxrs/src/main/jniLibs/` come from `stakwork/sphinx-rs`'s `sphinx-ffi`
crate (`sphinx-ffi/src/sphinxrs.udl`), the same core the Mac/iOS
`Sources/Sphinxrs` bindings are generated from
(see `peel/scripts/build-ffi.sh`). `sphinx-ffi` ships its own
`build-android.sh`, but that one uses `cross` (Docker) and builds all five
ABIs; this was built locally instead, with `cargo-ndk`, for just the two
ABIs that matter for development (a real device, and the emulator):

```sh
# toolchain, once:
brew install --cask android-ndk
cargo install cargo-ndk
rustup target add aarch64-linux-android x86_64-linux-android

# from a sphinx-rs checkout, sphinx-ffi/:
cargo run --features=uniffi/cli --bin uniffi-bindgen generate src/sphinxrs.udl --language kotlin
sed -i '' 's/return "uniffi_sphinxrs"/return "sphinxrs"/' src/uniffi/sphinxrs/sphinxrs.kt
export ANDROID_NDK_HOME="/opt/homebrew/share/android-ndk"
cargo ndk -t arm64-v8a -t x86_64 -o jniLibs build --release --features=uniffi/cli
```

Then copy `src/uniffi/sphinxrs/sphinxrs.kt` and `jniLibs/*/libsphinxrs.so`
into this repo's `sphinxrs/src/main/`.

The `.so` files here are unstripped (`sphinx-ffi`'s release profile sets
`debug = true`), which is why they're tens of MB; `strip` them for anything
other than local development.

## Verifying the bridge

`MainActivity` derives the keys for the standard "abandon ... about" test
mnemonic through `sphinxrs` and checks the pubkey against the same vector
Peel's own Swift tests use (`peel/Tests/PeelTestSupport/Fixtures.swift`,
`abandon`): `03653e90c1ce4660fd8505dd6d643356e93cfe202af109d382787639dd5890e87d`
for `nodeKeys(net: "bitcoin", ...)`. A match on screen means Kotlin, JNI,
and the Rust core are wired correctly end to end.
