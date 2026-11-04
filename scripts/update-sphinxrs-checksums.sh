#!/usr/bin/env bash
# Regenerates sphinxrs/jniLibs.sha256 for every .so under sphinxrs/src/main/jniLibs.
set -eu

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root/sphinxrs"

find src/main/jniLibs -type f -name '*.so' | LC_ALL=C sort | xargs sha256sum > jniLibs.sha256.tmp
mv jniLibs.sha256.tmp jniLibs.sha256

echo "Wrote sphinxrs/jniLibs.sha256:"
cat jniLibs.sha256
echo
echo "REMINDER: update 'Built from sphinx-rs commit:' in sphinxrs/SPHINXRS_PROVENANCE.md"
echo "and commit the .so files, jniLibs.sha256 and the provenance file together."
