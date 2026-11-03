# libsphinxrs.so provenance

Source repo: https://github.com/stakwork/sphinx-rs

Built from sphinx-rs commit: TODO_FILL_IN_FULL_40_CHAR_SHA

> **NOTE: the commit of the current binaries is NOT yet recorded.**
> The owner is rebuilding the library from the latest sphinx-rs commit and will
> push new binaries and fill in the commit above. Until then the checksums in
> `jniLibs.sha256` only detect changes; they do not prove origin.

Binaries (`src/main/jniLibs/`): `arm64-v8a/libsphinxrs.so`, `x86_64/libsphinxrs.so`.
Checksums: `jniLibs.sha256`.

## How to update

1. Replace the `.so` files under `sphinxrs/src/main/jniLibs/`.
2. Run `scripts/update-sphinxrs-checksums.sh`.
3. Set `Built from sphinx-rs commit:` above to the full 40-char SHA.
4. Commit the `.so` files, `jniLibs.sha256` and this file together.

## How to verify

```
bash scripts/verify-sphinxrs-checksums.sh
```

This runs `sha256sum -c` in `sphinxrs/` and fails on any mismatch or unlisted `.so`.
It runs in CI (PR checks and release). While the commit is a placeholder it only warns.

## Not done yet / follow-ups

- Check 16KB page-alignment of the `.so` files.
- Build the library in CI from a pinned sphinx-rs commit instead of committing binaries.
