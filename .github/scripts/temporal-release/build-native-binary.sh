#!/usr/bin/env bash

set -euo pipefail

# Report a transient or operational build failure.
fail() { echo "build-native-binary: $*" >&2; exit 1; }
# Report an immutable candidate mismatch using the shared conflict exit code.
conflict() { echo "build-native-binary: immutable candidate conflict: $*" >&2; exit 42; }

# Translate Windows checkout paths to the POSIX form expected by Git Bash tools.
native_path() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -u "$1"
  else printf '%s\n' "$1"; fi
}

required=(
  RELEASE_COMMIT RELEASE_PLATFORM
  RELEASE_PREBUILT_NATIVE_DIR RELEASE_TAG
  TRUSTED_AUTOMATION_ROOT
)
for variable in "${required[@]}"; do
  [[ -n ${!variable:-} ]] || fail "Required value $variable is missing."
done

for credential in \
  TEMPORAL_API_KEY GH_TOKEN \
  ACTIONS_ID_TOKEN_REQUEST_URL ACTIONS_ID_TOKEN_REQUEST_TOKEN; do
  [[ -z ${!credential:-} ]] || fail "Credential $credential must not reach candidate compilation."
done

trusted_root=$(native_path "$TRUSTED_AUTOMATION_ROOT")
output_root=$(native_path "$RELEASE_PREBUILT_NATIVE_DIR")
[[ $(git rev-parse --verify HEAD^{commit}) == "$RELEASE_COMMIT" ]] ||
  conflict "the checkout is not $RELEASE_COMMIT."
[[ $(git -C "$trusted_root" rev-parse --verify HEAD^{commit}) == "$RELEASE_COMMIT" ]] ||
  conflict "the trusted automation checkout changed."
notes_file=releases/$RELEASE_TAG
[[ -s $notes_file && ! -L $notes_file ]] || conflict "the release notes are unavailable."
release_version=${RELEASE_TAG#v}

case "$RELEASE_PLATFORM" in
  linux-amd64-musl | linux-amd64 | macos-amd64 | macos-arm64 | linux-arm64 | windows-amd64) ;;
  *) fail "Temporal selected an unknown sdk-java release platform." ;;
esac

hook_backup=$(mktemp)
cp gradle/versioning.gradle "$hook_backup"
cp "$trusted_root/gradle/versioning.gradle" gradle/versioning.gradle
# Restore the candidate's tracked Gradle hook before checking that the build was read-only.
restore_hook() { cp "$hook_backup" gradle/versioning.gradle; }
trap restore_hook EXIT

# Use the repository's existing native-image Docker definitions for Linux builds.
# The optional final arguments select musl without duplicating the build/run boundary.
build_linux() {
  local dockerfile=$1 image_file=$hook_backup.image
  shift
  docker build --iidfile "$image_file" "$trusted_root/docker/$dockerfile" 1>&2
  docker run --rm -w /github/workspace -v "$PWD:/github/workspace" "$(<"$image_file")" \
    ./gradlew "-PreleaseVersion=$release_version" -PnativeBuild "$@" \
    :temporal-test-server:nativeCompile 1>&2
}

case "$RELEASE_PLATFORM" in
  linux-amd64-musl)
    build_linux native-image-musl -PnativeBuildMusl
    ;;
  linux-amd64 | linux-arm64)
    build_linux native-image
    ;;
  macos-amd64 | macos-arm64 | windows-amd64)
    ./gradlew "-PreleaseVersion=$release_version" -PnativeBuild \
      :temporal-test-server:nativeCompile 1>&2
    ;;
esac

restore_hook
trap - EXIT
git diff --exit-code 1>&2 || conflict "the build modified tracked source files."

binary=temporal-test-server/build/native/nativeCompile/temporal-test-server
[[ $RELEASE_PLATFORM == windows-amd64 ]] && binary=${binary}.exe
[[ -f $binary && ! -L $binary && -s $binary ]] || fail "The native executable is invalid."
mkdir -p "$output_root"
[[ -z $(find "$output_root" -mindepth 1 -maxdepth 1 -print -quit) ]] ||
  fail "The native output directory is not empty."
cp "$binary" "$output_root/$(basename "$binary")"
