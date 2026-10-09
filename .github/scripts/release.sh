#!/usr/bin/env bash
set -euo pipefail
shopt -s nullglob

# Reports a workflow-formatted error and exits.
fail() {
  echo "::error::$*" >&2
  exit 1
}

# Fails when a required environment variable is empty.
require() {
  [[ -n "${!1:-}" ]] || fail "$1 is required."
}

# Writes release metadata to GitHub Actions or standard output.
write_output() {
  if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    printf '%s=%s\n' "$1" "$2" >> "$GITHUB_OUTPUT"
  else
    printf '%s=%s\n' "$1" "$2"
  fi
}

# Lists the valid version headings at a given commit.
changelog_versions() {
  git show "$1:CHANGELOG.md" \
    | sed -nE 's/^## \[([0-9]+\.[0-9]+\.[0-9]+(-RC[0-9]+)?)\] - [0-9]{4}-[0-9]{2}-[0-9]{2}$/\1/p' \
    | sort
}

# Extracts one version's changelog section at a given commit.
changelog_section() {
  git show "$1:CHANGELOG.md" | awk -v heading="## [$2]" -v include="${3:-false}" '
    !seen && index($0, heading) == 1 {
      seen = 1
      capture = 1
      if (include == "true") lines[++count] = $0
      next
    }
    capture && /^## / { capture = 0 }
    capture { lines[++count] = $0 }
    END {
      first = 1
      while (first <= count && lines[first] == "") first++
      while (count >= first && lines[count] == "") count--
      for (line = first; line <= count; line++) print lines[line]
    }
  '
}

# Validates a changelog transition and identifies a release candidate.
candidate() {
  require EVENT_NAME
  require HEAD_SHA
  local notes=${1:?Release notes output path is required.}
  local base dispatch_head draft_release existing release_commit tag version

  if [[ "$EVENT_NAME" == workflow_dispatch ]]; then
    require MANUAL_DRAFT_RELEASE
    require MANUAL_REF
    require MANUAL_VERSION
    [[ "$MANUAL_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-RC[0-9]+)?$ ]] \
      || fail "Manual release version must look like 1.2.3 or 1.2.3-RC1."
    case "$MANUAL_DRAFT_RELEASE" in
      true) draft_release=1 ;;
      false) draft_release=0 ;;
      *) fail "Manual draft selection must be true or false." ;;
    esac

    dispatch_head=$(git rev-parse --verify "$HEAD_SHA^{commit}")
    release_commit=$(git log -1 --first-parent --format=%H --fixed-strings \
      -S"## [$MANUAL_VERSION]" "$dispatch_head" -- CHANGELOG.md)
    [[ -n "$release_commit" ]] \
      || fail "Version $MANUAL_VERSION was not introduced on $MANUAL_REF."
    BASE_SHA=$(git rev-parse --verify "$release_commit^1")
    HEAD_SHA=$release_commit
  else
    require BASE_SHA
  fi

  if [[ "$BASE_SHA" =~ ^0+$ ]]; then
    BASE_SHA=$(git rev-parse "$HEAD_SHA^")
  fi
  base=$(git rev-parse --verify "$BASE_SHA^{commit}")
  head=$(git rev-parse --verify "$HEAD_SHA^{commit}")
  if [[ "$EVENT_NAME" == pull_request ]]; then
    base=$(git merge-base "$base" "$head") \
      || fail "The pull request branches must have a common ancestor."
  else
    git merge-base --is-ancestor "$base" "$head" \
      || fail "The base commit must be an ancestor of the release commit."
  fi

  mapfile -t base_versions < <(changelog_versions "$base")
  mapfile -t head_versions < <(changelog_versions "$head")
  for version in "${base_versions[@]}"; do
    diff -q <(changelog_section "$base" "$version" true) \
      <(changelog_section "$head" "$version" true) >/dev/null \
      || fail "Published changelog section $version was changed."
  done

  mapfile -t added < <(comm -13 \
    <(printf '%s\n' "${base_versions[@]}") \
    <(printf '%s\n' "${head_versions[@]}"))
  if [[ "${#added[@]}" -eq 0 ]]; then
    write_output release false
    return
  fi
  [[ "${#added[@]}" -eq 1 ]] \
    || fail "A release commit must add exactly one versioned changelog section."

  version=${added[0]}
  if [[ "$EVENT_NAME" == workflow_dispatch ]]; then
    [[ "$version" == "$MANUAL_VERSION" ]] \
      || fail "Commit $head does not introduce version $MANUAL_VERSION."
    diff -q <(changelog_section "$head" "$version" true) \
      <(changelog_section "$dispatch_head" "$version" true) >/dev/null \
      || fail "Release notes for $version changed after commit $head."
  fi
  changelog_section "$head" "$version" > "$notes"
  [[ -s "$notes" ]] || fail "Release notes for $version are empty."
  git show "$head:CHANGELOG.md" | awk -v release="## [$version]" '
    /^## \[Unreleased\]$/ { unreleased = NR }
    index($0, release) == 1 { candidate = NR }
    END { exit !(unreleased && candidate && unreleased < candidate) }
  ' || fail "[Unreleased] must remain above the new release section."

  tag="v$version"
  if existing=$(git rev-parse --verify "refs/tags/$tag^{commit}" 2>/dev/null); then
    [[ $(git cat-file -t "refs/tags/$tag") == commit ]] \
      || fail "Release tag $tag must be a lightweight tag."
    [[ ("$EVENT_NAME" == push || "$EVENT_NAME" == workflow_dispatch) && \
      "$existing" == "$head" ]] \
      || fail "Release tag $tag already exists at $existing."
  fi
  write_output release true
  [[ "$EVENT_NAME" == workflow_dispatch ]] \
    && write_output draft_release "$draft_release"
  write_output version "$version"
  write_output tag "$tag"
  write_output commit "$head"
  write_output prerelease "$([[ "$version" == *-RC* ]] && echo true || echo false)"
}

