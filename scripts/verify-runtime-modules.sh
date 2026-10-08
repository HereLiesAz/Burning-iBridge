#!/usr/bin/env bash
# Assert that packaged Compose application images include required JDK modules.
# No app launch/display server needed. Works on Linux and macOS build runners.
set -euo pipefail
root="${1:-build/compose/binaries/main}"
if [[ ! -d "$root" ]]; then
  echo "::error::Compose app output not found: $root" >&2
  exit 1
fi
list="$(mktemp)"
trap 'rm -f "$list"' EXIT
find "$root" -type f -name release -print > "$list"
if [[ ! -s "$list" ]]; then
  echo "::error::No jlink runtime release metadata found under $root" >&2
  exit 1
fi
checked=0
while IFS= read -r file; do
  grep -q '^MODULES=' "$file" || continue
  checked=$((checked+1))
  for module in java.net.http java.desktop jdk.crypto.ec; do
    if ! grep '^MODULES=' "$file" | grep -Eq "(^|[[:space:]])${module}([[:space:]\"]|$)"; then
      echo "::error::Bundled runtime $file is missing $module" >&2
      exit 1
    fi
  done
  echo "Verified bundled runtime modules: $file"
done < "$list"
if ((checked == 0)); then
  echo "::error::No packaged runtime with MODULES= found under $root" >&2
  exit 1
fi
echo "PASS: $checked packaged runtime(s) contain java.net.http, java.desktop and jdk.crypto.ec"
