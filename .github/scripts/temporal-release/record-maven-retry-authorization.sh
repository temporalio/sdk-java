#!/usr/bin/env bash

set -euo pipefail

fail() { echo "record-maven-retry-authorization: $*" >&2; exit 1; }

required=(
  GITHUB_OUTPUT GITHUB_RUN_ID GITHUB_TRIGGERING_ACTOR RELEASE_ARTIFACT_BUCKET
  RELEASE_AUTHORIZATION_FILE RELEASE_COMMIT RELEASE_DIGEST RELEASE_GENERATION RELEASE_RUN_ID
  RELEASE_TAG RELEASE_WORKFLOW_ID
)
for variable in "${required[@]}"; do
  [[ -n ${!variable:-} ]] || fail "Required value $variable is missing."
done

[[ $GITHUB_RUN_ID =~ ^[1-9][0-9]*$ ]] || fail "The GitHub run ID is invalid."
[[ $GITHUB_TRIGGERING_ACTOR =~ ^[A-Za-z0-9-]{1,39}$ ]] || fail "The GitHub actor is invalid."
[[ $RELEASE_COMMIT =~ ^[0-9a-f]{40}$ ]] || fail "The release commit is invalid."
[[ $RELEASE_DIGEST =~ ^[0-9a-f]{64}$ ]] || fail "The release digest is invalid."
[[ $RELEASE_GENERATION =~ ^[1-9][0-9]*$ ]] || fail "The Maven generation is invalid."
[[ $RELEASE_RUN_ID =~ ^[0-9a-fA-F-]{16,64}$ ]] || fail "The Workflow Run ID is invalid."
[[ $RELEASE_TAG =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-RC[0-9]+)?$ ]] ||
  fail "The release tag is invalid."
[[ $RELEASE_WORKFLOW_ID == "sdk-java-release/$RELEASE_DIGEST" ]] ||
  fail "The Workflow ID is invalid."

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

put_immutable() {
  local key=$1 file=$2 description=$3 existing="$work/existing.json"
  if aws s3api put-object --bucket "$RELEASE_ARTIFACT_BUCKET" --key "$key" \
    --body "$file" --if-none-match '*' >/dev/null 2>"$work/put-error"; then
    return
  fi
  aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$key" "$existing" --no-progress >/dev/null ||
    fail "Unable to reconcile the durable $description."
  cmp -s "$file" "$existing" || fail "The durable $description has another identity."
}

prefix="sdk-java/$RELEASE_DIGEST/state/maven/retry-authorizations/$RELEASE_GENERATION"
reservation="$work/reservation.json"
jq -cn --arg repository temporalio/sdk-java --arg tag "$RELEASE_TAG" \
  --arg commitSha "$RELEASE_COMMIT" --arg releaseDigest "$RELEASE_DIGEST" \
  --argjson generation "$RELEASE_GENERATION" \
  '{repository:$repository,tag:$tag,commitSha:$commitSha,releaseDigest:$releaseDigest,
    generation:$generation}' >"$reservation"
put_immutable "$prefix/reservation.json" "$reservation" "Maven retry generation reservation"
reservation_sha256=$(sha256sum "$reservation" | awk '{print $1}')

jq -cn --arg repository temporalio/sdk-java --arg tag "$RELEASE_TAG" \
  --arg commitSha "$RELEASE_COMMIT" --arg releaseDigest "$RELEASE_DIGEST" \
  --arg workflowId "$RELEASE_WORKFLOW_ID" --arg runId "$RELEASE_RUN_ID" \
  --arg githubActor "$GITHUB_TRIGGERING_ACTOR" --argjson githubRunId "$GITHUB_RUN_ID" \
  --argjson authorizedGeneration "$RELEASE_GENERATION" \
  --arg reservationSha256 "$reservation_sha256" \
  '{repository:$repository,tag:$tag,commitSha:$commitSha,releaseDigest:$releaseDigest,
    workflowId:$workflowId,runId:$runId,githubActor:$githubActor,githubRunId:$githubRunId,
    authorizedGeneration:$authorizedGeneration,reservationSha256:$reservationSha256,
    freshInspection:true}' >"$RELEASE_AUTHORIZATION_FILE"
put_immutable "$prefix/runs/$GITHUB_RUN_ID.json" "$RELEASE_AUTHORIZATION_FILE" \
  "Maven retry run authorization"

{
  echo "generation=$RELEASE_GENERATION"
  echo "sha256=$(sha256sum "$RELEASE_AUTHORIZATION_FILE" | awk '{print $1}')"
  echo "actor=$GITHUB_TRIGGERING_ACTOR"
  echo "run_id=$GITHUB_RUN_ID"
} >>"$GITHUB_OUTPUT"
