#!/usr/bin/env bash

set -euo pipefail

usage() {
  echo "Usage: $0 TAG SHA NOTES_FILE ASSET_DIR GITHUB_OUTPUT" >&2
}

fail() {
  echo "publish-github-release: $*" >&2
  exit 1
}

is_nonnegative_integer() {
  [[ "$1" =~ ^[0-9]+$ ]]
}

sleep_before_retry() {
  local attempt=$1
  local description=$2

  if [[ "$attempt" -lt "$max_attempts" ]]; then
    echo \
      "publish-github-release: ${description}; retrying in ${retry_delay_seconds}s (${attempt}/${max_attempts})." \
      >&2
    if [[ "$retry_delay_seconds" -gt 0 ]]; then
      sleep "$retry_delay_seconds"
    fi
  fi
}

sha256_file() {
  local file=$1

  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$file" | awk '{print $1}'
  else
    fail "Neither sha256sum nor shasum is available."
  fi
}

file_size() {
  local file=$1

  wc -c <"$file" | tr -d '[:space:]'
}

lowercase() {
  printf '%s' "$1" | tr '[:upper:]' '[:lower:]'
}

fetch_release_once() {
  local json

  if ! json=$(
    "$gh_bin" release view "$tag" \
      --repo "$repository" \
      --json tagName,targetCommitish,isDraft,isPrerelease,name,body,url,assets \
      2>"$gh_error_file"
  ); then
    return 1
  fi

  if ! jq -e '
    (.tagName | type == "string")
    and (.targetCommitish | type == "string")
    and (.isDraft | type == "boolean")
    and (.isPrerelease | type == "boolean")
    and (.name | type == "string")
    and (.body | type == "string")
    and (.url | type == "string")
    and (.assets | type == "array")
  ' >/dev/null 2>&1 <<<"$json"; then
    echo "GitHub returned malformed release metadata." >"$gh_error_file"
    return 1
  fi

  printf '%s' "$json"
}

validate_release_identity() {
  local json=$1
  local actual_tag
  local actual_target
  local actual_prerelease
  local actual_name

  actual_tag=$(jq -r '.tagName' <<<"$json")
  actual_target=$(jq -r '.targetCommitish' <<<"$json")
  actual_prerelease=$(jq -r '.isPrerelease' <<<"$json")
  actual_name=$(jq -r '.name' <<<"$json")

  [[ "$actual_tag" == "$tag" ]] ||
    fail "Existing release tag ${actual_tag} does not match ${tag}."
  [[ "$(lowercase "$actual_target")" == "$commit_sha" ]] ||
    fail "Existing release ${tag} targets ${actual_target}, not ${commit_sha}."
  [[ "$actual_prerelease" == "$expected_prerelease" ]] ||
    fail "Existing release ${tag} has prerelease=${actual_prerelease}, expected ${expected_prerelease}."
  [[ "$actual_name" == "$tag" ]] ||
    fail "Existing release ${tag} has title ${actual_name}, expected ${tag}."
}

release_body_is_exact() {
  local json=$1
  local actual_body

  # GitHub may return Markdown bodies with CRLF even when the notes file uses
  # LF. Normalize line endings without weakening the content comparison.
  actual_body=$(jq -r '.body' <<<"$json" | tr -d '\r')
  [[ "$actual_body" == "$expected_notes" ]]
}

retry_command() {
  local description=$1
  shift

  local attempt
  for ((attempt = 1; attempt <= max_attempts; attempt++)); do
    if "$@"; then
      return 0
    fi
    sleep_before_retry "$attempt" "$description"
  done

  return 1
}

