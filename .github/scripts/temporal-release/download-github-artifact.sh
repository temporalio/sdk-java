#!/usr/bin/env bash

set -euo pipefail

fail() { echo "download-github-artifact: $*" >&2; exit 1; }
conflict() { echo "download-github-artifact: immutable artifact conflict: $*" >&2; exit 42; }
unavailable() { echo "download-github-artifact: exact artifact unavailable: $*" >&2; exit 46; }

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  else shasum -a 256 "$1" | awk '{print $1}'; fi
}

for variable in GH_TOKEN GITHUB_ARTIFACT_DESTINATION; do
  [[ -n ${!variable:-} ]] || fail "Required value $variable is missing."
done
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
receipt=${GITHUB_ARTIFACT_RECEIPT_FILE:-}
if [[ -n $receipt ]]; then
  jq -e '.artifactId > 0 and .workflowRunId > 0 and
    (.artifactName | test("^[A-Za-z0-9][A-Za-z0-9._-]*$")) and
    (.githubDigest | test("^sha256:[0-9a-f]{64}$")) and
    (.files | type == "array" and length > 0)' "$receipt" >/dev/null ||
    conflict "the Temporal artifact receipt is invalid."
  artifact_id=$(jq -er .artifactId "$receipt")
  workflow_run_id=$(jq -er .workflowRunId "$receipt")
  artifact_name=$(jq -er .artifactName "$receipt")
  github_digest=$(jq -er .githubDigest "$receipt")
  created_at=$(jq -er .createdAt "$receipt")
  expires_at=$(jq -er .expiresAt "$receipt")
else
  for variable in GITHUB_ARTIFACT_ID GITHUB_ARTIFACT_RUN_ID GITHUB_ARTIFACT_NAME GITHUB_ARTIFACT_DIGEST; do
    [[ -n ${!variable:-} ]] || fail "Required value $variable is missing."
  done
  [[ $GITHUB_ARTIFACT_ID =~ ^[1-9][0-9]*$ && $GITHUB_ARTIFACT_RUN_ID =~ ^[1-9][0-9]*$ ]] ||
    conflict "the discovered GitHub artifact IDs are invalid."
  [[ $GITHUB_ARTIFACT_NAME =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] ||
    conflict "the discovered GitHub artifact name is invalid."
  [[ $GITHUB_ARTIFACT_DIGEST =~ ^sha256:[0-9a-f]{64}$ ]] ||
    conflict "the discovered GitHub artifact digest is invalid."
  artifact_id=$GITHUB_ARTIFACT_ID
  workflow_run_id=$GITHUB_ARTIFACT_RUN_ID
  artifact_name=$GITHUB_ARTIFACT_NAME
  github_digest=$GITHUB_ARTIFACT_DIGEST
  created_at=
  expires_at=
fi
metadata_file=$work/metadata.json
status=$(curl --silent --show-error --location --output "$metadata_file" --write-out '%{http_code}' \
  --header "Authorization: Bearer $GH_TOKEN" --header 'Accept: application/vnd.github+json' \
  --header 'X-GitHub-Api-Version: 2022-11-28' \
  "https://api.github.com/repos/temporalio/sdk-java/actions/artifacts/$artifact_id") ||
  fail "GitHub artifact metadata is temporarily unavailable."
case $status in 200) ;; 404) unavailable "artifact $artifact_id was deleted." ;; *)
  fail "GitHub returned HTTP $status for artifact metadata." ;; esac
metadata=$(<"$metadata_file")
jq_args=(--argjson id "$artifact_id" --argjson run "$workflow_run_id" --arg name "$artifact_name"
  --arg digest "$github_digest")
metadata_filter='.id == $id and .workflow_run.id == $run and .name == $name and .digest == $digest'
if [[ -n $receipt ]]; then
  jq_args+=(--arg created "$created_at" --arg expires "$expires_at")
  metadata_filter+=' and .created_at == $created and .expires_at == $expires'
fi
jq -e "${jq_args[@]}" "$metadata_filter" <<<"$metadata" >/dev/null ||
  conflict "artifact $artifact_id immutable metadata changed."
[[ $(jq -r .expired <<<"$metadata") == false ]] || unavailable "artifact $artifact_id expired."

archive=$(mktemp)
status=$(curl --silent --show-error --location --output "$archive" --write-out '%{http_code}' \
  --header "Authorization: Bearer $GH_TOKEN" --header 'Accept: application/vnd.github+json' \
  --header 'X-GitHub-Api-Version: 2022-11-28' \
  "https://api.github.com/repos/temporalio/sdk-java/actions/artifacts/$artifact_id/zip") ||
  fail "The exact GitHub artifact download is temporarily unavailable."
case $status in 200) ;; 404 | 410) unavailable "artifact $artifact_id has no downloadable archive." ;; *)
  fail "GitHub returned HTTP $status for the artifact download." ;; esac
[[ "sha256:$(sha256_file "$archive")" == "$github_digest" ]] ||
  conflict "the downloaded GitHub artifact archive digest differs."
unzip -Z1 "$archive" | sort >"$work/archive-files.txt" ||
  conflict "the downloaded GitHub artifact is not a valid ZIP archive."
while IFS= read -r name; do
  [[ $name =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] ||
    conflict "the downloaded GitHub artifact contains an unsafe path."
done <"$work/archive-files.txt"
[[ -z $(uniq -d "$work/archive-files.txt") ]] ||
  conflict "the downloaded GitHub artifact contains duplicate entries."
if [[ -n $receipt ]]; then
  jq -r '.files[].name' "$receipt" | sort >"$work/receipt-files.txt"
  cmp "$work/receipt-files.txt" "$work/archive-files.txt" >/dev/null ||
    conflict "the downloaded GitHub artifact archive entries differ from Temporal state."
fi
mkdir -p "$GITHUB_ARTIFACT_DESTINATION"
[[ -z $(find "$GITHUB_ARTIFACT_DESTINATION" -mindepth 1 -print -quit) ]] ||
  fail "The artifact destination is not empty."
unzip -q "$archive" -d "$GITHUB_ARTIFACT_DESTINATION"
actual=$work/downloaded-artifact-files.jsonl
: >"$actual"
while IFS= read -r file; do
  [[ -f $file && ! -L $file ]] || conflict "the downloaded artifact has an invalid entry."
  name=$(basename "$file")
  [[ $file == "$GITHUB_ARTIFACT_DESTINATION/$name" ]] ||
    conflict "the downloaded artifact contains a directory."
  jq -cn --arg name "$name" --arg sha256 "$(sha256_file "$file")" \
    --argjson size "$(wc -c <"$file" | tr -d ' ')" \
    '{name:$name,sha256:$sha256,size:$size}' >>"$actual"
done < <(find "$GITHUB_ARTIFACT_DESTINATION" -mindepth 1 -type f | sort)
if [[ -n $receipt ]]; then
  jq -S '.files | sort_by(.name)' "$receipt" >"$work/expected-artifact-files.json"
  jq -sS 'sort_by(.name)' "$actual" >"$work/actual-artifact-files.json"
  cmp "$work/expected-artifact-files.json" "$work/actual-artifact-files.json" >/dev/null ||
    conflict "the downloaded artifact files differ from Temporal state."
fi
