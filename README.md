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
crate, the same core the Mac/iOS `Sources/Sphinxrs` bindings are generated
from. `sphinxrs/sphinxrs.lock` pins the exact release (the sha256 of each
`.so` and the Kotlin bindings, then the tag) — the same shape `peel`'s own
`Vendor/sphinxrs.lock` pins the Mac/iOS bindings with
(`peel/scripts/build-ffi.sh`), just Android's own artifacts.

`stakwork/sphinx-rs` CI (`build-all.yml`) attaches `sphinxrs.kt` and
`kotlin-libraries.zip` (all five Android ABIs) to every release, so
nothing here needs a local Rust/NDK toolchain or `cargo-ndk` — just
download the pinned release's assets and check them against the lock:

```sh
./scripts/build-ffi.sh                 # verifies the pinned release against the lock
./scripts/build-ffi.sh --update v0.8.42 # moves to another release, rewrites the lock
```

Either way it also checks that the core commit the release pins has
upstream's known client security fixes (the same check `peel`'s own
script runs) — set `GITHUB_TOKEN` to a token that can read the private
`stakwork/sphinx` repo, or the check can only warn "unchecked" instead of
actually confirming them.

The `.so` files are unstripped (`sphinx-ffi`'s release profile sets
`debug = true`), which is why they're tens of MB; Android's own release
build already tries to strip them and falls back to packaging them as-is
when it can't (`stripReleaseDebugSymbols`'s own "Unable to strip..."
message is expected, not an error).

## Verifying the bridge works

`./gradlew testDebugUnitTest` runs what can run on a plain JVM (see
`docs/phases/01-foundation.md`, "Learned", for why `Account` and `Engine`
aren't in that set yet). To see the bridge actually move bytes through
the Rust core on a device: make an account in onboarding, then the home
screen shows the pubkey `sphinxrs` derived from its real twelve words,
proving Kotlin → JNI → the Rust core is wired correctly end to end, not
a canned value.
