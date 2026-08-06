#!/usr/bin/env bash

set -euo pipefail

fail() {
  echo "resolve-candidate: $*" >&2
  exit 1
}

[[ ${GITHUB_REPOSITORY:-} == temporalio/sdk-java ]] ||
  fail "This automation only releases temporalio/sdk-java."
[[ ${BASE_SHA:-} =~ ^[0-9a-f]{40}$ ]] || fail "BASE_SHA must be a full commit SHA."
[[ ${RELEASE_COMMIT:-} =~ ^[0-9a-f]{40}$ ]] ||
  fail "RELEASE_COMMIT must be a full commit SHA."
[[ ${RELEASE_AUTOMATION_REF:-} =~ ^[0-9a-f]{40}$ ]] ||
  fail "RELEASE_AUTOMATION_REF must be a full commit SHA."
[[ ${TRUSTED_AUTOMATION_ROOT:-} && -d $TRUSTED_AUTOMATION_ROOT ]] ||
  fail "The trusted automation checkout is missing."
[[ -n ${GITHUB_OUTPUT:-} && -n ${RUNNER_TEMP:-} ]] || fail "GitHub Actions paths are missing."

git merge-base --is-ancestor "$BASE_SHA" "$RELEASE_COMMIT" ||
  fail "The previous push SHA is not an ancestor of the release commit."
[[ $(git rev-parse --verify HEAD^{commit}) == "$RELEASE_COMMIT" ]] ||
  fail "The checkout is not the immutable release commit."

fields=()
while IFS= read -r -d '' field; do
  fields+=("$field")
done < <(git diff --name-status --no-renames -z "$BASE_SHA" "$RELEASE_COMMIT" -- releases/)

[[ ${#fields[@]} -eq 2 ]] ||
  fail "The push must contain exactly one release-note change."
[[ ${fields[0]} == A ]] || fail "The release-note change must add a new file."
notes_file=${fields[1]}
[[ $notes_file =~ ^releases/(v[0-9]+\.[0-9]+\.[0-9]+(-RC[0-9]+)?)$ ]] ||
  fail "The release-note filename is invalid."
tag=${BASH_REMATCH[1]}

read -r mode type _ < <(git ls-tree "$RELEASE_COMMIT" -- "$notes_file")
[[ $mode == 100644 && $type == blob ]] || fail "Release notes must be a regular file."
[[ -s $notes_file && ! -L $notes_file ]] || fail "Release notes must be nonempty and not a symlink."
notes_sha256=$(sha256sum "$notes_file" | awk '{print $1}')

policy_output=$(mktemp)
GITHUB_OUTPUT=$policy_output "$TRUSTED_AUTOMATION_ROOT/gradlew" \
  -p "$TRUSTED_AUTOMATION_ROOT/.github/release-automation" --no-daemon run \
  --args="maven-policy $PWD/settings.gradle" >/dev/null
maven_policy=$(awk -F= '$1 == "maven_policy" {print $2}' "$policy_output" | tail -1)
[[ -n $maven_policy ]] || fail "The fixed Java Maven policy did not classify this source."

set +e
git ls-remote --exit-code --tags https://github.com/temporalio/sdk-java.git \
  "refs/tags/$tag" >/dev/null 2>&1
tag_status=$?
set -e
case "$tag_status" in
  0) fail "The release tag already exists." ;;
  2) ;;
  *) fail "Unable to determine whether the release tag exists." ;;
esac

candidate_file="$RUNNER_TEMP/sdk-java-release-candidate.json"
jq -n \
  --arg tag "$tag" \
  --arg commitSha "$RELEASE_COMMIT" \
  --arg releaseNotesSha256 "$notes_sha256" \
  --arg trustedAutomationCommit "$RELEASE_AUTOMATION_REF" \
  --arg mavenPolicy "$maven_policy" \
  '{tag: $tag, commitSha: $commitSha, releaseNotesSha256: $releaseNotesSha256,
    trustedAutomationCommit: $trustedAutomationCommit, mavenPolicy: $mavenPolicy}' \
  >"$candidate_file"

{
  printf 'candidate_file=%s\n' "$candidate_file"
  printf 'tag=%s\n' "$tag"
  printf 'version=%s\n' "${tag#v}"
  printf 'commit=%s\n' "$RELEASE_COMMIT"
  printf 'notes_sha256=%s\n' "$notes_sha256"
  printf 'automation_commit=%s\n' "$RELEASE_AUTOMATION_REF"
  printf 'maven_policy=%s\n' "$maven_policy"
} >>"$GITHUB_OUTPUT"
