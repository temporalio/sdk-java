#!/usr/bin/env bash

set -euo pipefail

root=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir "$work/bin"
cat >"$work/bin/gh" <<'FAKE_GH'
#!/usr/bin/env bash
cat "$FAKE_GH_RESPONSE_FILE"
FAKE_GH
chmod +x "$work/bin/gh"

# Exercise the entire remaining shell surface: exact discovery, duplicate/expired
# refusal, and conversion of immutable GitHub metadata into the Temporal receipt.
cat >"$work/live.json" <<'JSON'
[{"artifacts":[{"id":11,"name":"exact-name","expired":false,
"digest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
"workflow_run":{"id":22}}]}]
JSON
PATH="$work/bin:$PATH" FAKE_GH_RESPONSE_FILE="$work/live.json" GH_TOKEN=test \
  GITHUB_ARTIFACT_NAME=exact-name "$root/github-artifact.sh" find >"$work/find.out"
grep -Fxq found=true "$work/find.out"
grep -Fxq artifact_id=11 "$work/find.out"

cat >"$work/duplicate.json" <<'JSON'
[{"artifacts":[
{"id":11,"name":"exact-name","expired":false,"workflow_run":{"id":22}},
{"id":12,"name":"exact-name","expired":false,"workflow_run":{"id":23}}]}]
JSON
set +e
PATH="$work/bin:$PATH" FAKE_GH_RESPONSE_FILE="$work/duplicate.json" GH_TOKEN=test \
  GITHUB_ARTIFACT_NAME=exact-name "$root/github-artifact.sh" find >/dev/null 2>&1
[[ $? -eq 42 ]] || exit 1

sed 's/"expired":false/"expired":true/' "$work/live.json" >"$work/expired.json"
PATH="$work/bin:$PATH" FAKE_GH_RESPONSE_FILE="$work/expired.json" GH_TOKEN=test \
  GITHUB_ARTIFACT_NAME=exact-name "$root/github-artifact.sh" find >/dev/null 2>&1
[[ $? -eq 46 ]] || exit 1
set -e

PATH="$work/bin:$PATH" FAKE_GH_RESPONSE_FILE="$work/live.json" GH_TOKEN=test \
  GITHUB_ARTIFACT_NAME=exact-name GITHUB_ARTIFACT_FILE_NAME=release.tar.gz \
  GITHUB_ARTIFACT_RECEIPT_FILE="$work/receipt.json" "$root/github-artifact.sh" record
jq -e '.artifactId == 11 and .workflowRunId == 22 and .fileName == "release.tar.gz"' \
  "$work/receipt.json" >/dev/null
