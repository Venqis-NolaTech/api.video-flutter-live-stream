#!/usr/bin/env bash
# Verify native libraries in a release APK/AAB are compatible with 16 KB page size devices.
# See: https://developer.android.com/guide/practices/page-sizes
set -euo pipefail

ARCHS="arm64-v8a x86_64"
MIN_ALIGN_HEX="0x4000"

usage() {
  echo "Usage: $0 <path-to.apk|path-to.aab>" >&2
  exit 1
}

[[ $# -eq 1 ]] || usage
INPUT="$1"

READELF=""
for cand in llvm-readelf llvm-readelf-19 llvm-readelf-18 llvm-readelf-17; do
  if command -v "$cand" &>/dev/null; then
    READELF="$cand"
    break
  fi
done

if [[ -z "$READELF" ]]; then
  echo "llvm-readelf not found; install LLVM (Android NDK ships it) and ensure it is on PATH." >&2
  exit 2
fi

TMP="${TMPDIR:-/tmp}/streampack_16k_verify_$$"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP"

unzip -q -o "$INPUT" "lib/*/*.so" -d "$TMP" || true

failed=0
while IFS= read -r -d '' so; do
  rel="${so#"$TMP"/}"
  align_max="$("$READELF" -l "$so" 2>/dev/null | awk '
    /^  LOAD/ {
      gsub(/0x/, "", $NF)
      val = ("0x" $NF) + 0
      if (val > max) max = val
    }
    END { printf "0x%x", max+0 }')"
  abis_ok=0
  for a in $ARCHS; do
    if [[ "$rel" == lib/$a/*.so ]]; then
      abis_ok=1
      break
    fi
  done
  if [[ "$abis_ok" -eq 0 ]]; then
    continue
  fi
  if [[ $((align_max)) -lt $((MIN_ALIGN_HEX)) ]]; then
    echo "FAIL $rel max LOAD align $align_max (need >= $MIN_ALIGN_HEX)" >&2
    failed=1
  else
    echo "OK   $rel max LOAD align $align_max"
  fi
done < <(find "$TMP/lib" -name '*.so' -print0 2>/dev/null)

if [[ "$failed" -ne 0 ]]; then
  echo "" >&2
  echo "One or more native libs are below 16 KB LOAD alignment. Rebuild with NDK r28+ and AGP 8.5.1+." >&2
  exit 1
fi

echo ""
echo "zipalign check (APK only; requires build-tools 35+):"
if [[ "$INPUT" == *.apk ]] && command -v zipalign &>/dev/null; then
  zipalign -v -c -P 16 4 "$INPUT" && echo "zipalign OK" || { echo "zipalign FAILED" >&2; exit 1; }
else
  echo "Skipping zipalign (not an APK or zipalign not on PATH)." >&2
fi

echo ""
echo "Optional AAB: bundletool dump config --bundle=your.aab | grep -i alignment"