load_remote_assets() {
  local json=$1
  local name
  local size
  local state
  local digest

  remote_names=()
  remote_sizes=()
  remote_states=()
  remote_digests=()
  remote_count=0

  while IFS=$'\t' read -r name size state digest; do
    [[ -n "$name" ]] || continue
    [[ "$name" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] ||
      fail "Release ${tag} contains an unsupported asset name: ${name}."
    find_expected_index "$name" >/dev/null ||
      fail "Release ${tag} contains unexpected asset ${name}."
    if find_remote_index "$name" >/dev/null; then
      fail "Release ${tag} contains duplicate asset ${name}."
    fi

    remote_names[$remote_count]=$name
    remote_sizes[$remote_count]=$size
    remote_states[$remote_count]=$(lowercase "$state")
    remote_digests[$remote_count]=$digest
    remote_count=$((remote_count + 1))
  done < <(
    jq -r '
      .assets[]
      | [
          .name,
          (.size | tostring),
          (.state // ""),
          (.digest // "")
        ]
      | @tsv
    ' <<<"$json"
  )
}

find_expected_index() {
  local requested=$1
  local index

  for ((index = 0; index < ${#expected_names[@]}; index++)); do
    if [[ "${expected_names[$index]}" == "$requested" ]]; then
      printf '%s' "$index"
      return 0
    fi
  done

  return 1
}

find_remote_index() {
  local requested=$1
  local index

  for ((index = 0; index < remote_count; index++)); do
    if [[ "${remote_names[$index]}" == "$requested" ]]; then
      printf '%s' "$index"
      return 0
    fi
  done

  return 1
}

asset_is_exact() {
  local name=$1
  local digest
  local expected_index
  local remote_index

  expected_index=$(find_expected_index "$name") || return 1
  remote_index=$(find_remote_index "$name") || return 1
  [[ "${remote_sizes[$remote_index]}" == "${expected_sizes[$expected_index]}" ]] || return 1
  [[ "${remote_states[$remote_index]}" == "uploaded" ]] || return 1

  digest=$(lowercase "${remote_digests[$remote_index]}")
  [[ "$digest" == "sha256:${expected_digests[$expected_index]}" ]] || return 1

  return 0
}

manifest_is_exact() {
  local asset
  local name

  [[ "$remote_count" -eq "$asset_count" ]] || return 1
  for asset in "${assets[@]}"; do
    name=$(basename -- "$asset")
    asset_is_exact "$name" || return 1
  done

  return 0
}

checksum_name_is_present() {
  local requested=$1
  local index

  for ((index = 0; index < checksum_count; index++)); do
    [[ "${checksum_names[$index]}" == "$requested" ]] && return 0
  done

  return 1
}

validate_checksum_manifest() {
  local manifest_path="${asset_dir}/SHA256SUMS"
  local line
  local checksum
  local remainder
  local filename
  local name
  local expected_index

  find_expected_index SHA256SUMS >/dev/null ||
    fail "The asset directory must contain SHA256SUMS."
  checksum_names=()
  checksum_count=0

  while IFS= read -r line || [[ -n "$line" ]]; do
    checksum=${line%% *}
    remainder=${line#"$checksum"}
    [[ "$checksum" =~ ^[0-9A-Fa-f]{64}$ ]] ||
      fail "SHA256SUMS contains an invalid checksum line."

    case "${remainder:0:2}" in
      "  " | " *")
        filename=${remainder:2}
        ;;
      *)
        fail "SHA256SUMS contains an unsupported checksum line."
        ;;
    esac

    filename=${filename#./}
    [[ "$filename" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] ||
      fail "SHA256SUMS contains an unsupported filename: ${filename}."
    [[ "$filename" != "SHA256SUMS" ]] ||
      fail "SHA256SUMS must not contain a checksum for itself."

    expected_index=$(find_expected_index "$filename") ||
      fail "SHA256SUMS contains unexpected asset ${filename}."
    if checksum_name_is_present "$filename"; then
      fail "SHA256SUMS contains duplicate asset ${filename}."
    fi

    checksum=$(lowercase "$checksum")
    [[ "$checksum" == "${expected_digests[$expected_index]}" ]] ||
      fail "SHA256SUMS does not match asset ${filename}."
    checksum_names[$checksum_count]=$filename
    checksum_count=$((checksum_count + 1))
  done <"$manifest_path"

  [[ "$checksum_count" -eq $(( ${#expected_names[@]} - 1 )) ]] ||
    fail "SHA256SUMS does not list every release archive exactly once."

  for name in "${expected_names[@]}"; do
    [[ "$name" == "SHA256SUMS" ]] && continue
    checksum_name_is_present "$name" ||
      fail "SHA256SUMS does not list release asset ${name}."
  done
}

resolve_tag_ref_once() {
  local object_json
  local object_type
  local object_sha
  local depth=0

  if ! object_json=$(
    "$gh_bin" api "repos/${repository}/git/ref/tags/${tag}" 2>"$gh_error_file"
  ); then
    return 1
  fi

  if ! jq -e '
    (.object.type | type == "string")
    and (.object.sha | type == "string")
  ' >/dev/null 2>&1 <<<"$object_json"; then
    return 2
  fi
  object_type=$(jq -r '.object.type' <<<"$object_json")
  object_sha=$(lowercase "$(jq -r '.object.sha' <<<"$object_json")")

  while [[ "$object_type" == "tag" && "$depth" -lt 16 ]]; do
    if ! object_json=$(
      "$gh_bin" api "repos/${repository}/git/tags/${object_sha}" 2>"$gh_error_file"
    ); then
      return 1
    fi
    if ! jq -e '
      (.object.type | type == "string")
      and (.object.sha | type == "string")
    ' >/dev/null 2>&1 <<<"$object_json"; then
      return 2
    fi
    object_type=$(jq -r '.object.type' <<<"$object_json")
    object_sha=$(lowercase "$(jq -r '.object.sha' <<<"$object_json")")
    depth=$((depth + 1))
  done

  [[ "$object_type" == "commit" && "$object_sha" =~ ^[0-9a-f]{40}$ ]] ||
    return 2
  printf '%s' "$object_sha"
}

verify_remote_tag_once() {
  local resolved_sha
  local resolve_status

  if resolved_sha=$(resolve_tag_ref_once); then
    [[ "$resolved_sha" == "$commit_sha" ]] ||
      fail "Tag ${tag} resolves to ${resolved_sha}, not ${commit_sha}."
    return 0
  else
    resolve_status=$?
  fi

  [[ "$resolve_status" -eq 1 ]] ||
    fail "GitHub returned malformed or unsupported tag data for ${tag}."
  return 1
}

verify_remote_tag() {
  local attempt

  for ((attempt = 1; attempt <= max_attempts; attempt++)); do
    if verify_remote_tag_once; then
      return 0
    fi
    sleep_before_retry "$attempt" "unable to resolve published tag ${tag}"
  done

  fail "Unable to verify the target of published tag ${tag}."
}

ensure_remote_tag() {
  local attempt
  local created_json

  for ((attempt = 1; attempt <= max_attempts; attempt++)); do
    if verify_remote_tag_once; then
      return 0
    fi

    if created_json=$(
      "$gh_bin" api \
        --method POST \
        "repos/${repository}/git/refs" \
        -f "ref=refs/tags/${tag}" \
        -f "sha=${commit_sha}" \
        2>"$gh_error_file"
    ); then
      if ! jq -e \
        --arg ref "refs/tags/${tag}" \
        --arg sha "$commit_sha" \
        '
          .ref == $ref
          and .object.type == "commit"
          and ((.object.sha | ascii_downcase) == $sha)
        ' >/dev/null 2>&1 <<<"$created_json"; then
        fail "GitHub returned unexpected data after creating tag ${tag}."
      fi
    fi

    if verify_remote_tag_once; then
      return 0
    fi
    sleep_before_retry "$attempt" "unable to create or verify tag ${tag}"
  done

  fail "Unable to create or verify tag ${tag} at ${commit_sha}."
}

write_release_url() {
  local json=$1
  local release_url

  release_url=$(jq -r '.url' <<<"$json")
  [[ "$release_url" == https://* ]] ||
    fail "GitHub returned an invalid release URL: ${release_url}."
  printf 'url=%s\n' "$release_url" >>"$github_output"
}

if [[ $# -ne 5 ]]; then
  usage
  exit 2
fi

tag=$1
commit_sha=$(lowercase "$2")
notes_file=$3
asset_dir=$4
github_output=$5

repository=${GITHUB_REPOSITORY:-}
gh_bin=${GH_BIN:-gh}
max_attempts=${RELEASE_MAX_ATTEMPTS:-10}
retry_delay_seconds=${RELEASE_RETRY_DELAY_SECONDS:-10}

[[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-RC[0-9]+)?$ ]] ||
  fail "The release tag is invalid: ${tag}."
[[ "$commit_sha" =~ ^[0-9a-f]{40}$ ]] ||
  fail "SHA must be a full 40-character commit SHA."
[[ "$repository" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] ||
  fail "GITHUB_REPOSITORY must have the form OWNER/REPOSITORY."
[[ -f "$notes_file" && ! -L "$notes_file" && -s "$notes_file" ]] ||
  fail "The release notes file must be a nonempty regular file: ${notes_file}."
[[ -d "$asset_dir" ]] ||
  fail "The asset directory does not exist: ${asset_dir}."
is_nonnegative_integer "$max_attempts" && [[ "$max_attempts" -gt 0 ]] ||
  fail "RELEASE_MAX_ATTEMPTS must be a positive integer."
is_nonnegative_integer "$retry_delay_seconds" ||
  fail "RELEASE_RETRY_DELAY_SECONDS must be a nonnegative integer."
command -v "$gh_bin" >/dev/null 2>&1 ||
  fail "The GitHub CLI is not available: ${gh_bin}."
command -v jq >/dev/null 2>&1 ||
  fail "jq is required."

expected_notes=$(tr -d '\r' <"$notes_file")
output_dir=$(dirname -- "$github_output")
[[ -d "$output_dir" ]] ||
  fail "The GitHub output directory does not exist: ${output_dir}."

if [[ "$tag" == *-RC* ]]; then
  expected_prerelease=true
else
  expected_prerelease=false
fi

LC_ALL=C
export LC_ALL
shopt -s dotglob nullglob
assets=()
asset_count=0

for entry in "$asset_dir"/*; do
  [[ -f "$entry" && ! -L "$entry" ]] ||
    fail "The asset directory may contain only regular files: ${entry}."
  [[ -s "$entry" ]] ||
    fail "Release assets must not be empty: ${entry}."

  asset_name=$(basename -- "$entry")
  [[ "$asset_name" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] ||
    fail "The asset filename is unsupported: ${asset_name}."
  assets[$asset_count]=$entry
  asset_count=$((asset_count + 1))
done

[[ "$asset_count" -gt 0 ]] ||
  fail "The asset directory must contain at least one asset."

expected_names=()
expected_sizes=()
expected_digests=()
remote_names=()
remote_sizes=()
remote_states=()
remote_digests=()

for asset in "${assets[@]}"; do
  asset_name=$(basename -- "$asset")
  expected_names+=("$asset_name")
  expected_sizes+=("$(file_size "$asset")")
  expected_digests+=("$(sha256_file "$asset")")
done
validate_checksum_manifest

temp_dir=$(mktemp -d)
trap 'rm -rf -- "$temp_dir"' EXIT
gh_error_file="$temp_dir/gh-error"

ensure_remote_tag

release_json=
release_found=false
create_args=(
  release create "$tag"
  --repo "$repository"
  --draft
  --title "$tag"
  --target "$commit_sha"
  --notes-file "$notes_file"
  --verify-tag
)
if [[ "$expected_prerelease" == "true" ]]; then
  create_args+=(--prerelease)
fi

for ((attempt = 1; attempt <= max_attempts; attempt++)); do
  if release_json=$(fetch_release_once); then
    release_found=true
    break
  fi

  if "$gh_bin" "${create_args[@]}" >/dev/null 2>"$gh_error_file"; then
    if release_json=$(fetch_release_once); then
      release_found=true
      break
    fi
  fi

  sleep_before_retry "$attempt" "unable to create or resume draft release ${tag}"
done

[[ "$release_found" == "true" ]] ||
  fail "Unable to create or resume draft release ${tag}."
validate_release_identity "$release_json"

if [[ "$(jq -r '.isDraft' <<<"$release_json")" == "false" ]]; then
  release_body_is_exact "$release_json" ||
    fail "Published release ${tag} does not contain the requested release notes."
  load_remote_assets "$release_json"
  manifest_is_exact ||
    fail "Published release ${tag} does not contain the exact requested asset set."
  verify_remote_tag
  write_release_url "$release_json"
  echo "GitHub release ${tag} is already published with the requested assets."
  exit 0
fi

edit_args=(
  release edit "$tag"
  --repo "$repository"
  --draft
  --title "$tag"
  --target "$commit_sha"
  --notes-file "$notes_file"
)
if [[ "$expected_prerelease" == "true" ]]; then
  edit_args+=(--prerelease)
fi
retry_command \
  "unable to refresh draft release ${tag}" \
  "$gh_bin" "${edit_args[@]}" >/dev/null ||
  fail "Unable to refresh draft release ${tag}."

assets_verified=false
for ((attempt = 1; attempt <= max_attempts; attempt++)); do
  if release_json=$(fetch_release_once); then
    validate_release_identity "$release_json"
    [[ "$(jq -r '.isDraft' <<<"$release_json")" == "true" ]] ||
      fail "Release ${tag} was published before asset verification completed."

    if ! release_body_is_exact "$release_json"; then
      sleep_before_retry "$attempt" "release notes for draft release ${tag} are not yet current"
      continue
    fi

    load_remote_assets "$release_json"
    if manifest_is_exact; then
      assets_verified=true
      break
    fi

    for asset in "${assets[@]}"; do
      asset_name=$(basename -- "$asset")
      if ! asset_is_exact "$asset_name"; then
        "$gh_bin" release upload "$tag" "$asset" \
          --repo "$repository" \
          --clobber \
          >/dev/null 2>"$gh_error_file" || true
      fi
    done

    if release_json=$(fetch_release_once); then
      validate_release_identity "$release_json"
      if release_body_is_exact "$release_json"; then
        load_remote_assets "$release_json"
        if manifest_is_exact; then
          assets_verified=true
          break
        fi
      fi
    fi
  fi

  sleep_before_retry "$attempt" "assets for draft release ${tag} are not yet complete"
done

[[ "$assets_verified" == "true" ]] ||
  fail "Unable to upload and verify the exact asset set for release ${tag}."

published=false
for ((attempt = 1; attempt <= max_attempts; attempt++)); do
  if ! verify_remote_tag_once; then
    sleep_before_retry "$attempt" "unable to verify tag ${tag} immediately before publication"
    continue
  fi

  "$gh_bin" release edit "$tag" \
    --repo "$repository" \
    --draft=false \
    >/dev/null 2>"$gh_error_file" || true

  if release_json=$(fetch_release_once); then
    validate_release_identity "$release_json"
    if [[ "$(jq -r '.isDraft' <<<"$release_json")" == "false" ]]; then
      release_body_is_exact "$release_json" ||
        fail "Release ${tag} notes changed while the release was being published."
      load_remote_assets "$release_json"
      manifest_is_exact ||
        fail "Release ${tag} assets changed while the release was being published."
      published=true
      break
    fi
  fi

  sleep_before_retry "$attempt" "release ${tag} is not yet published"
done

[[ "$published" == "true" ]] ||
  fail "Unable to publish release ${tag}."

verify_remote_tag
write_release_url "$release_json"
echo "Published GitHub release ${tag} at ${commit_sha}."
