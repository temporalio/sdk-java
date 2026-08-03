#!/usr/bin/env bash

set -euo pipefail

fail() {
  echo "build-native-and-store: $*" >&2
  exit 1
}

conflict() {
  echo "build-native-and-store: immutable artifact conflict: $*" >&2
  exit 42
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

required=(
  RELEASE_ARTIFACT_BUCKET RELEASE_CANDIDATE_DIGEST RELEASE_COMMIT RELEASE_NOTES_FILE
  RELEASE_NOTES_SHA256 RELEASE_PLATFORM RELEASE_TAG RELEASE_VERSION
  TRUSTED_AUTOMATION_ROOT
)
for variable in "${required[@]}"; do
  [[ -n ${!variable:-} ]] || fail "Required value $variable is missing."
done

[[ $(git rev-parse --verify HEAD^{commit}) == "$RELEASE_COMMIT" ]] ||
  conflict "the checkout is not $RELEASE_COMMIT."
[[ $RELEASE_NOTES_FILE == "releases/$RELEASE_TAG" && -s $RELEASE_NOTES_FILE ]] ||
  conflict "the release-note identity is invalid."
actual_notes_hash=$(sha256_file "$RELEASE_NOTES_FILE")
[[ $actual_notes_hash == "$RELEASE_NOTES_SHA256" ]] ||
  conflict "the release-note checksum changed."

case "$RELEASE_PLATFORM" in
  linux-amd64-musl | linux-amd64 | macos-amd64 | macos-arm64 | linux-arm64 | windows-amd64) ;;
  *) fail "Temporal scheduled an unknown sdk-java release platform." ;;
esac
asset_platform=${RELEASE_PLATFORM//-/_}
[[ $RELEASE_PLATFORM == macos-* ]] && asset_platform="macOS_${RELEASE_PLATFORM#macos-}"
archive_root="temporal-test-server_${RELEASE_VERSION}_${asset_platform}"
if [[ $RELEASE_PLATFORM == windows-amd64 ]]; then
  artifact_name=${archive_root}.zip
else
  artifact_name=${archive_root}.tar.gz
fi
if [[ ${RELEASE_MODE:-temporal} == emergency ]]; then
  [[ ${EMERGENCY_BUILD_ATTEMPT:-} =~ ^[0-9a-f]{64}$ ]] ||
    fail "The immutable emergency build attempt is missing."
  storage_key="sdk-java/emergency-artifacts/$RELEASE_CANDIDATE_DIGEST/$EMERGENCY_BUILD_ATTEMPT/$artifact_name"
else
  storage_key="sdk-java/$RELEASE_CANDIDATE_DIGEST/$artifact_name"
fi

# Activity completion can be lost after upload. Reuse an already committed immutable object
# before rebuilding timestamp-bearing native binaries or archives.
head_object=$(mktemp)
if aws s3api head-object --bucket "$RELEASE_ARTIFACT_BUCKET" --key "$storage_key" \
  >"$head_object" 2>/dev/null; then
  existing=$(mktemp)
  aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$storage_key" "$existing" --no-progress >/dev/null ||
    fail "Unable to read the existing candidate artifact."
  stored_hash=$(jq -r '.Metadata.sha256 // ""' "$head_object")
  stored_size=$(jq -r '.ContentLength' "$head_object")
  [[ $stored_hash =~ ^[0-9a-f]{64}$ && $stored_size =~ ^[1-9][0-9]*$ ]] ||
    conflict "$storage_key has invalid immutable metadata."
  [[ $(sha256_file "$existing") == "$stored_hash" &&
    $(wc -c <"$existing" | tr -d ' ') == "$stored_size" ]] ||
    conflict "$storage_key bytes do not match immutable metadata."
  printf '%s\t%s\t%s\t%s\n' "$artifact_name" "$stored_hash" "$stored_size" "$storage_key"
  exit 0
fi

# Maintenance branches may predate the explicit immutable release overrides. Supply the reviewed
# versioning hook from the frozen automation commit for this build, then restore the checkout.
hook_backup=$(mktemp)
cp gradle/versioning.gradle "$hook_backup"
cp "$TRUSTED_AUTOMATION_ROOT/gradle/versioning.gradle" gradle/versioning.gradle
restore_hook() { cp "$hook_backup" gradle/versioning.gradle; }
trap restore_hook EXIT

case "$RELEASE_PLATFORM" in
  linux-amd64-musl)
    image_id_file=$(mktemp)
    docker_context="$TRUSTED_AUTOMATION_ROOT/.github/release-automation/docker/native-image-musl-java17"
    env -u AWS_ACCESS_KEY_ID -u AWS_SECRET_ACCESS_KEY -u AWS_SESSION_TOKEN \
      -u AWS_REGION -u AWS_DEFAULT_REGION -u ACTIONS_ID_TOKEN_REQUEST_URL \
      -u ACTIONS_ID_TOKEN_REQUEST_TOKEN -u GH_TOKEN \
      docker build --iidfile "$image_id_file" "$docker_context" 1>&2
    docker run --rm -w /github/workspace -v "$PWD:/github/workspace" \
      "$(<"$image_id_file")" \
      ./gradlew "-PreleaseVersion=$RELEASE_VERSION" -PnativeBuild -PnativeBuildMusl \
      :temporal-test-server:nativeCompile 1>&2
    ;;
  linux-amd64 | linux-arm64)
    image_id_file=$(mktemp)
    docker_context="$TRUSTED_AUTOMATION_ROOT/.github/release-automation/docker/native-image-java17"
    env -u AWS_ACCESS_KEY_ID -u AWS_SECRET_ACCESS_KEY -u AWS_SESSION_TOKEN \
      -u AWS_REGION -u AWS_DEFAULT_REGION -u ACTIONS_ID_TOKEN_REQUEST_URL \
      -u ACTIONS_ID_TOKEN_REQUEST_TOKEN -u GH_TOKEN \
      docker build --iidfile "$image_id_file" "$docker_context" 1>&2
    docker run --rm -w /github/workspace -v "$PWD:/github/workspace" \
      "$(<"$image_id_file")" \
      ./gradlew "-PreleaseVersion=$RELEASE_VERSION" -PnativeBuild \
      :temporal-test-server:nativeCompile 1>&2
    ;;
  macos-amd64 | macos-arm64 | windows-amd64)
    env -u AWS_ACCESS_KEY_ID -u AWS_SECRET_ACCESS_KEY -u AWS_SESSION_TOKEN \
      -u AWS_REGION -u AWS_DEFAULT_REGION -u ACTIONS_ID_TOKEN_REQUEST_URL \
      -u ACTIONS_ID_TOKEN_REQUEST_TOKEN -u GH_TOKEN \
      ./gradlew "-PreleaseVersion=$RELEASE_VERSION" -PnativeBuild \
      :temporal-test-server:nativeCompile 1>&2
    ;;
