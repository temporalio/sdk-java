#!/usr/bin/env bash

set -euo pipefail

# The only trusted producer of release artifacts is the merge-triggered candidate run.
RELEASE_WORKFLOW_PATH=.github/workflows/temporal-release-candidate.yml
RELEASE_WORKFLOW_EVENT=push
RELEASE_WORKFLOW_BRANCH_PATTERN='^(main|releases/.+|[^/]*\.[^/]*\.x|release_[^/]*_[^/]*_x)$'

# Report a transient GitHub or local tooling failure.
fail() { echo "github-artifact: $*" >&2; exit 1; }
# Report a durable identity mismatch that retries cannot repair.
conflict() { echo "github-artifact: immutable conflict: $*" >&2; exit 42; }
# Report deletion or expiry of the exact artifact named by durable Workflow state.
unavailable() { echo "github-artifact: unavailable: $*" >&2; exit 46; }
# Compute SHA-256 portably on Linux and macOS runners.
sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  else shasum -a 256 "$1" | awk '{print $1}'; fi
}
# Require nonempty environment inputs before using them in API paths or filesystem paths.
need() { for name in "$@"; do [[ -n ${!name:-} ]] || fail "$name is required."; done; }
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# Find at most one live artifact with the immutable release-derived name.
# Multiple matches are a conflict rather than an arbitrary choice; an expired exact match
# is unavailable rather than absent, so automation cannot silently rebuild another payload.
find_artifact() {
  need GH_TOKEN GITHUB_ARTIFACT_NAME
  [[ $GITHUB_ARTIFACT_NAME =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || conflict "invalid name."
  response=$(gh api --paginate --slurp --method GET \
    repos/temporalio/sdk-java/actions/artifacts -f name="$GITHUB_ARTIFACT_NAME" -f per_page=100) ||
    fail "discovery failed."
  matches=$(jq --arg name "$GITHUB_ARTIFACT_NAME" \
    '[.[].artifacts[] | select(.name == $name)]' <<<"$response")
  total=$(jq length <<<"$matches")
  (( total <= 1 )) || conflict "multiple artifacts have the immutable name."
  live=$(jq '[.[] | select(.expired == false)] | length' <<<"$matches")
  if (( live == 0 )); then
    (( total == 0 )) || unavailable "the exact artifact expired."
    echo found=false
    return
  fi
  jq -er '[.[] | select(.expired == false)][0] |
    "found=true\nartifact_id=\(.id)\nworkflow_run_id=\(.workflow_run.id)\ngithub_digest=\(.digest)"' \
    <<<"$matches"
}

# Download an artifact frozen by its receipt and verify metadata, archive digest, and shape.
# GitHub's ZIP digest binds the complete archive. The receipt's single expected filename
# prevents path injection and ensures the later publication step consumes exactly the file
# that the Workflow associated with this platform or Maven payload.
download_artifact() {
  need GH_TOKEN GITHUB_ARTIFACT_DESTINATION
  receipt=${GITHUB_ARTIFACT_RECEIPT_FILE:-}
  if [[ -n $receipt ]]; then
    jq -e '.artifactId > 0 and .workflowRunId > 0 and
      (.artifactName | test("^[A-Za-z0-9][A-Za-z0-9._-]*$")) and
      (.githubDigest | test("^sha256:[0-9a-f]{64}$")) and
      (.fileName | test("^[A-Za-z0-9][A-Za-z0-9._-]*$"))' "$receipt" >/dev/null ||
      conflict "invalid Temporal receipt."
    artifact_id=$(jq -er .artifactId "$receipt")
    workflow_run_id=$(jq -er .workflowRunId "$receipt")
    artifact_name=$(jq -er .artifactName "$receipt")
    github_digest=$(jq -er .githubDigest "$receipt")
  else
    need GITHUB_ARTIFACT_ID GITHUB_ARTIFACT_RUN_ID GITHUB_ARTIFACT_NAME GITHUB_ARTIFACT_DIGEST
    artifact_id=$GITHUB_ARTIFACT_ID
    workflow_run_id=$GITHUB_ARTIFACT_RUN_ID
    artifact_name=$GITHUB_ARTIFACT_NAME
    github_digest=$GITHUB_ARTIFACT_DIGEST
  fi
  [[ $artifact_id =~ ^[1-9][0-9]*$ && $workflow_run_id =~ ^[1-9][0-9]*$ &&
    $artifact_name =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ &&
    $github_digest =~ ^sha256:[0-9a-f]{64}$ ]] || conflict "invalid artifact identity."
  status=$(curl --silent --show-error --location --output "$work/metadata" --write-out '%{http_code}' \
    --header "Authorization: Bearer $GH_TOKEN" --header 'Accept: application/vnd.github+json' \
    "https://api.github.com/repos/temporalio/sdk-java/actions/artifacts/$artifact_id") ||
    fail "metadata request failed."
  case $status in 200) ;; 404) unavailable "artifact $artifact_id was deleted." ;; *)
    fail "GitHub returned HTTP $status for metadata." ;; esac
  jq -e --argjson id "$artifact_id" --argjson run "$workflow_run_id" --arg name "$artifact_name" \
    --arg digest "$github_digest" \
    '.id == $id and .workflow_run.id == $run and .name == $name and
     .digest == $digest' "$work/metadata" >/dev/null ||
    conflict "artifact metadata changed."
  jq -e '.expired == false' "$work/metadata" >/dev/null ||
    unavailable "artifact $artifact_id has expired."
  run=$(gh api "repos/temporalio/sdk-java/actions/runs/$workflow_run_id") ||
    fail "the originating GitHub Actions run is temporarily unavailable."
  jq -e --argjson id "$workflow_run_id" --arg path "$RELEASE_WORKFLOW_PATH" \
    --arg event "$RELEASE_WORKFLOW_EVENT" --arg branch "$RELEASE_WORKFLOW_BRANCH_PATTERN" '
    .id == $id and .path == $path and .event == $event and
    .head_repository.full_name == "temporalio/sdk-java" and
    (.head_branch | test($branch)) and
    (.status == "in_progress" or .status == "completed")' <<<"$run" >/dev/null ||
    conflict "the artifact originated from another workflow run."
  status=$(curl --silent --show-error --location --output "$work/archive" --write-out '%{http_code}' \
    --header "Authorization: Bearer $GH_TOKEN" --header 'Accept: application/vnd.github+json' \
    "https://api.github.com/repos/temporalio/sdk-java/actions/artifacts/$artifact_id/zip") ||
    fail "download failed."
  case $status in 200) ;; 404 | 410) unavailable "artifact has no archive." ;; *)
    fail "GitHub returned HTTP $status for download." ;; esac
  [[ "sha256:$(sha256_file "$work/archive")" == "$github_digest" ]] ||
    conflict "archive digest changed."
  unzip -Z1 "$work/archive" | sort >"$work/files" || conflict "archive is not a ZIP."
  while IFS= read -r name; do
    [[ $name =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || conflict "archive has an unsafe path."
  done <"$work/files"
  [[ -z $(uniq -d "$work/files") ]] || conflict "archive has duplicate entries."
  if [[ -n $receipt ]]; then
    [[ $(wc -l <"$work/files" | tr -d ' ') == 1 &&
      $(<"$work/files") == "$(jq -er .fileName "$receipt")" ]] ||
      conflict "receipt filename changed."
  fi
  mkdir -p "$GITHUB_ARTIFACT_DESTINATION"
  [[ -z $(find "$GITHUB_ARTIFACT_DESTINATION" -mindepth 1 -print -quit) ]] ||
    fail "destination is not empty."
  unzip -q "$work/archive" -d "$GITHUB_ARTIFACT_DESTINATION"
  if [[ -n $receipt ]]; then
    file="$GITHUB_ARTIFACT_DESTINATION/$(jq -er .fileName "$receipt")"
    [[ -f $file && ! -L $file ]] || conflict "download has an invalid entry."
  fi
}

# Record GitHub's immutable artifact metadata without downloading the archive twice.
# Publication performs the full download and content validation before use; this command
# freezes the artifact ID, source run, archive digest, name, and expected filename in
# Temporal state as soon as the credential-free build has uploaded it.
record_artifact() {
  need GITHUB_ARTIFACT_FILE_NAME GITHUB_ARTIFACT_RECEIPT_FILE
  receipt_file=$GITHUB_ARTIFACT_RECEIPT_FILE
  found=$(find_artifact)
  [[ $(awk -F= '$1 == "found" {print $2}' <<<"$found") == true ]] || unavailable "artifact absent."
  artifact_id=$(awk -F= '$1 == "artifact_id" {print $2}' <<<"$found")
  workflow_run_id=$(awk -F= '$1 == "workflow_run_id" {print $2}' <<<"$found")
  github_digest=$(awk -F= '$1 == "github_digest" {print $2}' <<<"$found")
  file_name=$GITHUB_ARTIFACT_FILE_NAME
  [[ $file_name =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || conflict "invalid artifact filename."
  jq -n --argjson artifactId "$artifact_id" --argjson workflowRunId "$workflow_run_id" \
    --arg artifactName "$GITHUB_ARTIFACT_NAME" --arg githubDigest "$github_digest" \
    --arg fileName "$file_name" \
    '{artifactId:$artifactId,workflowRunId:$workflowRunId,artifactName:$artifactName,
      githubDigest:$githubDigest,fileName:$fileName}' >"$receipt_file"
}

case ${1:-} in
  find) find_artifact ;;
  download) download_artifact ;;
  record) record_artifact ;;
  *) fail "expected find, download, or record." ;;
esac
