# Peel (Android)

A from-scratch native Sphinx client for Android, Kotlin + Jetpack Compose,
sharing the same Rust core as the Mac and iPhone apps in the `peel`
repository. See that repository's `docs/PLAN.md` for the architecture this
is meant to grow into (PeelCore's `Engine`, GRDB-equivalent storage, MQTT
transport) and `docs/VISION.md` for why.

## Layout

- `app/` — the Compose UI and app module (`com.sphinx.peel`).
- `sphinxrs/` — the generated UniFFI Kotlin bindings to the Rust core
  (package `uniffi.sphinxrs`) plus the native `libsphinxrs.so` for
  `arm64-v8a` and `x86_64`. Generated, not hand-written; see below to
  rebuild it.

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
