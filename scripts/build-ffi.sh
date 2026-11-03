#!/bin/sh
# Assembles the Sphinx core bindings for Android:
#   sphinxrs/src/main/java/uniffi/sphinxrs/sphinxrs.kt - the generated
#     Kotlin bindings
#   sphinxrs/src/main/jniLibs/{arm64-v8a,x86_64}/libsphinxrs.so - the
#     Rust native library, two of the five ABIs sphinx-rs CI builds (the
#     two app/build.gradle.kts's own abiFilters admit: real devices and
#     the emulator, not 32-bit)
#
# sphinxrs/sphinxrs.lock pins them: the sha256 of each .so, the Kotlin
# bindings, then the sphinx-rs release whose assets they are - the exact
# shape peel's own Vendor/sphinxrs.lock pins the Mac/iOS bindings with
# (scripts/build-ffi.sh there), just Android's own artifacts in place of
# the Mac library/Swift bindings/header. sphinx-rs CI (build-all.yml)
# attaches sphinxrs.kt and kotlin-libraries.zip (all five ABIs) to every
# release.
#
#   scripts/build-ffi.sh                downloads the pinned release's files
#                                       (cached in .build/sphinx-core) and
#                                       checks each against the lock
#   scripts/build-ffi.sh --update <tag> downloads another release's files and
#                                       records it in the lock: for moving to
#                                       a new core
#
# Either way it then checks that the core the release pins (sphinx-ffi's
# Cargo.toml at the tag) has upstream's client security fixes, asking GitHub's
# compare API. stakwork/sphinx is private, so that needs a GITHUB_TOKEN that
# can read it. A missing fix only warns, unless PEEL_REQUIRE_CORE_FIXES=1,
# which any build that ships sets: then a missing or unchecked fix stops the
# build.
set -eu

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOCK="$ROOT/sphinxrs/sphinxrs.lock"
CACHE="$ROOT/.build/sphinx-core"
mkdir -p "$CACHE"
die() { echo "build-ffi: $*" >&2; exit 1; }
sha256() { shasum -a 256 "$1" | cut -d' ' -f1; }
get() { # url, to stdout; with GITHUB_TOKEN set, sent authenticated
  if [ -n "${GITHUB_TOKEN:-}" ]; then
    curl -fsSL --proto '=https' --proto-redir '=https' --retry 3 -H "Authorization: Bearer $GITHUB_TOKEN" "$1"
  else
    curl -fsSL --proto '=https' --proto-redir '=https' --retry 3 "$1"
  fi
}
# Release tags only: they go into URLs and file names.
valid_tag() { printf '%s\n' "$1" | grep -qE '^v[0-9]+\.[0-9]+\.[0-9]+$'; }

if [ "${1:-}" = "--update" ]; then
  TAG="${2:-}"
  valid_tag "$TAG" || die "usage: build-ffi.sh --update <sphinx-rs release tag, e.g. v0.8.41>"
  ARM64_SHA=""
else
  [ -f "$LOCK" ] || die "missing $LOCK; run with --update <tag>"
  read -r ARM64_SHA X86_64_SHA KT_SHA TAG EXTRA < "$LOCK"
  if printf '%s\n' "$ARM64_SHA" "$X86_64_SHA" "$KT_SHA" | grep -qvE '^[0-9a-f]{64}$' \
    || ! valid_tag "$TAG" || [ -n "$EXTRA" ]; then
    die "$LOCK should hold three sha256s (arm64-v8a .so, x86_64 .so, Kotlin bindings) and a sphinx-rs release tag"
  fi
fi
fetch() { # url destination
  [ -s "$2" ] && return
  echo "downloading $1" >&2
  get "$1" > "$2.part" || { rm -f "$2.part"; die "could not download $1"; }
  mv "$2.part" "$2"
}
RELEASE="https://github.com/stakwork/sphinx-rs/releases/download/$TAG"
KT="$CACHE/$TAG-sphinxrs.kt"
ZIP="$CACHE/$TAG-kotlin-libraries.zip"
fetch "$RELEASE/sphinxrs.kt" "$KT"
fetch "$RELEASE/kotlin-libraries.zip" "$ZIP"
fetch "https://raw.githubusercontent.com/stakwork/sphinx-rs/$TAG/sphinx-ffi/Cargo.toml" "$CACHE/$TAG-Cargo.toml"

