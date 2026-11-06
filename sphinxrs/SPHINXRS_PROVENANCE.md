# libsphinxrs.so provenance

Source repo: https://github.com/stakwork/sphinx-rs

Built from sphinx-rs commit: 0a32648e29f0816053398fd7056ba56d8ccfd2e6

That commit is the `v0.8.42` release tag — also what `sphinxrs/sphinxrs.lock`
pins (`scripts/build-ffi.sh`, which is how these binaries actually got here:
downloaded from that release's own GitHub assets, not built locally). The
lock is the automation-friendly record (a tag plus checksums
`build-ffi.sh` checks on every run); this file is the human-readable one —
keep both in step when either changes.

Binaries (`src/main/jniLibs/`): `arm64-v8a/libsphinxrs.so`, `x86_64/libsphinxrs.so`.
Checksums: `jniLibs.sha256`.

## How to update

Run `scripts/build-ffi.sh --update <tag>` (downloads the release's own
`sphinxrs.kt`/`.so` files and rewrites `sphinxrs/sphinxrs.lock`), then:

1. Run `scripts/update-sphinxrs-checksums.sh`.
2. Set `Built from sphinx-rs commit:` above to the new release tag's commit
   (`gh api repos/stakwork/sphinx-rs/tags --jq '.[] | select(.name=="<tag>") | .commit.sha'`).
3. Commit the `.so` files, `sphinxrs.kt`, `sphinxrs.lock`, `jniLibs.sha256`
   and this file together.

## How to verify

```
bash scripts/verify-sphinxrs-checksums.sh
```

This runs `sha256sum -c` in `sphinxrs/` and fails on any mismatch or unlisted `.so`.
It runs in CI (PR checks and release). While the commit is a placeholder it only warns.

## Not done yet / follow-ups

- Check 16KB page-alignment of the `.so` files.
- Build the library in CI from a pinned sphinx-rs commit instead of committing binaries.
