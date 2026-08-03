#!/usr/bin/env bash

set -euo pipefail

fail() { echo "verify-artifact-replacement-safe: $*" >&2; exit 1; }
conflict() { echo "verify-artifact-replacement-safe: immutable conflict: $*" >&2; exit 42; }

for name in GH_TOKEN RELEASE_ARTIFACT_BUCKET RELEASE_COMMIT RELEASE_TAG RH_PASSWORD RH_USER; do
  [[ -n ${!name:-} ]] || fail "$name is required."
done
[[ $RELEASE_COMMIT =~ ^[0-9a-f]{40}$ ]] || conflict "the source SHA is invalid."
[[ $RELEASE_TAG =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-RC[0-9]+)?$ ]] ||
  conflict "the release tag is invalid."

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
version=${RELEASE_TAG#v}
central_status=$(curl --silent --show-error --location --output /dev/null --write-out '%{http_code}' \
  "https://repo1.maven.org/maven2/io/temporal/temporal-sdk/$version/temporal-sdk-$version.pom") ||
  fail "Maven Central is temporarily unavailable."
case "$central_status" in
  404) ;;
  200) conflict "Maven Central already has the release coordinates." ;;
  *) fail "Maven Central returned HTTP $central_status while checking replacement safety." ;;
esac

portal_token=$(printf '%s:%s' "$RH_USER" "$RH_PASSWORD" | base64 | tr -d '\n')
curl --silent --show-error --fail --user "$RH_USER:$RH_PASSWORD" \
  --header 'Accept: application/json' \
  'https://ossrh-staging-api.central.sonatype.com/service/local/staging/profile_repositories' \
  >"$work/profile.json" || fail "Sonatype repositories are temporarily unavailable."
curl --silent --show-error --fail --header "Authorization: Bearer $portal_token" \
  --header 'Accept: application/json' \
  'https://ossrh-staging-api.central.sonatype.com/manual/search/repositories?ip=any&profile_id=io.temporal' \
  >"$work/manual.json" || fail "Publisher Portal repositories are temporarily unavailable."
jq -e '[.data // .profileRepositories // [] | .[] |
  select((.description // "") | startswith("sdk-java:"))] | length == 0' \
  "$work/profile.json" >/dev/null || conflict "an automated sdk-java repository is active."
jq -e '(.repositories // []) | length == 0' "$work/manual.json" >/dev/null ||
  conflict "a Publisher Portal compatibility repository is active."

aws s3api list-objects-v2 --bucket "$RELEASE_ARTIFACT_BUCKET" --prefix sdk-java/ \
  --output json >"$work/state.json" || fail "durable Maven state is temporarily unavailable."
while IFS= read -r intent_key; do
  aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$intent_key" "$work/intent.json" \
    --no-progress >/dev/null || fail "a durable Maven intent is temporarily unavailable."
  if jq -e --arg tag "$RELEASE_TAG" --arg commit "$RELEASE_COMMIT" \
    '.tag == $tag and .commitSha == $commit' "$work/intent.json" >/dev/null; then
    conflict "a durable Maven intent already exists for this tag and SHA."
  fi
done < <(jq -r '.Contents // [] | .[].Key |
  select(test("/state/maven/generations/[0-9]+/intent\\.json$"))' "$work/state.json")

tag_status=$(curl --silent --show-error --location --output "$work/tag.json" --write-out '%{http_code}' \
  --header "Authorization: Bearer $GH_TOKEN" --header 'Accept: application/vnd.github+json' \
  "https://api.github.com/repos/temporalio/sdk-java/git/ref/tags/$RELEASE_TAG") ||
  fail "GitHub tag state is temporarily unavailable."
case "$tag_status" in
  404) ;;
  200) conflict "the release tag already exists." ;;
  *) fail "GitHub returned HTTP $tag_status while checking replacement safety." ;;
esac
