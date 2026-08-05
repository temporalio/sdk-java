#!/usr/bin/env bash

set -euo pipefail

root=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/bin" "$work/s3"

cat >"$work/bin/aws" <<'FAKE_AWS'
#!/usr/bin/env bash
set -euo pipefail
if [[ $1 == s3api && $2 == put-object ]]; then
  shift 2
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --key) key=$2; shift 2 ;;
      --body) body=$2; shift 2 ;;
      *) shift ;;
    esac
  done
  target="$FAKE_S3_ROOT/$key"
  [[ ! -e $target ]] || exit 1
  mkdir -p "$(dirname "$target")"
  cp "$body" "$target"
elif [[ $1 == s3 && $2 == cp ]]; then
  source=${3#s3://test-bucket/}
  cp "$FAKE_S3_ROOT/$source" "$4"
else
  exit 2
fi
FAKE_AWS
chmod +x "$work/bin/aws"

authorize() {
  local actor=$1 github_run=$2 workflow_run=$3 output=$4 authorization=$5
  PATH="$work/bin:$PATH" FAKE_S3_ROOT="$work/s3" \
    GITHUB_OUTPUT="$output" GITHUB_RUN_ID="$github_run" GITHUB_TRIGGERING_ACTOR="$actor" \
    RELEASE_ARTIFACT_BUCKET=test-bucket RELEASE_AUTHORIZATION_FILE="$authorization" \
    RELEASE_COMMIT=1111111111111111111111111111111111111111 \
    RELEASE_DIGEST=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
    RELEASE_GENERATION=2 RELEASE_RUN_ID="$workflow_run" \
    RELEASE_TAG=v1.2.3 \
    RELEASE_WORKFLOW_ID=sdk-java-release/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
    "$root/record-maven-retry-authorization.sh"
}

authorize first-manager 1001 11111111-2222-3333-4444-555555555555 \
  "$work/first.outputs" "$work/first.json"
authorize current-manager 1002 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  "$work/current.outputs" "$work/current.json"
authorize current-manager 1002 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  "$work/current-rerun.outputs" "$work/current-rerun.json"

prefix="$work/s3/sdk-java/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/state/maven/retry-authorizations/2"
jq -e 'keys == ["commitSha","generation","releaseDigest","repository","tag"]' \
  "$prefix/reservation.json" >/dev/null
jq -e '.githubActor == "first-manager" and .githubRunId == 1001' \
  "$prefix/runs/1001.json" >/dev/null
jq -e '.githubActor == "current-manager" and .githubRunId == 1002' \
  "$prefix/runs/1002.json" >/dev/null
jq -e '.runId == "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"' "$prefix/runs/1002.json" >/dev/null
[[ $(awk -F= '$1 == "actor" {print $2}' "$work/current.outputs") == current-manager ]]
[[ $(awk -F= '$1 == "run_id" {print $2}' "$work/current.outputs") == 1002 ]]
actual_sha256=$(awk -F= '$1 == "sha256" {print $2}' "$work/current.outputs")
expected_sha256=$(sha256sum "$prefix/runs/1002.json" | awk '{print $1}')
[[ $actual_sha256 == "$expected_sha256" ]]
cmp -s "$work/current.json" "$work/current-rerun.json"
if authorize another-manager 1002 aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \
  "$work/conflict.outputs" "$work/conflict.json" 2>/dev/null; then
  echo "A GitHub run was reattributed to another manager." >&2
  exit 1
fi
