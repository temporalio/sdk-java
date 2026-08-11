#!/usr/bin/env bash

set -euo pipefail

# Report a transient GitHub or local tooling failure.
fail() { echo "github-artifact: $*" >&2; exit 1; }
# Report a durable identity mismatch that retries cannot repair.
conflict() { echo "github-artifact: immutable conflict: $*" >&2; exit 42; }
# Report expiry of the one artifact identity the Workflow may accept.
unavailable() { echo "github-artifact: unavailable: $*" >&2; exit 46; }
# Require nonempty environment inputs before using them in API or file arguments.
need() { for name in "$@"; do [[ -n ${!name:-} ]] || fail "$name is required."; done; }

# Find at most one artifact with an immutable release-derived name. An expired exact
# match is unavailable, not absent: rebuilding under the same name could substitute bytes.
find_artifact() {
  need GH_TOKEN GITHUB_ARTIFACT_NAME
  [[ $GITHUB_ARTIFACT_NAME =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || conflict "invalid name."
  response=$(gh api --paginate --slurp --method GET repos/temporalio/sdk-java/actions/artifacts \
    -f name="$GITHUB_ARTIFACT_NAME" -f per_page=100) || fail "discovery failed."
  matches=$(jq --arg name "$GITHUB_ARTIFACT_NAME" \
    '[.[].artifacts[] | select(.name == $name)]' <<<"$response")
  count=$(jq length <<<"$matches")
  (( count <= 1 )) || conflict "multiple artifacts have the immutable name."
  if (( count == 0 )); then
    echo found=false
    return
  fi
  jq -e '.[0].expired == false' <<<"$matches" >/dev/null || unavailable "artifact expired."
  jq -er '.[0] | "found=true\nartifact_id=\(.id)\nworkflow_run_id=\(.workflow_run.id)\ngithub_digest=\(.digest)"' \
    <<<"$matches"
}

# Freeze GitHub's artifact identity in a Temporal receipt. Publication later downloads
# and validates the receipt-backed archive in typed Python before consuming any bytes.
record_artifact() {
  need GITHUB_ARTIFACT_FILE_NAME GITHUB_ARTIFACT_RECEIPT_FILE
  [[ $GITHUB_ARTIFACT_FILE_NAME =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] ||
    conflict "invalid artifact filename."
  found=$(find_artifact)
  [[ $found == found=true$'\n'* ]] || unavailable "artifact absent."
  # Read one named field from find_artifact's Actions-output-compatible result.
  value() { awk -F= -v key="$1" '$1 == key {print $2}' <<<"$found"; }
  jq -n --argjson artifactId "$(value artifact_id)" \
    --argjson workflowRunId "$(value workflow_run_id)" \
    --arg artifactName "$GITHUB_ARTIFACT_NAME" --arg githubDigest "$(value github_digest)" \
    --arg fileName "$GITHUB_ARTIFACT_FILE_NAME" \
    '{artifactId:$artifactId,workflowRunId:$workflowRunId,artifactName:$artifactName,
      githubDigest:$githubDigest,fileName:$fileName}' >"$GITHUB_ARTIFACT_RECEIPT_FILE"
}

case ${1:-} in
  find) find_artifact ;;
  record) record_artifact ;;
  *) fail "expected find or record." ;;
esac