esac

restore_hook
trap - EXIT

git diff --exit-code 1>&2 || conflict "the build modified tracked source files."
binary=temporal-test-server/build/native/nativeCompile/temporal-test-server
[[ $RELEASE_PLATFORM == windows-amd64 ]] && binary=${binary}.exe
[[ -f $binary && ! -L $binary && -s $binary ]] || fail "The native executable is invalid."

stage=$(mktemp -d)
mkdir "$stage/$archive_root"
cp "$binary" "$stage/$archive_root/$(basename "$binary")"
if [[ $RELEASE_PLATFORM == windows-amd64 ]]; then
  (cd "$stage" && zip -qr "$artifact_name" "$archive_root")
else
  chmod 0755 "$stage/$archive_root/$(basename "$binary")"
  tar -C "$stage" -czf "$stage/$artifact_name" "$archive_root"
fi
artifact="$stage/$artifact_name"
sha256=$(sha256_file "$artifact")
size=$(wc -c <"$artifact" | tr -d ' ')

if aws s3api head-object \
  --bucket "$RELEASE_ARTIFACT_BUCKET" \
  --key "$storage_key" >"$head_object" 2>/dev/null; then
  stored_hash=$(jq -r '.Metadata.sha256 // ""' "$head_object")
  stored_size=$(jq -r '.ContentLength' "$head_object")
  existing=$(mktemp)
  aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$storage_key" "$existing" --no-progress >/dev/null ||
    fail "Unable to read the concurrently committed candidate artifact."
  [[ $stored_hash =~ ^[0-9a-f]{64}$ && $stored_size =~ ^[1-9][0-9]*$ &&
    $(sha256_file "$existing") == "$stored_hash" &&
    $(wc -c <"$existing" | tr -d ' ') == "$stored_size" ]] ||
    conflict "$storage_key has invalid immutable bytes or metadata."
  printf '%s\t%s\t%s\t%s\n' "$artifact_name" "$stored_hash" "$stored_size" "$storage_key"
  exit 0
else
  if ! aws s3api put-object \
    --bucket "$RELEASE_ARTIFACT_BUCKET" \
    --key "$storage_key" \
    --body "$artifact" \
    --metadata "sha256=$sha256" \
    --if-none-match '*' >/dev/null; then
    aws s3api head-object \
      --bucket "$RELEASE_ARTIFACT_BUCKET" \
      --key "$storage_key" >"$head_object" || fail "Unable to reconcile the S3 upload."
    stored_hash=$(jq -r '.Metadata.sha256 // ""' "$head_object")
    stored_size=$(jq -r '.ContentLength' "$head_object")
    existing=$(mktemp)
    aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$storage_key" "$existing" --no-progress >/dev/null ||
      fail "Unable to read the candidate artifact that won the upload race."
    [[ $stored_hash =~ ^[0-9a-f]{64}$ && $stored_size =~ ^[1-9][0-9]*$ &&
      $(sha256_file "$existing") == "$stored_hash" &&
      $(wc -c <"$existing" | tr -d ' ') == "$stored_size" ]] ||
      conflict "$storage_key won the race with invalid bytes or metadata."
    printf '%s\t%s\t%s\t%s\n' "$artifact_name" "$stored_hash" "$stored_size" "$storage_key"
    exit 0
  fi
fi

printf '%s\t%s\t%s\t%s\n' "$artifact_name" "$sha256" "$size" "$storage_key"