# Tests the project and verifies its primary local Maven publication.
build_maven() {
  require RELEASE_COMMIT
  require RELEASE_VERSION
  local repository=${1:?Local Maven repository path is required.}
  local pom="$repository/io/temporal/temporal-sdk/$RELEASE_VERSION/temporal-sdk-$RELEASE_VERSION.pom"

  ./gradlew --no-daemon \
    "-PreleaseVersion=$RELEASE_VERSION" \
    "-PreleaseCommit=$RELEASE_COMMIT" \
    "-Dmaven.repo.local=$repository" \
    build publishToMavenLocal
  git diff --exit-code
  [[ -f "$pom" ]] || fail "The temporal-sdk POM was not generated."
  grep -Fq "<tag>$RELEASE_COMMIT</tag>" "$pom" \
    || fail "The generated POM does not identify the release commit."
}

# Reports whether the exact release is already visible on Maven Central.
central_state() {
  local pom="$RUNNER_TEMP/temporal-sdk-central.pom"
  local url="https://repo1.maven.org/maven2/io/temporal/temporal-sdk/$RELEASE_VERSION/temporal-sdk-$RELEASE_VERSION.pom"
  local status curl_status
  set +e
  status=$(curl --silent --show-error --output "$pom" --write-out '%{http_code}' "$url")
  curl_status=$?
  set -e
  [[ "$curl_status" -eq 0 ]] || fail "Maven Central could not be read."
  case "$status" in
    200)
      grep -Fq "<tag>$RELEASE_COMMIT</tag>" "$pom" \
        || fail "Maven Central contains this version from another commit."
      echo published
      ;;
    404) echo absent ;;
    *) fail "Maven Central returned HTTP $status." ;;
  esac
}

# Publishes the signed staging repository and waits for Maven Central.
publish_maven() {
  require GRADLE_USER_HOME
  require MAVEN_RETRY_REQUIRED
  require RELEASE_COMMIT
  require RELEASE_VERSION
  require RUNNER_TEMP
  local state variable signing_directory status attempt

  state=$(central_state)
  [[ "$state" == absent ]] || return
  case "$MAVEN_RETRY_REQUIRED" in
    false) ;;
    true)
      [[ "${MAVEN_RETRY_COMMIT:-}" == "$RELEASE_COMMIT" ]] \
        || fail "Inspect Sonatype, then set MAVEN_RETRY_COMMIT to $RELEASE_COMMIT before rerunning."
      ;;
    *) fail "MAVEN_RETRY_REQUIRED must be true or false." ;;
  esac
  for variable in KEY KEY_ID KEY_PASSWORD RH_PASSWORD RH_USER; do
    [[ -n "${!variable:-}" ]] || fail "Release secret $variable is not configured."
  done

  umask 077
  signing_directory="$RUNNER_TEMP/release-gnupg"
  signing_key="$signing_directory/secring.gpg"
  properties="$GRADLE_USER_HOME/gradle.properties"
  mkdir -p "$GRADLE_USER_HOME" "$signing_directory"
  trap 'rm -f "$properties" "$signing_key"' EXIT
  printf '%s' "$KEY" | base64 --decode > "$signing_key"
  {
    printf 'signing.keyId = %s\n' "$KEY_ID"
    printf 'signing.password = %s\n' "$KEY_PASSWORD"
    printf 'signing.secretKeyRingFile = %s\n' "$signing_key"
    printf 'ossrhUsername = %s\n' "$RH_USER"
    printf 'ossrhPassword = %s\n' "$RH_PASSWORD"
  } > "$properties"

  set +e
  ./gradlew --no-daemon \
    "-PreleaseVersion=$RELEASE_VERSION" \
    "-PreleaseCommit=$RELEASE_COMMIT" \
    publishToSonatype closeAndReleaseSonatypeStagingRepository
  status=$?
  set -e
  [[ "$status" -eq 0 ]] \
    || echo "::warning::Gradle failed; checking Central before declaring an ambiguous publication."

  for attempt in {1..90}; do
    state=$(central_state)
    [[ "$state" == published ]] && return
    [[ "$attempt" -eq 90 ]] || sleep 20
  done
  fail "Maven publication is ambiguous. Inspect Sonatype before authorizing a retry."
}

