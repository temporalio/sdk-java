#!/usr/bin/env bash

set -euo pipefail

fail() { echo "collect-artifact-manifest: $*" >&2; exit 1; }

[[ ${RELEASE_ARTIFACT_BUCKET:-} && ${RELEASE_CANDIDATE_DIGEST:-} =~ ^[0-9a-f]{64}$ ]] ||
  fail "The artifact bucket and immutable candidate digest are required."
[[ ${RELEASE_MANIFEST_FILE:-} ]] || fail "RELEASE_MANIFEST_FILE is required."

prefix=${RELEASE_ARTIFACT_PREFIX:-"sdk-java/$RELEASE_CANDIDATE_DIGEST/"}
[[ $prefix == "sdk-java/$RELEASE_CANDIDATE_DIGEST/" ||
  $prefix =~ ^sdk-java/emergency-artifacts/$RELEASE_CANDIDATE_DIGEST/[0-9a-f]{64}/$ ]] ||
  fail "The immutable artifact prefix is invalid."
listing=$(mktemp)
aws s3api list-objects-v2 --bucket "$RELEASE_ARTIFACT_BUCKET" --prefix "$prefix" \
  --output json >"$listing" || fail "Unable to list durable candidate artifacts."
mapfile -t keys < <(jq -er '.Contents // [] | .[].Key' "$listing" | sort)
[[ ${#keys[@]} -eq 6 ]] || fail "The exact six native artifacts are not durable yet."
: >"$RELEASE_MANIFEST_FILE"
for key in "${keys[@]}"; do
  name=${key#"$prefix"}
  [[ $name && $name != */* ]] || fail "The candidate prefix contains an unexpected object."
  head=$(aws s3api head-object --bucket "$RELEASE_ARTIFACT_BUCKET" --key "$key") ||
    fail "Unable to inspect $key."
  hash=$(jq -er '.Metadata.sha256' <<<"$head")
  size=$(jq -er '.ContentLength' <<<"$head")
  [[ $hash =~ ^[0-9a-f]{64}$ && $size =~ ^[1-9][0-9]*$ ]] ||
    fail "$key has invalid immutable metadata."
  object=$(mktemp)
  aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$key" "$object" --no-progress >/dev/null ||
    fail "Unable to read $key."
  [[ $(sha256sum "$object" | awk '{print $1}') == "$hash" &&
    $(wc -c <"$object" | tr -d ' ') == "$size" ]] ||
    fail "$key bytes differ from its immutable metadata."
  printf '%s\t%s\t%s\t%s\n' "$name" "$hash" "$size" "$key" >>"$RELEASE_MANIFEST_FILE"
done
