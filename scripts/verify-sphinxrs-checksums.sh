#!/usr/bin/env bash
# Verifies prebuilt libsphinxrs.so files against sphinxrs/jniLibs.sha256.
set -eu

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root/sphinxrs"

provenance="SPHINXRS_PROVENANCE.md"
if grep -E '^Built from sphinx-rs commit:.*TODO_FILL_IN' "$provenance" >/dev/null 2>&1; then
  msg="sphinx-rs commit for the prebuilt libsphinxrs.so is not recorded yet (placeholder in sphinxrs/$provenance)."
  if [ -n "${GITHUB_ACTIONS:-}" ]; then
    echo "::warning::$msg"
  else
    echo "WARNING: $msg"
  fi
fi

fail=0
sha256sum -c jniLibs.sha256 || fail=1

# Any .so on disk that is not listed is also a failure.
while IFS= read -r f; do
  if ! grep -qF -- "  $f" jniLibs.sha256; then
    echo "Not listed in jniLibs.sha256: $f"
    fail=1
  fi
done < <(find src/main/jniLibs -type f -name '*.so' | LC_ALL=C sort)

if [ "$fail" -ne 0 ]; then
  cat >&2 <<'M'
ERROR: the prebuilt libsphinxrs.so no longer match sphinxrs/jniLibs.sha256.
If the change is intentional, run scripts/update-sphinxrs-checksums.sh and
update the commit in sphinxrs/SPHINXRS_PROVENANCE.md.
Otherwise the binaries were modified unexpectedly: investigate.
M
  exit 1
fi
echo "sphinxrs native library checksums OK"
