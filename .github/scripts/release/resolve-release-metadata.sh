#!/usr/bin/env bash

set -euo pipefail

usage() {
  echo "Usage: $0 BASE_SHA HEAD_SHA GITHUB_OUTPUT" >&2
}

fail() {
  echo "resolve-release-metadata: $*" >&2
  exit 1
}

if [[ $# -ne 3 ]]; then
  usage
  exit 2
fi

base_ref=$1
head_ref=$2
github_output=$3

git rev-parse --is-inside-work-tree >/dev/null 2>&1 ||
  fail "The current directory is not a Git worktree."

base_sha=$(git rev-parse --verify "${base_ref}^{commit}" 2>/dev/null) ||
  fail "BASE_SHA does not identify a commit: ${base_ref}."
head_sha=$(git rev-parse --verify "${head_ref}^{commit}" 2>/dev/null) ||
  fail "HEAD_SHA does not identify a commit: ${head_ref}."

git merge-base --is-ancestor "$base_sha" "$head_sha" ||
  fail "BASE_SHA must be an ancestor of HEAD_SHA."

diff_fields=()
while IFS= read -r -d '' field; do
  diff_fields+=("$field")
done < <(
  git diff \
    --name-status \
    --no-renames \
    -z \
    "$base_sha" \
    "$head_sha" \
    -- releases/
)

if [[ ${#diff_fields[@]} -ne 2 ]]; then
  fail \
    "The release range must contain exactly one release-file change; found $(( ${#diff_fields[@]} / 2 ))."
fi

status=${diff_fields[0]}
notes_file=${diff_fields[1]}

[[ "$status" == "A" ]] ||
  fail "The only release-file change must add a new file; found status ${status} for ${notes_file}."

if [[ ! "$notes_file" =~ ^releases/(v[0-9]+\.[0-9]+\.[0-9]+(-RC[0-9]+)?)$ ]]; then
  fail "The release notes filename is invalid: ${notes_file}."
fi

tag=${BASH_REMATCH[1]}
version=${tag#v}

tree_entry=$(git ls-tree "$head_sha" -- "$notes_file")
read -r notes_mode notes_type _ <<<"$tree_entry"
[[ "$notes_mode" == "100644" && "$notes_type" == "blob" ]] ||
  fail "The release notes path must be a regular, nonexecutable Git file: ${notes_file}."

notes_size=$(git cat-file -s "${head_sha}:${notes_file}" 2>/dev/null) ||
  fail "The release notes file is not present at HEAD_SHA: ${notes_file}."
[[ "$notes_size" -gt 0 ]] ||
  fail "The release notes file must not be empty: ${notes_file}."

if git show-ref --verify --quiet "refs/tags/${tag}"; then
  fail "The release tag already exists: ${tag}."
fi

if [[ "$tag" == *-RC* ]]; then
  prerelease=true
else
  prerelease=false
fi

output_dir=$(dirname -- "$github_output")
[[ -d "$output_dir" ]] ||
  fail "The GitHub output directory does not exist: ${output_dir}."

{
  printf 'tag=%s\n' "$tag"
  printf 'version=%s\n' "$version"
  printf 'notes_file=%s\n' "$notes_file"
  printf 'commit=%s\n' "$head_sha"
  printf 'prerelease=%s\n' "$prerelease"
} >>"$github_output"
