#!/usr/bin/env bash

set -euo pipefail

fail() { echo "manual-ownership: $*" >&2; exit 1; }
conflict() { echo "manual-ownership: immutable conflict: $*" >&2; exit 42; }

for name in RELEASE_ARTIFACT_BUCKET RELEASE_COMMIT RELEASE_OWNERSHIP_ACTION RELEASE_TAG; do
  [[ -n ${!name:-} ]] || fail "$name is required."
done
[[ $RELEASE_COMMIT =~ ^[0-9a-f]{40}$ ]] || conflict "the source SHA is invalid."
[[ $RELEASE_TAG =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-RC[0-9]+)?$ ]] ||
  conflict "the release tag is invalid."

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
key="sdk-java/ownership/$RELEASE_TAG.json"

head_state() {
  local error status
  error="$work/head-error.txt"
  set +e
  aws s3api head-object --bucket "$RELEASE_ARTIFACT_BUCKET" --key "$key" \
    >"$work/head.json" 2>"$error"
  status=$?
  set -e
  if [[ $status -eq 0 ]]; then
    echo present
  elif [[ $status -eq 254 ]] &&
    grep -Eq '^An error occurred \((404|NoSuchKey|NotFound)\) when calling the HeadObject operation:' \
      "$error"; then
    echo absent
  else
    cat "$error" >&2
    fail "durable ownership is temporarily unavailable."
  fi
}

read_ownership() {
  aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$key" "$work/ownership.json" --no-progress \
    >/dev/null || fail "durable ownership is temporarily unavailable."
  jq -e --arg tag "$RELEASE_TAG" --arg commit "$RELEASE_COMMIT" \
    'keys == ["commitSha","owner","releaseDigest","tag"] and
     .tag == $tag and .commitSha == $commit and
     (.releaseDigest | test("^[0-9a-f]{64}$")) and
     (.owner == "temporal" or .owner == "manual")' "$work/ownership.json" >/dev/null ||
    conflict "the tag ownership belongs to another immutable release."
}

case "$RELEASE_OWNERSHIP_ACTION" in
  read)
    if [[ $(head_state) == absent ]]; then
      echo ABSENT
      exit 0
    fi
    read_ownership
    jq -r '.owner | ascii_upcase' "$work/ownership.json"
    ;;
  claim)
    [[ ${RELEASE_OWNERSHIP_DIGEST:-} =~ ^[0-9a-f]{64}$ ]] ||
      conflict "the release digest is invalid."
    jq -n --arg tag "$RELEASE_TAG" --arg commitSha "$RELEASE_COMMIT" \
      --arg releaseDigest "$RELEASE_OWNERSHIP_DIGEST" \
      '{tag:$tag,commitSha:$commitSha,releaseDigest:$releaseDigest,owner:"manual"}' \
      >"$work/manual.json"
    if [[ $(head_state) == absent ]]; then
      if ! aws s3api put-object --bucket "$RELEASE_ARTIFACT_BUCKET" --key "$key" \
        --body "$work/manual.json" --if-none-match '*' >/dev/null; then
        [[ $(head_state) == present ]] || fail "unable to claim manual ownership."
      else
        echo "$RELEASE_OWNERSHIP_DIGEST"
        exit 0
      fi
    fi
    read_ownership
    owner=$(jq -er .owner "$work/ownership.json")
    existing_digest=$(jq -er .releaseDigest "$work/ownership.json")
    if [[ $owner == manual ]]; then
      echo "$existing_digest"
      exit 0
    fi
    [[ ${TEMPORAL_HANDOFF_CONFIRMED:-false} == true ]] ||
      conflict "Temporal still owns this release."
    jq --arg owner manual '.owner=$owner' "$work/ownership.json" >"$work/manual.json"
    etag=$(jq -er .ETag "$work/head.json")
    aws s3api put-object --bucket "$RELEASE_ARTIFACT_BUCKET" --key "$key" \
      --body "$work/manual.json" --if-match "$etag" >/dev/null ||
      fail "ownership changed while the manual workflow was taking over."
    echo "$existing_digest"
    ;;
  *) fail "unknown ownership operation." ;;
esac
