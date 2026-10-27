# Peel (Android)

A from-scratch native Sphinx client for Android, Kotlin + Jetpack Compose,
sharing the same Rust core as the Mac and iPhone apps in the `peel`
repository. Read `AGENTS.md` before changing anything: it has the project
layout and points at `docs/VISION.md` and `docs/PLAN.md` for why and how
this is meant to grow. `docs/phases/` has what's built, open and decided,
phase by phase, kept current as work lands — that's the place to check
"what's actually here right now," not this file.

## Build

Needs the Android SDK (`local.properties` → `sdk.dir`) and a JDK 17.

```sh
./gradlew assembleDebug
./gradlew installDebug   # with a device or emulator connected
./gradlew testDebugUnitTest
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

## Verifying the bridge works

`./gradlew testDebugUnitTest` runs what can run on a plain JVM (see
`docs/phases/01-foundation.md`, "Learned", for why `Account` and `Engine`
aren't in that set yet). To see the bridge actually move bytes through
the Rust core on a device: make an account in onboarding, then the home
screen shows the pubkey `sphinxrs` derived from its real twelve words,
proving Kotlin → JNI → the Rust core is wired correctly end to end, not
a canned value.
