#!/usr/bin/env bash

set -euo pipefail

fail() { echo "github-artifact-receipt: $*" >&2; exit 1; }
conflict() { echo "github-artifact-receipt: immutable artifact conflict: $*" >&2; exit 42; }

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  else shasum -a 256 "$1" | awk '{print $1}'; fi
}

required=(
  GH_TOKEN GITHUB_ARTIFACT_CONTENT_DIR GITHUB_ARTIFACT_ID GITHUB_ARTIFACT_NAME
  GITHUB_ARTIFACT_RECEIPT_FILE GITHUB_ARTIFACT_RUN_ID
)
for variable in "${required[@]}"; do
  [[ -n ${!variable:-} ]] || fail "Required value $variable is missing."
done
[[ $GITHUB_ARTIFACT_ID =~ ^[1-9][0-9]*$ && $GITHUB_ARTIFACT_RUN_ID =~ ^[1-9][0-9]*$ ]] ||
  conflict "the GitHub artifact IDs are invalid."
[[ -d $GITHUB_ARTIFACT_CONTENT_DIR ]] || fail "The artifact content directory is missing."
[[ -z $(find "$GITHUB_ARTIFACT_CONTENT_DIR" -mindepth 1 -maxdepth 1 ! -type f -print -quit) ]] ||
  conflict "the artifact contains a directory, link, or other non-file entry."

metadata=$(gh api "repos/temporalio/sdk-java/actions/artifacts/$GITHUB_ARTIFACT_ID") ||
  fail "The exact GitHub artifact is unavailable."
jq -e --argjson id "$GITHUB_ARTIFACT_ID" --argjson run "$GITHUB_ARTIFACT_RUN_ID" \
  --arg name "$GITHUB_ARTIFACT_NAME" \
  '.id == $id and .name == $name and .workflow_run.id == $run and .expired == false and
   (.digest | test("^sha256:[0-9a-f]{64}$")) and
   (.created_at | type == "string") and (.expires_at | type == "string")' \
  <<<"$metadata" >/dev/null || conflict "the GitHub artifact metadata differs."

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
files=$work/github-artifact-files.jsonl
: >"$files"
while IFS= read -r file; do
  [[ -f $file && ! -L $file ]] || conflict "the artifact contains a non-file entry."
  name=$(basename "$file")
  [[ $name =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || conflict "the artifact filename is invalid."
  sha=$(sha256_file "$file")
  size=$(wc -c <"$file" | tr -d ' ')
  jq -cn --arg name "$name" --arg sha256 "$sha" --argjson size "$size" \
    '{name:$name,sha256:$sha256,size:$size}' >>"$files"
done < <(find "$GITHUB_ARTIFACT_CONTENT_DIR" -mindepth 1 -maxdepth 1 -type f | sort)
[[ -s $files ]] || conflict "the artifact has no files."
jq -s --argjson artifactId "$GITHUB_ARTIFACT_ID" \
  --argjson workflowRunId "$GITHUB_ARTIFACT_RUN_ID" \
  --arg artifactName "$GITHUB_ARTIFACT_NAME" --arg githubDigest "$(jq -er .digest <<<"$metadata")" \
  --arg createdAt "$(jq -er .created_at <<<"$metadata")" \
  --arg expiresAt "$(jq -er .expires_at <<<"$metadata")" \
  '{artifactId:$artifactId,workflowRunId:$workflowRunId,artifactName:$artifactName,
    githubDigest:$githubDigest,createdAt:$createdAt,expiresAt:$expiresAt,files:.}' \
  "$files" >"$GITHUB_ARTIFACT_RECEIPT_FILE"
