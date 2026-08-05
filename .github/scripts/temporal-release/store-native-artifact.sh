#!/usr/bin/env bash

set -euo pipefail

fail() {
  echo "store-native-artifact: $*" >&2
  exit 1
}

conflict() {
  echo "store-native-artifact: immutable artifact conflict: $*" >&2
  exit 42
}

sha256_file() {
  sha256sum "$1" | awk '{print $1}'
}

# Prints present or absent. Authentication, authorization, throttling, transport, and other
# service failures remain retryable Activity failures instead of masquerading as an absent key.
s3_head_state() {
  local output=$1
  local error_file
  local status
  error_file=$(mktemp)
  if aws s3api head-object --bucket "$RELEASE_ARTIFACT_BUCKET" --key "$storage_key" \
    >"$output" 2>"$error_file"; then
    printf 'present\n'
    return 0
  else
    status=$?
  fi
  if [[ $status -eq 254 ]] &&
    grep -Eq '^An error occurred \((404|NoSuchKey|NotFound)\) when calling the HeadObject operation:' \
      "$error_file"; then
    printf 'absent\n'
    return 0
  fi
  cat "$error_file" >&2
  return "$status"
}

emit_existing() {
  local head_object=$1
  local description=$2
  local existing
  local stored_hash
  local stored_size
  existing=$(mktemp)
  aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$storage_key" "$existing" --no-progress >/dev/null ||
    fail "Unable to read $description."
  stored_hash=$(jq -r '.Metadata.sha256 // ""' "$head_object")
  stored_size=$(jq -r '.ContentLength' "$head_object")
  [[ $stored_hash =~ ^[0-9a-f]{64}$ && $stored_size =~ ^[1-9][0-9]*$ ]] ||
    conflict "$storage_key has invalid immutable metadata."
  [[ $(sha256_file "$existing") == "$stored_hash" &&
    $(wc -c <"$existing" | tr -d ' ') == "$stored_size" ]] ||
    conflict "$storage_key bytes do not match immutable metadata."
  printf '%s\t%s\t%s\n' "$artifact_name" "$stored_hash" "$stored_size"
}

required=(
  RELEASE_ARTIFACT_BUCKET RELEASE_CANDIDATE_DIGEST RELEASE_COMMIT RELEASE_NOTES_FILE
  RELEASE_NOTES_SHA256 RELEASE_PLATFORM RELEASE_PREBUILT_NATIVE_DIR RELEASE_TAG RELEASE_VERSION
  RELEASE_ASSET_PLATFORM RELEASE_ARCHIVE_EXTENSION RELEASE_BINARY_NAME
  TRUSTED_AUTOMATION_COMMIT TRUSTED_AUTOMATION_ROOT
)
for variable in "${required[@]}"; do
  [[ -n ${!variable:-} ]] || fail "Required value $variable is missing."
done
[[ $(git -C "$TRUSTED_AUTOMATION_ROOT" rev-parse --verify HEAD^{commit}) ==
  "$TRUSTED_AUTOMATION_COMMIT" ]] || conflict "the trusted automation checkout changed."

binary_name=$RELEASE_BINARY_NAME
prebuilt="$RELEASE_PREBUILT_NATIVE_DIR"
[[ -f $prebuilt/$binary_name && ! -L $prebuilt/$binary_name && -s $prebuilt/$binary_name ]] ||
  conflict "the prepared native executable is invalid."
[[ -f $prebuilt/metadata.json && ! -L $prebuilt/metadata.json ]] ||
  conflict "the prepared native metadata is invalid."
[[ $(find "$prebuilt" -mindepth 1 -maxdepth 1 -printf '%f\n' | sort | tr '\n' ' ') ==
  "$binary_name metadata.json " ]] || conflict "the prepared artifact contains unexpected files."
jq -e \
  --arg candidateDigest "$RELEASE_CANDIDATE_DIGEST" \
  --arg commitSha "$RELEASE_COMMIT" \
  --arg platform "$RELEASE_PLATFORM" \
  --arg releaseNotesPath "$RELEASE_NOTES_FILE" \
  --arg releaseNotesSha256 "$RELEASE_NOTES_SHA256" \
  --arg tag "$RELEASE_TAG" \
  --arg trustedAutomationCommit "$TRUSTED_AUTOMATION_COMMIT" \
  --arg version "$RELEASE_VERSION" \
  'keys == ["candidateDigest","commitSha","platform","releaseNotesPath",
    "releaseNotesSha256","tag","trustedAutomationCommit","version"] and
   .candidateDigest == $candidateDigest and .commitSha == $commitSha and
   .platform == $platform and .releaseNotesPath == $releaseNotesPath and
   .releaseNotesSha256 == $releaseNotesSha256 and .tag == $tag and
   .trustedAutomationCommit == $trustedAutomationCommit and .version == $version' \
  "$prebuilt/metadata.json" >/dev/null || conflict "the prepared artifact identity changed."

archive_root="temporal-test-server_${RELEASE_VERSION}_${RELEASE_ASSET_PLATFORM}"
artifact_name=${archive_root}${RELEASE_ARCHIVE_EXTENSION}
storage_key="sdk-java/$RELEASE_CANDIDATE_DIGEST/$artifact_name"

head_object=$(mktemp)
head_state=$(s3_head_state "$head_object") || fail "Unable to inspect the candidate artifact."
if [[ $head_state == present ]]; then
  emit_existing "$head_object" "the existing candidate artifact"
  exit 0
fi

stage=$(mktemp -d)
mkdir "$stage/$archive_root"
cp "$prebuilt/$binary_name" "$stage/$archive_root/$binary_name"
if [[ $RELEASE_PLATFORM == windows-amd64 ]]; then
  touch -t 198001010000 "$stage/$archive_root" "$stage/$archive_root/$binary_name"
  (cd "$stage" && zip -Xqr "$artifact_name" "$archive_root")
else
  chmod 0755 "$stage/$archive_root/$binary_name"
  tar -C "$stage" --sort=name --mtime='UTC 1970-01-01' --owner=0 --group=0 \
    --numeric-owner -cf - "$archive_root" | gzip -n >"$stage/$artifact_name"
fi
artifact="$stage/$artifact_name"
sha256=$(sha256_file "$artifact")
size=$(wc -c <"$artifact" | tr -d ' ')

head_state=$(s3_head_state "$head_object") || fail "Unable to recheck the candidate artifact."
if [[ $head_state == present ]]; then
  emit_existing "$head_object" "the concurrently committed candidate artifact"
  exit 0
fi
if ! aws s3api put-object \
  --bucket "$RELEASE_ARTIFACT_BUCKET" \
  --key "$storage_key" \
  --body "$artifact" \
  --metadata "sha256=$sha256" \
  --if-none-match '*' >/dev/null; then
  head_state=$(s3_head_state "$head_object") || fail "Unable to reconcile the S3 upload."
  [[ $head_state == present ]] || fail "The failed S3 upload did not create an object."
  emit_existing "$head_object" "the candidate artifact that won the upload race"
  exit 0
fi

printf '%s\t%s\t%s\n' "$artifact_name" "$sha256" "$size"
