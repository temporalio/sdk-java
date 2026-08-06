#!/usr/bin/env bash

set -euo pipefail

root=$(cd "$(dirname "$0")/../../.." && pwd)
script=$root/.github/scripts/temporal-release/package-native-artifact.sh
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
commit=$(git -C "$root" rev-parse HEAD)
candidate=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
notes=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb

package() {
  local platform=$1 asset_platform=$2 extension=$3 binary_name=$4 output=$5
  mkdir "$work/prebuilt-$platform" "$output"
  printf 'native executable bytes' >"$work/prebuilt-$platform/$binary_name"
  jq -n --arg candidateDigest "$candidate" --arg commitSha "$commit" \
    --arg platform "$platform" --arg releaseNotesPath releases/v1.2.3 \
    --arg releaseNotesSha256 "$notes" --arg tag v1.2.3 \
    --arg trustedAutomationCommit "$commit" --arg version 1.2.3 \
    '{candidateDigest:$candidateDigest,commitSha:$commitSha,platform:$platform,
      releaseNotesPath:$releaseNotesPath,releaseNotesSha256:$releaseNotesSha256,tag:$tag,
      trustedAutomationCommit:$trustedAutomationCommit,version:$version}' \
    >"$work/prebuilt-$platform/metadata.json"
  RELEASE_ASSET_PLATFORM=$asset_platform RELEASE_ARCHIVE_EXTENSION=$extension \
    RELEASE_BINARY_NAME=$binary_name RELEASE_CANDIDATE_DIGEST=$candidate \
    RELEASE_COMMIT=$commit RELEASE_NOTES_FILE=releases/v1.2.3 RELEASE_NOTES_SHA256=$notes \
    RELEASE_OUTPUT_DIR=$output RELEASE_PLATFORM=$platform \
    RELEASE_PREBUILT_NATIVE_DIR="$work/prebuilt-$platform" RELEASE_TAG=v1.2.3 \
    RELEASE_VERSION=1.2.3 TRUSTED_AUTOMATION_COMMIT=$commit TRUSTED_AUTOMATION_ROOT=$root \
    "$script" >/dev/null
}

package linux-amd64 linux_amd64 .tar.gz temporal-test-server "$work/linux-one"
mv "$work/prebuilt-linux-amd64" "$work/first-linux-input"
package linux-amd64 linux_amd64 .tar.gz temporal-test-server "$work/linux-two"
cmp "$work/linux-one/temporal-test-server_1.2.3_linux_amd64.tar.gz" \
  "$work/linux-two/temporal-test-server_1.2.3_linux_amd64.tar.gz"
tar -tzf "$work/linux-one/temporal-test-server_1.2.3_linux_amd64.tar.gz" |
  grep -Fxq 'temporal-test-server_1.2.3_linux_amd64/temporal-test-server'

package windows-amd64 windows_amd64 .zip temporal-test-server.exe "$work/windows"
unzip -Z1 "$work/windows/temporal-test-server_1.2.3_windows_amd64.zip" |
  grep -Fxq 'temporal-test-server_1.2.3_windows_amd64/temporal-test-server.exe'
