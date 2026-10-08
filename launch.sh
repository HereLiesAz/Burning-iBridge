#!/usr/bin/env bash
# One-command Compose Desktop source launcher (Linux/macOS).
# This bootstraps Gradle, not the third-party bridgeOS binaries.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VERSION=9.7.1
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/burning-ibridge/gradle"
DISTRIBUTION="$CACHE/gradle-$VERSION"
ARCHIVE="$CACHE/gradle-$VERSION-bin.zip"
mkdir -p "$CACHE"

if ! command -v java >/dev/null 2>&1; then
  echo "JDK 21 is required. Install JDK 21 and retry." >&2
  exit 2
fi
java_version="$(java -version 2>&1 | head -1)"
case "$java_version" in
  *'"21.'*|*'version "21"'*) ;;
  *) echo "JDK 21 required; found $java_version" >&2; exit 2;;
esac

if [[ ! -x "$DISTRIBUTION/bin/gradle" ]]; then
  if ! command -v curl >/dev/null 2>&1 || ! command -v unzip >/dev/null 2>&1; then
    echo "Requires curl and unzip to download the Gradle build tool." >&2
    exit 2
  fi
  curl -fL --retry 3 "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip" -o "$ARCHIVE"
  curl -fL --retry 3 "https://services.gradle.org/distributions/gradle-$VERSION-bin.zip.sha256" -o "$ARCHIVE.sha256"
  expected="$(tr -d '\r\n ' < "$ARCHIVE.sha256")"
  if command -v sha256sum >/dev/null 2>&1; then
    actual="$(sha256sum "$ARCHIVE" | cut -d ' ' -f1)"
  else
    actual="$(shasum -a 256 "$ARCHIVE" | cut -d ' ' -f1)"
  fi
  if [[ "$expected" != "$actual" || ! "$actual" =~ ^[a-fA-F0-9]{64}$ ]]; then
    echo "Gradle SHA-256 mismatch; refusing to run archive." >&2
    exit 3
  fi
  unzip -q "$ARCHIVE" -d "$CACHE"
  rm -f "$ARCHIVE" "$ARCHIVE.sha256"
fi

exec "$DISTRIBUTION/bin/gradle" -p "$HERE" run "$@"