STAGE="$CACHE/$TAG-stage"
rm -rf "$STAGE"
mkdir -p "$STAGE"
unzip -q -o "$ZIP" "out/arm64-v8a/libsphinxrs.so" "out/x86_64/libsphinxrs.so" -d "$STAGE"
ARM64_SO="$STAGE/out/arm64-v8a/libsphinxrs.so"
X86_64_SO="$STAGE/out/x86_64/libsphinxrs.so"
[ -f "$ARM64_SO" ] || die "kotlin-libraries.zip has no out/arm64-v8a/libsphinxrs.so"
[ -f "$X86_64_SO" ] || die "kotlin-libraries.zip has no out/x86_64/libsphinxrs.so"

if [ -z "$ARM64_SHA" ]; then
  mkdir -p "$ROOT/sphinxrs"
  printf '%s %s %s %s\n' "$(sha256 "$ARM64_SO")" "$(sha256 "$X86_64_SO")" "$(sha256 "$KT")" "$TAG" > "$LOCK"
else
  # A release's assets can be replaced; refuse any file that differs from the lock.
  pinned() { # file sha256
    [ "$(sha256 "$1")" = "$2" ] && return
    die "the downloaded $(basename "$1") does not match sphinxrs/sphinxrs.lock"
  }
  pinned "$ARM64_SO" "$ARM64_SHA"
  pinned "$X86_64_SO" "$X86_64_SHA"
  pinned "$KT" "$KT_SHA"
fi
CARGO_TOML="$(cat "$CACHE/$TAG-Cargo.toml")"

# The core rev the release pins must carry upstream's client security fixes
# (merged 2026-09-25, in sphinx v0.6.29) - the same check peel's own
# scripts/build-ffi.sh runs for the Mac/iOS bindings, since both pull from
# the same sphinx-rs release and so the same core.
CORE_REV="$(printf '%s\n' "$CARGO_TOML" | sed -n 's/^sphinx = .*rev = "\([0-9a-f]*\)".*/\1/p')"
MISSING=""
UNCHECKED=""
while read -r commit name; do
  cmp_status=""
  [ -z "$CORE_REV" ] || cmp_status="$(get "https://api.github.com/repos/stakwork/sphinx/compare/$commit...$CORE_REV?per_page=1" 2>/dev/null \
    | plutil -extract status raw -o - - 2>/dev/null)" || cmp_status=""
  case "$cmp_status" in
    ahead|identical) ;;
    behind|diverged) MISSING="$MISSING $name" ;;
    *) UNCHECKED="$UNCHECKED $name" ;;
  esac
done <<'EOF'
c3ddbb7d02f26c70a96c50384dbd776d403fc229 SR-016(amount)
031427c3062722a1e70be5437f084cb610c8b6f4 SR-016(keyx)
9f784bc635e0d9b5a70e2f942216f697d04cd23a SR-017
a962ce1f3ca7d00cece2743e21ac27d38acc81ee SR-018
948f8cc1b0346000600858fbe51b522d09179cee SR-019
c1a46181f38e6085fdef51e84d9a28b09b102cf0 SR-020
480b3ae35fe511fb777b2f2ab3d72b0683ebb1e6 SR-022
EOF
if [ -n "$MISSING$UNCHECKED" ]; then
  LEVEL=WARNING
  [ "${PEEL_REQUIRE_CORE_FIXES:-}" = 1 ] && LEVEL=ERROR
  {
    echo "************************************************************"
    echo "$LEVEL: the core sphinx-rs $TAG pins (sphinx ${CORE_REV:-unknown})"
    [ -z "$MISSING" ] || echo "  lacks upstream's client security fixes:$MISSING"
    [ -z "$UNCHECKED" ] || echo "  could not be checked for:$UNCHECKED"
    [ -z "$UNCHECKED" ] || echo "  (GitHub's compare API failed; stakwork/sphinx is private, so set"
    [ -z "$UNCHECKED" ] || echo "  GITHUB_TOKEN to a token that can read it)"
    echo "  Fixed upstream in v0.6.29; pin a sphinx-rs release with them."
    echo "************************************************************"
  } >&2
  [ "$LEVEL" = WARNING ] || die "PEEL_REQUIRE_CORE_FIXES=1: refusing a core without the fixes"
fi

mkdir -p "$ROOT/sphinxrs/src/main/jniLibs/arm64-v8a" "$ROOT/sphinxrs/src/main/jniLibs/x86_64"
cp "$ARM64_SO" "$ROOT/sphinxrs/src/main/jniLibs/arm64-v8a/libsphinxrs.so"
cp "$X86_64_SO" "$ROOT/sphinxrs/src/main/jniLibs/x86_64/libsphinxrs.so"
cp "$KT" "$ROOT/sphinxrs/src/main/java/uniffi/sphinxrs/sphinxrs.kt"
rm -rf "$STAGE"

echo "bindings ready: sphinx-rs $TAG (core ${CORE_REV:-unknown})"