# Creates or validates the GitHub release, tag, notes, and assets.
publish_github() {
  require GITHUB_REPOSITORY
  require GITHUB_STEP_SUMMARY
  require PRERELEASE
  require RELEASE_COMMIT
  require RELEASE_TAG
  local notes=${1:?Release notes path is required.}
  local assets=${2:?Release asset directory is required.}
  local draft_release=${MANUAL_DRAFT_RELEASE:-${DRAFT_RELEASE:-1}}
  local requested_draft=false
  local actual_draft maven_retry_required=false published_assets release summary_title tag
  local release_assets=("$assets"/*)
  case "$draft_release" in
    0) ;;
    1) requested_draft=true ;;
    *) fail "DRAFT_RELEASE must be 0, 1, or unset." ;;
  esac
  [[ "${#release_assets[@]}" -gt 0 ]] || fail "No GitHub release assets were produced."

  if ! release=$(gh release view --repo "$GITHUB_REPOSITORY" "$RELEASE_TAG" \
    --json body,isDraft,isPrerelease,name,targetCommitish,url 2>/dev/null); then
    create=(release create "$RELEASE_TAG" --repo "$GITHUB_REPOSITORY" \
      --title "$RELEASE_TAG" --target "$RELEASE_COMMIT" --notes-file "$notes")
    [[ "$requested_draft" == true ]] && create+=(--draft)
    [[ "$PRERELEASE" == true ]] && create+=(--prerelease)
    create+=("${release_assets[@]}")
    gh "${create[@]}"
    release=$(gh release view --repo "$GITHUB_REPOSITORY" "$RELEASE_TAG" \
      --json body,isDraft,isPrerelease,name,targetCommitish,url)
  elif [[ $(jq -r .isDraft <<<"$release") == false ]]; then
    maven_retry_required=true
  fi

  jq -e --arg tag "$RELEASE_TAG" --argjson prerelease "$PRERELEASE" \
    --rawfile notes "$notes" \
    'def normalized: gsub("\r"; "") | sub("\n+$"; "");
     .name == $tag and ((.body | normalized) == ($notes | normalized)) and
     .isPrerelease == $prerelease' <<<"$release" >/dev/null
  actual_draft=$(jq -r .isDraft <<<"$release")
  if tag=$(gh api "repos/$GITHUB_REPOSITORY/git/ref/tags/$RELEASE_TAG" 2>/dev/null); then
    jq -e --arg commit "$RELEASE_COMMIT" \
      '.object.type == "commit" and .object.sha == $commit' <<<"$tag" >/dev/null \
      || fail "Release tag $RELEASE_TAG must be lightweight and point to $RELEASE_COMMIT."
  else
    [[ "$actual_draft" == true ]] || fail "Published release tag $RELEASE_TAG is missing."
    jq -e --arg commit "$RELEASE_COMMIT" \
      '.targetCommitish == $commit' <<<"$release" >/dev/null
  fi

  published_assets=$(mktemp -d)
  trap "rm -rf -- '$published_assets'" EXIT
  gh release download --repo "$GITHUB_REPOSITORY" "$RELEASE_TAG" --dir "$published_assets"
  diff -qr "$assets" "$published_assets"

  if [[ "$requested_draft" == false && "$actual_draft" == true ]]; then
    gh release edit --repo "$GITHUB_REPOSITORY" "$RELEASE_TAG" --draft=false
    release=$(gh release view --repo "$GITHUB_REPOSITORY" "$RELEASE_TAG" \
      --json body,isDraft,isPrerelease,name,targetCommitish,url)
    actual_draft=$(jq -r .isDraft <<<"$release")
    [[ "$actual_draft" == false ]] || fail "GitHub release $RELEASE_TAG remains a draft."
    tag=$(gh api "repos/$GITHUB_REPOSITORY/git/ref/tags/$RELEASE_TAG")
    jq -e --arg commit "$RELEASE_COMMIT" \
      '.object.type == "commit" and .object.sha == $commit' <<<"$tag" >/dev/null \
      || fail "Release tag $RELEASE_TAG must be lightweight and point to $RELEASE_COMMIT."
  fi

  write_output draft "$actual_draft"
  write_output maven_retry_required "$maven_retry_required"
  summary_title="Release published"
  [[ "$actual_draft" == true ]] && summary_title="Release drafted"
  {
    echo "## $summary_title"
    echo
    echo "- GitHub: $(jq -r .url <<<"$release")"
    echo "- Commit: \`$RELEASE_COMMIT\`"
  } >> "$GITHUB_STEP_SUMMARY"
}

# Dispatches the requested release operation.
command=${1:-}
shift || true
case "$command" in
  candidate) candidate "$@" ;;
  build-maven) build_maven "$@" ;;
  publish-maven) publish_maven "$@" ;;
  publish-github) publish_github "$@" ;;
  *) fail "Unknown release command: $command" ;;
esac
