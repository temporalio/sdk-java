#!/usr/bin/env bash
set -euo pipefail
# Report an actionable candidate-build failure.
fail() { echo "build-native-binary: $*" >&2; exit 1; }
for name in RELEASE_COMMIT RELEASE_PLATFORM RELEASE_PREBUILT_NATIVE_DIR RELEASE_TAG; do
  [[ -n ${!name:-} ]] || fail "$name is required."
done
for name in TEMPORAL_API_KEY GH_TOKEN ACTIONS_ID_TOKEN_REQUEST_URL ACTIONS_ID_TOKEN_REQUEST_TOKEN; do
  [[ -z ${!name:-} ]] || fail "$name must not reach candidate compilation."
done
[[ $(git rev-parse HEAD) == "$RELEASE_COMMIT" ]] || fail "the checkout changed."
version=${RELEASE_TAG#v}
# Build Linux with the repository's existing native-image containers. The image ID
# returned by Docker avoids maintaining a parallel release-only Docker definition.
build_linux() {
  local dockerfile=$1 image
  shift
  image=$(docker build -q "docker/$dockerfile")
  docker run --rm -w /github/workspace -v "$PWD:/github/workspace" "$image" \
    ./gradlew "-PreleaseVersion=$version" -PnativeBuild "$@" \
    :temporal-test-server:nativeCompile
}

case "$RELEASE_PLATFORM" in
  linux-amd64-musl) build_linux native-image-musl -PnativeBuildMusl ;;
  linux-amd64 | linux-arm64) build_linux native-image ;;
  macos-amd64 | macos-arm64 | windows-amd64)
    ./gradlew "-PreleaseVersion=$version" -PnativeBuild :temporal-test-server:nativeCompile
    ;;
  *) fail "unknown native platform $RELEASE_PLATFORM." ;;
esac

binary=temporal-test-server/build/native/nativeCompile/temporal-test-server
[[ $RELEASE_PLATFORM == windows-amd64 ]] && binary=${binary}.exe
[[ -f $binary && ! -L $binary && -s $binary ]] || fail "the native executable is invalid."
mkdir -p "$RELEASE_PREBUILT_NATIVE_DIR"
[[ -z $(find "$RELEASE_PREBUILT_NATIVE_DIR" -mindepth 1 -print -quit) ]] ||
  fail "the native output directory is not empty."
cp "$binary" "$RELEASE_PREBUILT_NATIVE_DIR/"
