#!/usr/bin/env bash

set -euo pipefail

: "${MOCK_GH_STATE_DIR:?MOCK_GH_STATE_DIR is required.}"

mkdir -p "$MOCK_GH_STATE_DIR/assets" "$MOCK_GH_STATE_DIR/omit-digest"
touch "$MOCK_GH_STATE_DIR/calls.log"
printf '%q ' "$@" >>"$MOCK_GH_STATE_DIR/calls.log"
printf '\n' >>"$MOCK_GH_STATE_DIR/calls.log"

release_file="$MOCK_GH_STATE_DIR/release.json"
tag_file="$MOCK_GH_STATE_DIR/tag-sha"
tag_type_file="$MOCK_GH_STATE_DIR/tag-type"
annotated_target_file="$MOCK_GH_STATE_DIR/annotated-target-sha"

sha256_file() {
  local file=$1

  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file" | awk '{print $1}'
  else
    shasum -a 256 "$file" | awk '{print $1}'
  fi
}

release_json() {
  local assets_json='[]'
  local asset
  local name
  local size
  local digest

  for asset in "$MOCK_GH_STATE_DIR"/assets/*; do
    [[ -f "$asset" ]] || continue
    name=$(basename -- "$asset")
    size=$(stat -f '%z' "$asset" 2>/dev/null || stat -c '%s' "$asset")
    digest=$(sha256_file "$asset")
    if [[ -f "$MOCK_GH_STATE_DIR/omit-digest/$name" ]]; then
      assets_json=$(
        jq \
          --arg name "$name" \
          --argjson size "$size" \
          '. + [{name: $name, size: $size, state: "uploaded", digest: null}]' \
          <<<"$assets_json"
      )
    else
      assets_json=$(
        jq \
          --arg name "$name" \
          --argjson size "$size" \
          --arg digest "sha256:${digest}" \
          '. + [{name: $name, size: $size, state: "uploaded", digest: $digest}]' \
          <<<"$assets_json"
      )
    fi
  done

  jq --argjson assets "$assets_json" '. + {assets: $assets}' "$release_file"
}

argument_value() {
  local requested=$1
  shift

  while [[ $# -gt 0 ]]; do
    if [[ "$1" == "$requested" ]]; then
      [[ $# -ge 2 ]] || exit 2
      printf '%s' "$2"
      return 0
    fi
    shift
  done

  return 1
}

has_argument() {
  local requested=$1
  shift
  local argument

  for argument in "$@"; do
    [[ "$argument" == "$requested" ]] && return 0
  done

  return 1
}

resolved_tag_sha() {
  local tag_type=commit

  [[ -f "$tag_file" ]] || return 1
  if [[ -f "$tag_type_file" ]]; then
    tag_type=$(<"$tag_type_file")
  fi

  case "$tag_type" in
    commit)
      cat "$tag_file"
      ;;
    tag)
      [[ -f "$annotated_target_file" ]] || return 1
      cat "$annotated_target_file"
      ;;
    *)
      return 1
      ;;
  esac
}

command=${1:-}
subcommand=${2:-}

if [[ "$command" == "release" && "$subcommand" == "view" ]]; then
  [[ -f "$release_file" ]] || {
    echo "release not found." >&2
    exit 1
  }
  [[ "${3:-}" == "$(jq -r '.tagName' "$release_file")" ]] || exit 2
  [[ "$(argument_value --repo "$@")" == "temporalio/sdk-java" ]] || exit 2
  release_json
  exit 0
fi

if [[ "$command" == "release" && "$subcommand" == "create" ]]; then
  tag=${3:?A tag is required.}
  [[ ! -f "$release_file" ]] || {
    echo "release already exists." >&2
    exit 1
  }

  target=$(argument_value --target "$@")
  title=$(argument_value --title "$@")
  notes_file=$(argument_value --notes-file "$@")
  body=$(<"$notes_file")
  if has_argument --verify-tag "$@"; then
    [[ "$(resolved_tag_sha)" == "$target" ]] || exit 1
  fi
  prerelease=false
  if has_argument --prerelease "$@"; then
    prerelease=true
  fi

  jq -n \
    --arg tag "$tag" \
    --arg target "$target" \
    --arg title "$title" \
    --arg body "$body" \
    --arg url "https://github.example.test/releases/tag/${tag}" \
    --argjson prerelease "$prerelease" \
    '{
      tagName: $tag,
      targetCommitish: $target,
      isDraft: true,
      isPrerelease: $prerelease,
      name: $title,
      body: $body,
      url: $url
    }' >"$release_file"
  exit 0
fi

if [[ "$command" == "release" && "$subcommand" == "edit" ]]; then
  [[ -f "$release_file" ]] || exit 1
  [[ "${3:-}" == "$(jq -r '.tagName' "$release_file")" ]] || exit 2
  [[ "$(argument_value --repo "$@")" == "temporalio/sdk-java" ]] || exit 2

  if target=$(argument_value --target "$@" 2>/dev/null); then
    jq --arg target "$target" '.targetCommitish = $target' \
      "$release_file" >"$release_file.tmp"
    mv "$release_file.tmp" "$release_file"
  fi
  if title=$(argument_value --title "$@" 2>/dev/null); then
    jq --arg title "$title" '.name = $title' \
      "$release_file" >"$release_file.tmp"
    mv "$release_file.tmp" "$release_file"
  fi
  if notes_file=$(argument_value --notes-file "$@" 2>/dev/null); then
    body=$(<"$notes_file")
    jq --arg body "$body" '.body = $body' \
      "$release_file" >"$release_file.tmp"
    mv "$release_file.tmp" "$release_file"
  fi
  if has_argument --prerelease "$@"; then
    jq '.isPrerelease = true' "$release_file" >"$release_file.tmp"
    mv "$release_file.tmp" "$release_file"
  fi

  if has_argument --draft=false "$@"; then
    publish_failure_file=${MOCK_GH_PUBLISH_FAILURES_FILE:-}
    if [[ -n "$publish_failure_file" && -f "$publish_failure_file" ]]; then
      failures_remaining=$(<"$publish_failure_file")
      if [[ "$failures_remaining" -gt 0 ]]; then
        printf '%s\n' "$((failures_remaining - 1))" >"$publish_failure_file"
        echo "simulated publish failure." >&2
        exit 1
      fi
    fi
    [[ "$(resolved_tag_sha)" == "$(jq -r '.targetCommitish' "$release_file")" ]] || exit 1
    jq '.isDraft = false' "$release_file" >"$release_file.tmp"
    mv "$release_file.tmp" "$release_file"
  fi
  exit 0
fi

if [[ "$command" == "release" && "$subcommand" == "upload" ]]; then
  asset=${4:?An asset is required.}
  failure_file=${MOCK_GH_UPLOAD_FAILURES_FILE:-}

  if [[ -n "$failure_file" && -f "$failure_file" ]]; then
    failures_remaining=$(<"$failure_file")
    if [[ "$failures_remaining" -gt 0 ]]; then
      printf '%s\n' "$((failures_remaining - 1))" >"$failure_file"
      echo "simulated upload failure." >&2
      exit 1
    fi
  fi

  cp "$asset" "$MOCK_GH_STATE_DIR/assets/$(basename -- "$asset")"
  rm -f "$MOCK_GH_STATE_DIR/omit-digest/$(basename -- "$asset")"
  exit 0
fi

if [[ "$command" == "api" ]]; then
  if [[ "${2:-}" == "--method" && "${3:-}" == "POST" ]]; then
    [[ "${4:-}" == "repos/temporalio/sdk-java/git/refs" ]] || exit 2
    requested_ref=$(argument_value -f "$@")
    requested_ref=${requested_ref#ref=}
    [[ "$requested_ref" == refs/tags/* ]] || exit 2

    requested_sha=
    previous=
    for argument in "$@"; do
      if [[ "$previous" == "-f" && "$argument" == sha=* ]]; then
        requested_sha=${argument#sha=}
      fi
      previous=$argument
    done
    [[ "$requested_sha" =~ ^[0-9a-f]{40}$ ]] || exit 2
    [[ "$requested_ref" =~ ^refs/tags/v[0-9]+\.[0-9]+\.[0-9]+(-RC[0-9]+)?$ ]] || exit 2
    [[ ! -f "$tag_file" ]] || exit 1
    printf '%s\n' "$requested_sha" >"$tag_file"
    printf 'commit\n' >"$tag_type_file"
    rm -f "$annotated_target_file"
    jq -n \
      --arg ref "$requested_ref" \
      --arg sha "$requested_sha" \
      '{ref: $ref, object: {type: "commit", sha: $sha}}'
    exit 0
  fi

  if [[ "${2:-}" == repos/temporalio/sdk-java/git/ref/tags/* ]]; then
    [[ -f "$tag_file" ]] || exit 1
    requested_tag=${2#repos/temporalio/sdk-java/git/ref/tags/}
    if [[ -f "$release_file" ]]; then
      [[ "$requested_tag" == "$(jq -r '.tagName' "$release_file")" ]] || exit 2
    fi
    tag_type=commit
    if [[ -f "$tag_type_file" ]]; then
      tag_type=$(<"$tag_type_file")
    fi
    jq -n \
      --arg ref "refs/tags/${requested_tag}" \
      --arg type "$tag_type" \
      --arg sha "$(<"$tag_file")" \
      '{ref: $ref, object: {type: $type, sha: $sha}}'
    exit 0
  fi

  if [[ "${2:-}" == repos/temporalio/sdk-java/git/tags/* ]]; then
    [[ -f "$tag_file" && -f "$tag_type_file" && -f "$annotated_target_file" ]] || exit 1
    [[ "$(<"$tag_type_file")" == "tag" ]] || exit 2
    [[ "${2#repos/temporalio/sdk-java/git/tags/}" == "$(<"$tag_file")" ]] || exit 2
    jq -n \
      --arg sha "$(<"$annotated_target_file")" \
      '{object: {type: "commit", sha: $sha}}'
    exit 0
  fi

  exit 2
fi

echo "mock-gh: unsupported command: $*" >&2
exit 2
