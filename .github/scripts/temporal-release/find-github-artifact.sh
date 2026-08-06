#!/usr/bin/env bash

set -euo pipefail

fail() { echo "find-github-artifact: $*" >&2; exit 1; }
conflict() { echo "find-github-artifact: immutable artifact conflict: $*" >&2; exit 42; }
unavailable() { echo "find-github-artifact: exact artifact unavailable: $*" >&2; exit 46; }

[[ -n ${GH_TOKEN:-} && -n ${GITHUB_ARTIFACT_NAME:-} ]] || fail "GitHub access and artifact name are required."
[[ $GITHUB_ARTIFACT_NAME =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || conflict "the artifact name is invalid."
response=$(gh api --paginate --slurp --method GET \
  repos/temporalio/sdk-java/actions/artifacts -f name="$GITHUB_ARTIFACT_NAME" -f per_page=100) ||
  fail "GitHub artifact discovery is unavailable."
matches=$(jq --arg name "$GITHUB_ARTIFACT_NAME" \
  '[.[].artifacts[] | select(.name == $name)]' <<<"$response")
total=$(jq 'length' <<<"$matches")
(( total <= 1 )) || conflict "more than one artifact has the immutable routing name."
live=$(jq '[.[] | select(.expired == false)] | length' <<<"$matches")
if (( live == 0 )); then
  (( total == 0 )) ||
    unavailable "the only artifact with the immutable routing name expired."
  echo 'found=false'
  exit 0
fi
jq -er '
  [.[] | select(.expired == false)][0] |
   "found=true\nartifact_id=\(.id)\nworkflow_run_id=\(.workflow_run.id)\ngithub_digest=\(.digest)"' \
  <<<"$matches"
