#!/usr/bin/env bash

set -euo pipefail

fail() { echo "create-sonatype-repository: $*" >&2; exit 1; }
conflict() { echo "create-sonatype-repository: immutable conflict: $*" >&2; exit 42; }

for name in RH_PASSWORD RH_USER SONATYPE_REPOSITORY_DESCRIPTION; do
  [[ -n ${!name:-} ]] || fail "$name is required."
done
[[ $SONATYPE_REPOSITORY_DESCRIPTION =~ ^sdk-java:[0-9a-f]{64}:[0-9]+$ ]] ||
  conflict "the repository description is outside sdk-java release policy."

base=https://ossrh-staging-api.central.sonatype.com
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
curl --silent --show-error --fail --user "$RH_USER:$RH_PASSWORD" \
  --header 'Accept: application/json' "$base/service/local/staging/profiles" \
  >"$work/profiles.json" || fail "Sonatype profiles are temporarily unavailable."
profile_id=$(jq -er '[.data[] | select(.name == "io.temporal") | .id] |
  if length == 1 then .[0] else error("expected one io.temporal profile") end' \
  "$work/profiles.json") || conflict "Sonatype did not return one fixed io.temporal profile."
jq -n --arg description "$SONATYPE_REPOSITORY_DESCRIPTION" \
  '{data:{description:$description}}' >"$work/start.json"
status=$(curl --silent --show-error --output "$work/response.json" --write-out '%{http_code}' \
  --request POST --user "$RH_USER:$RH_PASSWORD" --header 'Content-Type: application/json' \
  --data-binary "@$work/start.json" \
  "$base/service/local/staging/profiles/$profile_id/start") ||
  fail "Sonatype repository creation was unavailable."
case "$status" in 200 | 201) ;; *) fail "Sonatype returned HTTP $status while creating the repository." ;; esac
jq -er '.data.stagedRepositoryId |
  select(type == "string" and test("^[A-Za-z0-9._-]+$"))' "$work/response.json" ||
  fail "Sonatype accepted creation without returning a repository ID."
