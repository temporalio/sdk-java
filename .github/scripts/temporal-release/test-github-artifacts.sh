#!/usr/bin/env bash

set -euo pipefail

root=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir "$work/bin" "$work/content"

cat >"$work/bin/gh" <<'FAKE_GH'
#!/usr/bin/env bash
set -euo pipefail
if [[ ${FAKE_GH_RESPONSE_FILE:-} ]]; then
  cat "$FAKE_GH_RESPONSE_FILE"
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
  "$root/find-github-artifact.sh" >"$work/find.out"
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
  "$root/find-github-artifact.sh" >/dev/null 2>&1
status=$?
set -e
[[ $status -eq 42 ]]

cat >"$work/expired.json" <<'JSON'
[{"artifacts":[{"id":11,"name":"exact-name","expired":true,"workflow_run":{"id":22}}]}]
JSON
set +e
PATH="$work/bin:$PATH" FAKE_GH_RESPONSE_FILE="$work/expired.json" \
  GH_TOKEN=test GITHUB_ARTIFACT_NAME=exact-name \
  "$root/find-github-artifact.sh" >/dev/null 2>&1
status=$?
set -e
[[ $status -eq 46 ]]

printf 'release bytes' >"$work/content/release.tar.gz"
cat >"$work/metadata.json" <<'JSON'
{"id":11,"name":"exact-name","expired":false,
"digest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
"created_at":"2026-08-01T00:00:00Z","expires_at":"2026-10-30T00:00:00Z",
"workflow_run":{"id":22}}
JSON
PATH="$work/bin:$PATH" FAKE_GH_METADATA_FILE="$work/metadata.json" GH_TOKEN=test \
  GITHUB_ARTIFACT_CONTENT_DIR="$work/content" GITHUB_ARTIFACT_ID=11 \
  GITHUB_ARTIFACT_NAME=exact-name GITHUB_ARTIFACT_RECEIPT_FILE="$work/receipt.json" \
  GITHUB_ARTIFACT_RUN_ID=22 "$root/github-artifact-receipt.sh"
jq -e '.artifactId == 11 and .workflowRunId == 22 and .files[0].name == "release.tar.gz" and
  .files[0].size == 13 and (.files[0].sha256 | test("^[0-9a-f]{64}$"))' \
  "$work/receipt.json" >/dev/null

mkdir "$work/content/unexpected"
set +e
PATH="$work/bin:$PATH" FAKE_GH_METADATA_FILE="$work/metadata.json" GH_TOKEN=test \
  GITHUB_ARTIFACT_CONTENT_DIR="$work/content" GITHUB_ARTIFACT_ID=11 \
  GITHUB_ARTIFACT_NAME=exact-name GITHUB_ARTIFACT_RECEIPT_FILE="$work/rejected.json" \
  GITHUB_ARTIFACT_RUN_ID=22 "$root/github-artifact-receipt.sh" >/dev/null 2>&1
status=$?
set -e
[[ $status -eq 42 ]]
rmdir "$work/content/unexpected"

(cd "$work/content" && zip -q "$work/artifact.zip" release.tar.gz)
archive_digest=$(sha256sum "$work/artifact.zip" | awk '{print $1}')
jq --arg digest "sha256:$archive_digest" '.githubDigest = $digest' \
  "$work/receipt.json" >"$work/download-receipt.json"
jq --arg digest "sha256:$archive_digest" '.digest = $digest' \
  "$work/metadata.json" >"$work/download-metadata.json"
PATH="$work/bin:$PATH" FAKE_CURL_METADATA="$work/download-metadata.json" \
  FAKE_CURL_ZIP="$work/artifact.zip" GH_TOKEN=test \
  GITHUB_ARTIFACT_DESTINATION="$work/download" \
  GITHUB_ARTIFACT_RECEIPT_FILE="$work/download-receipt.json" \
  "$root/download-github-artifact.sh"
cmp "$work/content/release.tar.gz" "$work/download/release.tar.gz"

PATH="$work/bin:$PATH" FAKE_CURL_METADATA="$work/download-metadata.json" \
  FAKE_CURL_ZIP="$work/artifact.zip" GH_TOKEN=test \
  GITHUB_ARTIFACT_DESTINATION="$work/discovered-download" \
  GITHUB_ARTIFACT_DIGEST="sha256:$archive_digest" GITHUB_ARTIFACT_ID=11 \
  GITHUB_ARTIFACT_NAME=exact-name GITHUB_ARTIFACT_RUN_ID=22 \
  "$root/download-github-artifact.sh"
cmp "$work/content/release.tar.gz" "$work/discovered-download/release.tar.gz"

jq '.digest = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"' \
  "$work/download-metadata.json" >"$work/changed-metadata.json"
set +e
PATH="$work/bin:$PATH" FAKE_CURL_METADATA="$work/changed-metadata.json" \
  FAKE_CURL_ZIP="$work/artifact.zip" GH_TOKEN=test \
  GITHUB_ARTIFACT_DESTINATION="$work/conflict" \
  GITHUB_ARTIFACT_RECEIPT_FILE="$work/download-receipt.json" \
  "$root/download-github-artifact.sh" >/dev/null 2>&1
status=$?
set -e
[[ $status -eq 42 ]]

jq '.expired = true' "$work/download-metadata.json" >"$work/expired-metadata.json"
set +e
PATH="$work/bin:$PATH" FAKE_CURL_METADATA="$work/expired-metadata.json" \
  FAKE_CURL_ZIP="$work/artifact.zip" GH_TOKEN=test \
  GITHUB_ARTIFACT_DESTINATION="$work/expired-download" \
  GITHUB_ARTIFACT_RECEIPT_FILE="$work/download-receipt.json" \
  "$root/download-github-artifact.sh" >/dev/null 2>&1
status=$?
set -e
[[ $status -eq 46 ]]
