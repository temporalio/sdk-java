#!/usr/bin/env bash

set -euo pipefail

# Report an operational packaging failure.
fail() { echo "package-native-artifact: $*" >&2; exit 1; }
# Report an immutable input or artifact mismatch.
conflict() { echo "package-native-artifact: immutable artifact conflict: $*" >&2; exit 42; }

required=(
  RELEASE_ASSET_PLATFORM RELEASE_ARCHIVE_EXTENSION RELEASE_BINARY_NAME
  RELEASE_COMMIT RELEASE_OUTPUT_DIR RELEASE_PLATFORM RELEASE_PREBUILT_NATIVE_DIR RELEASE_TAG
  TRUSTED_AUTOMATION_ROOT
)
for variable in "${required[@]}"; do
  [[ -n ${!variable:-} ]] || fail "Required value $variable is missing."
done

[[ $(git -C "$TRUSTED_AUTOMATION_ROOT" rev-parse --verify HEAD^{commit}) == \
  "$RELEASE_COMMIT" ]] || conflict "the trusted automation checkout changed."
prebuilt=$RELEASE_PREBUILT_NATIVE_DIR
binary=$prebuilt/$RELEASE_BINARY_NAME
[[ -f $binary && ! -L $binary && -s $binary ]] || conflict "the native executable is invalid."
entries=$(find "$prebuilt" -mindepth 1 -maxdepth 1 -exec basename {} \; | sort | tr '\n' ' ')
[[ $entries == "$RELEASE_BINARY_NAME " ]] ||
  conflict "the native output contains unexpected files."

version=${RELEASE_TAG#v}
archive_root="temporal-test-server_${version}_${RELEASE_ASSET_PLATFORM}"
artifact_name=${archive_root}${RELEASE_ARCHIVE_EXTENSION}
mkdir -p "$RELEASE_OUTPUT_DIR"
[[ -z $(find "$RELEASE_OUTPUT_DIR" -mindepth 1 -maxdepth 1 -print -quit) ]] ||
  fail "The native artifact output directory is not empty."
python3 "$TRUSTED_AUTOMATION_ROOT/.github/release-automation/release_automation/native_artifact.py" \
  "$binary" "$RELEASE_OUTPUT_DIR/$artifact_name" "$archive_root" \
  "$RELEASE_BINARY_NAME" "$RELEASE_PLATFORM"
[[ -s $RELEASE_OUTPUT_DIR/$artifact_name ]] || fail "The native archive was not created."
printf '%s\n' "$artifact_name"
