#!/usr/bin/env bash

set -euo pipefail

root=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir "$work/bin" "$work/content"

cat >"$work/bin/gh" <<'FAKE_GH'
#!/usr/bin/env bash
set -euo pipefail
if [[ ${FAKE_GH_RESPONSE_FILE:-} && "$*" == *"actions/artifacts -f"* ]]; then
  cat "$FAKE_GH_RESPONSE_FILE"
elif [[ "$*" == *"actions/runs/"* ]]; then
  cat "$FAKE_GH_RUN_FILE"
else
  cat "$FAKE_GH_METADATA_FILE"
fi
FAKE_GH

cat >"$work/bin/curl" <<'FAKE_CURL'
#!/usr/bin/env bash
set -euo pipefail
output=
arguments=("$@")
for ((index = 0; index < ${#arguments[@]}; index++)); do
  if [[ ${arguments[$index]} == --output ]]; then
    output=${arguments[$((index + 1))]}
  fi
done
url=${arguments[$((${#arguments[@]} - 1))]}
status=${FAKE_CURL_STATUS:-200}
if [[ $status == 200 ]]; then
  if [[ $url == */zip ]]; then cp "$FAKE_CURL_ZIP" "$output"
  else cp "$FAKE_CURL_METADATA" "$output"
  fi
else
  : >"$output"
fi
printf '%s' "$status"
FAKE_CURL
chmod +x "$work/bin/gh" "$work/bin/curl"

cat >"$work/live.json" <<'JSON'
[{"artifacts":[{"id":11,"name":"exact-name","expired":false,
"digest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
"workflow_run":{"id":22}}]}]
JSON
PATH="$work/bin:$PATH" FAKE_GH_RESPONSE_FILE="$work/live.json" \
  GH_TOKEN=test GITHUB_ARTIFACT_NAME=exact-name \
  "$root/github-artifact.sh" find >"$work/find.out"
grep -Fxq 'found=true' "$work/find.out"
grep -Fxq 'artifact_id=11' "$work/find.out"
grep -Fxq 'workflow_run_id=22' "$work/find.out"

cat >"$work/duplicate.json" <<'JSON'
[{"artifacts":[
{"id":11,"name":"exact-name","expired":false,"workflow_run":{"id":22}},
{"id":12,"name":"exact-name","expired":false,"workflow_run":{"id":23}}]}]
JSON
set +e
PATH="$work/bin:$PATH" FAKE_GH_RESPONSE_FILE="$work/duplicate.json" \
  GH_TOKEN=test GITHUB_ARTIFACT_NAME=exact-name \
  "$root/github-artifact.sh" find >/dev/null 2>&1
status=$?
set -e
[[ $status -eq 42 ]]

cat >"$work/expired.json" <<'JSON'
[{"artifacts":[{"id":11,"name":"exact-name","expired":true,"workflow_run":{"id":22}}]}]
JSON
set +e
PATH="$work/bin:$PATH" FAKE_GH_RESPONSE_FILE="$work/expired.json" \
  GH_TOKEN=test GITHUB_ARTIFACT_NAME=exact-name \
  "$root/github-artifact.sh" find >/dev/null 2>&1
status=$?
set -e
[[ $status -eq 46 ]]

cat >"$work/metadata.json" <<'JSON'
{"id":11,"name":"exact-name","expired":false,
"digest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
"created_at":"2026-08-01T00:00:00Z","expires_at":"2026-10-30T00:00:00Z",
"workflow_run":{"id":22}}
JSON
cat >"$work/run.json" <<'JSON'
{"id":22,"path":".github/workflows/temporal-release-candidate.yml","event":"push",
"head_branch":"main","head_repository":{"full_name":"temporalio/sdk-java"},"status":"completed"}
JSON
printf 'release bytes' >"$work/content/release.tar.gz"
(cd "$work/content" && zip -q "$work/artifact.zip" release.tar.gz)
archive_digest=$(sha256sum "$work/artifact.zip" | awk '{print $1}')
jq --arg digest "sha256:$archive_digest" '.digest = $digest' \
  "$work/metadata.json" >"$work/download-metadata.json"
cat >"$work/download-receipt.json" <<JSON
{"artifactId":11,"workflowRunId":22,"artifactName":"exact-name",
 "githubDigest":"sha256:$archive_digest","fileName":"release.tar.gz"}
JSON
PATH="$work/bin:$PATH" FAKE_CURL_METADATA="$work/download-metadata.json" \
  FAKE_CURL_ZIP="$work/artifact.zip" FAKE_GH_RUN_FILE="$work/run.json" GH_TOKEN=test \
  GITHUB_ARTIFACT_DESTINATION="$work/download" \
  GITHUB_ARTIFACT_RECEIPT_FILE="$work/download-receipt.json" \
  "$root/github-artifact.sh" download
cmp "$work/content/release.tar.gz" "$work/download/release.tar.gz"

jq '.path = ".github/workflows/temporal-release-resume.yml"' "$work/run.json" >"$work/stale-run.json"
set +e
PATH="$work/bin:$PATH" FAKE_CURL_METADATA="$work/download-metadata.json" \
  FAKE_CURL_ZIP="$work/artifact.zip" FAKE_GH_RUN_FILE="$work/stale-run.json" GH_TOKEN=test \
  GITHUB_ARTIFACT_DESTINATION="$work/stale-download" \
  GITHUB_ARTIFACT_RECEIPT_FILE="$work/download-receipt.json" \
  "$root/github-artifact.sh" download >/dev/null 2>&1
status=$?
set -e
[[ $status -eq 42 ]]

sed "s/sha256:a\{64\}/sha256:$archive_digest/" "$work/live.json" >"$work/live-download.json"
PATH="$work/bin:$PATH" FAKE_GH_RESPONSE_FILE="$work/live-download.json" \
  GH_TOKEN=test GITHUB_ARTIFACT_FILE_NAME=release.tar.gz \
  GITHUB_ARTIFACT_NAME=exact-name GITHUB_ARTIFACT_RECEIPT_FILE="$work/received.json" \
  "$root/github-artifact.sh" record
jq -e '.artifactId == 11 and .fileName == "release.tar.gz"' "$work/received.json" >/dev/null

jq '.digest = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"' \
  "$work/download-metadata.json" >"$work/changed-metadata.json"
set +e
PATH="$work/bin:$PATH" FAKE_CURL_METADATA="$work/changed-metadata.json" \
  FAKE_CURL_ZIP="$work/artifact.zip" GH_TOKEN=test \
  GITHUB_ARTIFACT_DESTINATION="$work/conflict" \
  GITHUB_ARTIFACT_RECEIPT_FILE="$work/download-receipt.json" \
  "$root/github-artifact.sh" download >/dev/null 2>&1
status=$?
set -e
[[ $status -eq 42 ]]

jq '.expired = true' "$work/download-metadata.json" >"$work/expired-metadata.json"
set +e
PATH="$work/bin:$PATH" FAKE_CURL_METADATA="$work/expired-metadata.json" \
  FAKE_CURL_ZIP="$work/artifact.zip" GH_TOKEN=test \
  GITHUB_ARTIFACT_DESTINATION="$work/expired-download" \
  GITHUB_ARTIFACT_RECEIPT_FILE="$work/download-receipt.json" \
  "$root/github-artifact.sh" download >/dev/null 2>&1
status=$?
set -e
[[ $status -eq 46 ]]
