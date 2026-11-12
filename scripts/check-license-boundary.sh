#!/usr/bin/env bash
# Fail if a forbidden non-MIT/reference/internal path is tracked in the public repo.
set -euo pipefail
cd "$(dirname "$0")/.."

pattern='(^|/)(avs3dec/|libAVS3AudioDec\.so$|libVMFPlatForm\.so$|libavs3self\.so$|libneuralstem_avs3\.so$|cleanroom/|report/)'

hits="$(git ls-files | grep -E "$pattern" || true)"
if [[ -n "$hits" ]]; then
  echo "ERROR: forbidden paths are tracked in the public repo:" >&2
  echo "$hits" >&2
  exit 1
fi

echo "license boundary OK"
