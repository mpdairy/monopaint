#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME}"
: "${MATTELIER_KEYSTORE:?Set the private signing keystore path}"
: "${MATTELIER_KEY_ALIAS:?Set the signing key alias}"
: "${MATTELIER_STORE_PASSWORD:?Set the keystore password}"
: "${MATTELIER_KEY_PASSWORD:?Set the key password}"
export MATTELIER_STORE_PASSWORD MATTELIER_KEY_PASSWORD
release_tools="$ANDROID_HOME/build-tools/34.0.0"
./gradlew :app:assembleRelease :app:lintRelease
mkdir -p dist
release_tmp=$(mktemp -d)
trap 'rm -rf "$release_tmp"' EXIT
"$release_tools/zipalign" -p -f 4 app/build/outputs/apk/release/app-release-unsigned.apk "$release_tmp/aligned.apk"
"$release_tools/apksigner" sign --ks "$MATTELIER_KEYSTORE" --ks-key-alias "$MATTELIER_KEY_ALIAS" \
  --ks-pass env:MATTELIER_STORE_PASSWORD --key-pass env:MATTELIER_KEY_PASSWORD \
  --out "$release_tmp/Mattelier.apk" "$release_tmp/aligned.apk"
"$release_tools/apksigner" verify --verbose "$release_tmp/Mattelier.apk"
"$release_tools/zipalign" -c -p 4 "$release_tmp/Mattelier.apk"
release_version=$("$release_tools/aapt" dump badging "$release_tmp/Mattelier.apk" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")
test -n "$release_version"
cp "$release_tmp/Mattelier.apk" dist/Mattelier.apk
printf '%s\n' "$release_version" > dist/VERSION
(cd dist && sha256sum Mattelier.apk > SHA256SUMS)
