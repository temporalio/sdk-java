#!/usr/bin/env bash

set -euo pipefail

fail() { echo "resolve-emergency-candidate: $*" >&2; exit 1; }

[[ ${GITHUB_REPOSITORY:-} == temporalio/sdk-java ]] ||
  fail "This automation only releases temporalio/sdk-java."
[[ ${RELEASE_COMMIT:-} =~ ^[0-9a-f]{40}$ ]] || fail "A full source SHA is required."
[[ ${RELEASE_AUTOMATION_REF:-} =~ ^[0-9a-f]{40}$ ]] ||
  fail "A full trusted automation SHA is required."
[[ ${TRUSTED_AUTOMATION_ROOT:-} && -d $TRUSTED_AUTOMATION_ROOT ]] ||
  fail "The trusted automation checkout is missing."
[[ ${RELEASE_TAG:-} =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-RC[0-9]+)?$ ]] ||
  fail "The release tag is invalid."
[[ -n ${GITHUB_OUTPUT:-} && -n ${RUNNER_TEMP:-} ]] || fail "Actions paths are missing."
[[ $(git rev-parse --verify HEAD^{commit}) == "$RELEASE_COMMIT" ]] ||
  fail "The checkout is not the exact source SHA."

notes_file="releases/$RELEASE_TAG"
read -r mode type _ < <(git ls-tree "$RELEASE_COMMIT" -- "$notes_file")
[[ $mode == 100644 && $type == blob && -s $notes_file && ! -L $notes_file ]] ||
  fail "The exact release-note file is unavailable."
notes_sha256=$(sha256sum "$notes_file" | awk '{print $1}')

policy_output=$(mktemp)
GITHUB_OUTPUT=$policy_output "$TRUSTED_AUTOMATION_ROOT/gradlew" \
  -p "$TRUSTED_AUTOMATION_ROOT/.github/release-automation" --no-daemon run \
  --args="maven-policy $PWD/settings.gradle" >/dev/null
maven_policy=$(awk -F= '$1 == "maven_policy" {print $2}' "$policy_output" | tail -1)
[[ -n $maven_policy ]] || fail "The fixed Java Maven policy did not classify this source."

candidate_file="$RUNNER_TEMP/sdk-java-emergency-candidate.json"
jq -n --arg repository temporalio/sdk-java --arg version "${RELEASE_TAG#v}" \
  --arg tag "$RELEASE_TAG" --arg commitSha "$RELEASE_COMMIT" \
  --arg releaseNotesPath "$notes_file" --arg releaseNotesSha256 "$notes_sha256" \
  --arg trustedAutomationCommit "$RELEASE_AUTOMATION_REF" --arg mavenPolicy "$maven_policy" \
  '{repository:$repository,version:$version,tag:$tag,commitSha:$commitSha,
    releaseNotesPath:$releaseNotesPath,releaseNotesSha256:$releaseNotesSha256,
    trustedAutomationCommit:$trustedAutomationCommit,mavenPolicy:$mavenPolicy}' >"$candidate_file"

{
  printf 'candidate_file=%s\n' "$candidate_file"
  printf 'notes_sha256=%s\n' "$notes_sha256"
  printf 'maven_policy=%s\n' "$maven_policy"
} >>"$GITHUB_OUTPUT"
