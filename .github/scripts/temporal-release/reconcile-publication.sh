#!/usr/bin/env bash

set -euo pipefail

# Report a transient or operational failure that Temporal may retry.
fail() { echo "reconcile-publication: $*" >&2; exit 1; }
# Report immutable external state that conflicts with this release identity.
conflict() { echo "reconcile-publication: immutable release conflict: $*" >&2; exit 42; }
# Report an uncertain Maven submission outcome that requires durable inspection.
maven_ambiguous() { echo "reconcile-publication: ambiguous Maven submission: $*" >&2; exit 44; }

required=(
  GH_TOKEN RELEASE_INPUT_FILE RELEASE_OUTPUT_FILE RELEASE_STAGE RH_PASSWORD RH_USER
  TRUSTED_AUTOMATION_ROOT
)
for variable in "${required[@]}"; do
  [[ -n ${!variable:-} ]] || fail "Required value $variable is missing."
done

central_base=https://repo1.maven.org/maven2
tag=$(jq -er .release.candidate.tag "$RELEASE_INPUT_FILE")
version=${tag#v}
commit=$(jq -er .release.candidate.commitSha "$RELEASE_INPUT_FILE")
notes_file=releases/$tag
release_digest=$(jq -er .releaseDigest "$RELEASE_INPUT_FILE")
submission_generation=$(jq -er '.mavenGenerations[-1].generation' "$RELEASE_INPUT_FILE")

[[ $(git rev-parse --verify HEAD^{commit}) == "$commit" ]] ||
  conflict "the source checkout is not the immutable release commit."
[[ -s $notes_file && ! -L $notes_file ]] || conflict "the release notes are unavailable."

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/assets"
mapfile -t maven_artifacts < <(jq -er '.mavenArtifacts[]' "$RELEASE_INPUT_FILE")

# Download one Actions artifact using only the identity frozen in its receipt.
download_receipt() {
  local receipt=$1 destination=$2
  GH_TOKEN=$GH_TOKEN GITHUB_ARTIFACT_RECEIPT_FILE=$receipt \
    GITHUB_ARTIFACT_DESTINATION=$destination \
    "$TRUSTED_AUTOMATION_ROOT/.github/scripts/temporal-release/github-artifact.sh" download
}

# Prove that an artifact came from this repository's scheduled release workflow.
# Artifact names and digests bind bytes, but origin validation is the trust boundary that
# prevents an unrelated Actions run from supplying those bytes to privileged publication.
verify_artifact_origin() {
  local receipt=$1 run_id run
  run_id=$(jq -er .workflowRunId "$receipt")
  run=$(gh api "repos/temporalio/sdk-java/actions/runs/$run_id") ||
    fail "The originating GitHub Actions run is temporarily unavailable."
  jq -e --argjson id "$run_id" '
    .id == $id and .path == ".github/workflows/temporal-release-resume.yml" and
    .head_repository.full_name == "temporalio/sdk-java" and
    .head_branch == "main" and .event == "schedule" and
    (.status == "in_progress" or .status == "completed")' <<<"$run" >/dev/null ||
    conflict "the artifact originated from another workflow run."
}

# Download the fixed native matrix and construct its deterministic checksum asset.
materialize_native_assets() {
  local count index receipt
  count=$(jq '.release.artifacts | length' "$RELEASE_INPUT_FILE")
  for ((index = 0; index < count; index++)); do
    receipt=$work/native-$index.json
    jq ".release.artifacts[$index]" "$RELEASE_INPUT_FILE" >"$receipt"
    verify_artifact_origin "$receipt"
    mkdir "$work/native-$index"
    download_receipt "$receipt" "$work/native-$index"
    find "$work/native-$index" -mindepth 1 -maxdepth 1 -type f -exec cp {} "$work/assets/" \;
  done
  (cd "$work/assets" && sha256sum *.tar.gz *.zip | sort -k2) >"$work/assets/SHA256SUMS"
}

# Download, safely extract, and policy-validate the signed Maven payload.
# Validation happens again at publication because the Actions artifact, not a prior runner's
# filesystem, is the durable handoff between payload generation and external mutation.
materialize_maven_payload() {
  local receipt=$work/maven-receipt.json archive=$work/maven-download/maven-payload.tar
  local bundle=$work/maven-bundle
  jq .mavenPayload "$RELEASE_INPUT_FILE" >"$receipt"
  verify_artifact_origin "$receipt"
  mkdir "$work/maven-download"
  download_receipt "$receipt" "$work/maven-download"
  [[ -s $archive ]] || conflict "the exact Maven payload archive is absent."
  mkdir "$bundle"
  payload_root=$bundle/repository
  payload_manifest=$bundle/manifest.tsv
  printf '%s\n' "${maven_artifacts[@]}" >"$work/approved-maven-artifacts.txt"
  python3 "$TRUSTED_AUTOMATION_ROOT/.github/release-automation/release_automation/maven_payload.py" \
    extract "$archive" "$payload_root" "$work/approved-maven-artifacts.txt" "$version" "$commit" ||
    conflict "the Maven archive violates sdk-java policy."
}

# Count expected artifacts visible in Central and verify each published POM identity.
# Partial visibility is tracked explicitly because publication may propagate artifact by
# artifact; an existing coordinate with the wrong SCM commit is an immutable conflict.
central_state() {
  present=0
  missing=0
  for artifact in "${maven_artifacts[@]}"; do
    pom=$work/$artifact.pom
    status=$(curl --silent --show-error --location --output "$pom" --write-out '%{http_code}' \
      "$central_base/io/temporal/$artifact/$version/$artifact-$version.pom") ||
      fail "Maven Central is temporarily unavailable."
    case $status in
      200)
        identity=$(python3 - "$pom" <<'PY'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
ns = root.tag.partition("}")[0] + "}" if root.tag.startswith("{") else ""
print("\t".join((root.findtext(f"{ns}groupId", "").strip(),
                 root.findtext(f"{ns}artifactId", "").strip(),
                 root.findtext(f"{ns}version", "").strip(),
                 root.findtext(f"{ns}scm/{ns}tag", "").strip().lower())))
PY
)
        [[ $identity == io.temporal$'\t'"$artifact"$'\t'"$version"$'\t'"$commit" ]] ||
          conflict "$artifact exists with another immutable identity."
        present=$((present + 1))
        ;;
      404) missing=$((missing + 1)) ;;
      *) fail "Maven Central returned HTTP $status for $artifact." ;;
    esac
  done
}

# Require every file in the frozen Maven manifest to be visible in Maven Central.
validate_central_files() {
  while IFS=$'\t' read -r relative _ _; do
    status=$(curl --silent --show-error --location --head --output /dev/null --write-out '%{http_code}' \
      "$central_base/$relative") || fail "Maven Central is temporarily unavailable."
    [[ $status == 200 ]] || fail "Maven Central returned HTTP $status for $relative."
  done <"$payload_manifest"
}

# Fetch a consistent-enough view from both legacy staging and Publisher Portal APIs.
# Sonatype migration exposes repository identity through two APIs with different shapes;
# reconciliation must consult both before concluding that a submission is absent.
sonatype_snapshot() {
  curl --silent --show-error --fail --user "$RH_USER:$RH_PASSWORD" \
    --header 'Accept: application/json' \
    https://ossrh-staging-api.central.sonatype.com/service/local/staging/profile_repositories \
    >"$work/profile-repositories.json" || fail "Sonatype repositories are unavailable."
  jq -e '((.data // .profileRepositories) | type == "array")' \
    "$work/profile-repositories.json" >/dev/null || fail "Sonatype repository data is invalid."
  portal_token=$(printf '%s:%s' "$RH_USER" "$RH_PASSWORD" | base64 | tr -d '\n')
  curl --silent --show-error --fail --header "Authorization: Bearer $portal_token" \
    --header 'Accept: application/json' \
    'https://ossrh-staging-api.central.sonatype.com/manual/search/repositories?ip=any&profile_id=io.temporal' \
    >"$work/manual-repositories.json" || fail "Publisher Portal state is unavailable."
  jq -e '(.repositories // []) | type == "array"' "$work/manual-repositories.json" >/dev/null ||
    fail "Publisher Portal repository data is invalid."
}

# Return exactly one durable generation entry from the Activity input.
generation_state() {
  jq -cer --argjson generation "$1" \
    '[.mavenGenerations[] | select(.generation == $generation)] |
     if length == 1 then .[0] else error("missing Maven generation") end' \
    "$RELEASE_INPUT_FILE"
}

# Join both Sonatype views for one repository ID without discarding duplicates.
repository_snapshot() {
  jq -cn --arg id "$1" --slurpfile profiles "$work/profile-repositories.json" \
    --slurpfile manual "$work/manual-repositories.json" '
    {profiles:[($profiles[0].data // $profiles[0].profileRepositories // [])[] |
       select((.repositoryId // .id) == $id)],
     manual:[($manual[0].repositories // [])[] | select(.key == $id)]}'
}

# Discover the unique repository created with this release-generation description.
# The description is the idempotency key available before Sonatype returns a repository ID.
find_repository_by_description() {
  local description=$1
  jq -rn --arg description "$description" --slurpfile profiles "$work/profile-repositories.json" \
    --slurpfile manual "$work/manual-repositories.json" '
    [($profiles[0].data // $profiles[0].profileRepositories // [])[] |
       select(.description == $description) | .repositoryId // .id] +
    [($manual[0].repositories // [])[] | select(.description == $description) | .key] |
    unique | if length <= 1 then .[0] // "" else error("multiple exact repositories") end'
}

# Create a staging repository with the exact generation description and return its ID.
# The response boundary is inherently ambiguous: if the request succeeds but the response is
# lost, Temporal inspection must discover the repository before another generation is allowed.
create_repository() {
  local description=$1 profile_id status
  curl --silent --show-error --fail --user "$RH_USER:$RH_PASSWORD" \
    --header 'Accept: application/json' \
    https://ossrh-staging-api.central.sonatype.com/service/local/staging/profiles \
    >"$work/profiles.json" || fail "Sonatype profiles are temporarily unavailable."
  profile_id=$(jq -er '[.data[] | select(.name == "io.temporal") | .id] |
    if length == 1 then .[0] else error("expected one io.temporal profile") end' \
    "$work/profiles.json") || conflict "Sonatype did not return one fixed io.temporal profile."
  jq -n --arg description "$description" '{data:{description:$description}}' >"$work/start.json"
  status=$(curl --silent --show-error --output "$work/start-response.json" --write-out '%{http_code}' \
    --request POST --user "$RH_USER:$RH_PASSWORD" --header 'Content-Type: application/json' \
    --data-binary @"$work/start.json" \
    "https://ossrh-staging-api.central.sonatype.com/service/local/staging/profiles/$profile_id/start") ||
    fail "Sonatype repository creation was unavailable."
  [[ $status == 200 || $status == 201 ]] ||
    fail "Sonatype returned HTTP $status while creating the repository."
  jq -er '.data.stagedRepositoryId | select(type == "string" and test("^[A-Za-z0-9._-]+$"))' \
    "$work/start-response.json" || fail "Sonatype accepted creation without returning an ID."
}

# Read and identity-check the Publisher Portal state for one deployment.
portal_status() {
  local deployment_id=$1
  curl --silent --show-error --fail --request POST \
    --header "Authorization: Bearer $portal_token" --header 'Accept: application/json' \
    "https://central.sonatype.com/api/v1/publisher/status?id=$deployment_id" \
    >"$work/portal-status.json" || fail "Publisher Portal deployment is unavailable."
  jq -er --arg id "$deployment_id" 'select(.deploymentId == $id) | .deploymentState' \
    "$work/portal-status.json"
}

# Revalidate all earlier generations immediately before creating a replacement repository.
# Workflow recovery already inspected twice with a durable delay. This final check closes the
# race where an eventually consistent repository appears after the second inspection but before
# the next generation mutates Sonatype, preventing duplicate publication attempts.
validate_prior_generations_inactive() {
  (( submission_generation > 0 )) || return
  local row generation description repository_id discovered_id portal_id state snapshot
  while IFS= read -r row; do
    IFS='|' read -r generation repository_id portal_id < <(
      jq -r '[.generation,.repositoryId // "",.portalDeploymentId // ""] | join("|")' <<<"$row")
    (( generation < submission_generation )) || continue
    snapshot=
    description=sdk-java:$release_digest:$generation
    discovered_id=$(find_repository_by_description "$description") ||
      conflict "multiple repositories match earlier Maven generation $generation."
    [[ -z $repository_id || -z $discovered_id || $repository_id == "$discovered_id" ]] ||
      conflict "earlier Maven generation $generation has another repository identity."
    [[ -n $repository_id ]] || repository_id=$discovered_id
    if [[ -n $repository_id && -z $portal_id ]]; then
      snapshot=$(repository_snapshot "$repository_id")
      portal_id=$(jq -r '.manual[0].portal_deployment_id // ""' <<<"$snapshot")
    fi
    state=
    [[ -z $portal_id ]] || state=$(portal_status "$portal_id")
    [[ -z $state || $state == FAILED ]] ||
      maven_ambiguous "earlier Maven generation $generation has Portal state $state."
    if [[ -n $repository_id ]]; then
      snapshot=${snapshot:-$(repository_snapshot "$repository_id")}
      [[ $(jq '.profiles | length' <<<"$snapshot") == 0 ]] ||
        maven_ambiguous "earlier Maven generation $generation is still staged."
      if [[ $state == FAILED ]]; then
        jq -e --arg portal "$portal_id" '.manual | length == 1 and
          .[0].state == "released" and .[0].portal_deployment_id == $portal' \
          <<<"$snapshot" >/dev/null ||
          maven_ambiguous "failed Maven generation $generation is not inactive."
      else
        [[ $(jq '.manual | length' <<<"$snapshot") == 0 ]] ||
          maven_ambiguous "earlier Maven generation $generation is still live."
      fi
    fi
  done < <(jq -c '.mavenGenerations[] | select(.submissionStarted == true)' "$RELEASE_INPUT_FILE")
}

# Compare every staged Maven file with the frozen payload and list only missing files.
# Existing remote bytes are never overwritten: a size or digest difference is an immutable
# conflict, while a 404 is safe to repair by uploading that exact manifest entry.
inspect_staging_payload() {
  : >"$work/remote-missing.tsv"
  while IFS=$'\t' read -r relative sha size; do
    remote=$work/remote-$(printf '%s' "$relative" | sha256sum | awk '{print $1}')
    status=$(curl --silent --show-error --location --output "$remote" --write-out '%{http_code}' \
      --user "$RH_USER:$RH_PASSWORD" \
      "https://ossrh-staging-api.central.sonatype.com/service/local/repositories/$repository_id/content/$relative") ||
      fail "Unable to inspect staged Maven file $relative."
    case $status in
      200)
        [[ $(sha256sum "$remote" | awk '{print $1}') == "$sha" &&
          $(wc -c <"$remote" | tr -d ' ') == "$size" ]] ||
          conflict "staged Maven file $relative differs."
        ;;
      404) printf '%s\t%s\t%s\n' "$relative" "$sha" "$size" >>"$work/remote-missing.tsv" ;;
      *) fail "Sonatype returned HTTP $status for $relative." ;;
    esac
  done <"$payload_manifest"
}

# Upload only manifest entries proven absent from the exact staging repository.
upload_missing_payload() {
  while IFS=$'\t' read -r relative _ _; do
    [[ -n $relative ]] || continue
    curl --silent --show-error --fail --user "$RH_USER:$RH_PASSWORD" \
      --upload-file "$payload_root/$relative" \
      "https://ossrh-staging-api.central.sonatype.com/service/local/staging/deployByRepositoryId/$repository_id/$relative" \
      >/dev/null || fail "Unable to upload staged Maven file $relative."
  done <"$work/remote-missing.tsv"
}

# Adopt or create the repository for the current durable Maven generation.
reconcile_maven_repository() {
  local state description
  [[ $present -eq 0 || $present -eq ${#maven_artifacts[@]} ]] ||
    fail "Maven publication is partially visible."
  state=$(generation_state "$submission_generation")
  description=sdk-java:$release_digest:$submission_generation
  [[ $(jq -r .submissionStarted <<<"$state") == true ]] ||
    conflict "the durable Maven generation intent differs."
  repository_id=$(jq -r '.repositoryId // ""' <<<"$state")
  sonatype_snapshot
  if [[ -z $repository_id ]]; then
    repository_id=$(find_repository_by_description "$description") ||
      conflict "multiple Sonatype repositories match the generation."
  fi
  [[ -z $repository_id ]] || return
  [[ $present -eq 0 ]] || maven_ambiguous "Central is complete without a repository identity."
  validate_prior_generations_inactive
  repository_id=$(create_repository "$description")
}

# Reconcile staging contents, close the repository, and adopt its Portal deployment.
# Accepted close requests intentionally fail this Activity attempt so a later retry observes
# Sonatype's external transition instead of assuming the mutation completed synchronously.
reconcile_maven_portal() {
  local description repository_state close_status snapshot
  description=sdk-java:$release_digest:$submission_generation
  sonatype_snapshot
  snapshot=$(repository_snapshot "$repository_id")
  IFS='|' read -r profile_description repository_state portal_id < <(jq -r '
    [.profiles[0].description // "",.manual[0].state // "",
     .manual[0].portal_deployment_id // ""] | join("|")' <<<"$snapshot")
  [[ -z $profile_description || $profile_description == "$description" ]] ||
    conflict "the Sonatype repository ID has another description."
  [[ -n $repository_state ]] || {
    [[ -n $profile_description ]] || maven_ambiguous "the repository disappeared from Sonatype."
    repository_state=open
  }
  if [[ $repository_state == open ]]; then
    inspect_staging_payload
    upload_missing_payload
    inspect_staging_payload
    [[ ! -s $work/remote-missing.tsv ]] || fail "The staged Maven payload is incomplete."
    jq -n --arg id "$repository_id" --arg description "$description" \
      '{data:{stagedRepositoryIds:[$id],description:$description}}' >"$work/close.json"
    close_status=$(curl --silent --show-error --output "$work/close-response" \
      --write-out '%{http_code}' --request POST --user "$RH_USER:$RH_PASSWORD" \
      --header 'Content-Type: application/json' --data-binary @"$work/close.json" \
      https://ossrh-staging-api.central.sonatype.com/service/local/staging/bulk/close) ||
      fail "Unable to close the Sonatype repository."
    case $close_status in 200 | 201 | 202 | 204) ;; *)
      fail "Sonatype returned HTTP $close_status while closing the repository." ;; esac
    fail "Sonatype accepted the repository close; Temporal will reconcile again."
  fi
  [[ $repository_state == closed || $repository_state == released ]] ||
    conflict "Sonatype returned unsupported repository state $repository_state."
  [[ $portal_id =~ ^[0-9a-fA-F-]{16,64}$ ]] || fail "The Portal deployment ID is not visible yet."
}

# Publish only a validated Portal deployment, then force a fresh reconciliation pass.
publish_maven() {
  local deployment_state publish_status
  deployment_state=$(portal_status "$portal_id")
  case $deployment_state in
    VALIDATED)
      publish_status=$(curl --silent --show-error --output "$work/publish-response" \
        --write-out '%{http_code}' --request POST --header "Authorization: Bearer $portal_token" \
        "https://central.sonatype.com/api/v1/publisher/deployment/$portal_id") ||
        fail "Unable to publish the exact Portal deployment."
      [[ $publish_status == 204 ]] || fail "Portal returned HTTP $publish_status while publishing."
      fail "Portal accepted publication; Temporal will reconcile again."
      ;;
    PENDING | VALIDATING | PUBLISHING) fail "Portal deployment is $deployment_state." ;;
    FAILED) echo "reconcile-publication: exact Portal deployment failed validation." >&2; exit 45 ;;
    PUBLISHED) ;;
    *) conflict "Portal returned unsupported deployment state $deployment_state." ;;
  esac
}

# Fetch the release matching the exact tag while treating API failure as distinct from absence.
release_json() {
  local releases
  releases=$(gh api --paginate --slurp 'repos/temporalio/sdk-java/releases?per_page=100') ||
    fail "GitHub releases are temporarily unavailable."
  jq -c --arg tag "$tag" '[.[][]] | map(select(.tag_name == $tag)) | first // empty' <<<"$releases"
}

# GET a GitHub object while distinguishing an expected 404 from transient API errors.
github_optional_get() {
  local path=$1 output=$2 status
  status=$(curl --silent --show-error --location --output "$output" --write-out '%{http_code}' \
    --header "Authorization: Bearer $GH_TOKEN" --header 'Accept: application/vnd.github+json' \
    --header 'X-GitHub-Api-Version: 2022-11-28' "https://api.github.com/$path") ||
    fail "GitHub is temporarily unavailable."
  case $status in 200) return 0 ;; 404) : >"$output"; return 1 ;; *)
    fail "GitHub returned HTTP $status while reading $path." ;; esac
}

# Create the tag if absent, or prove an existing/concurrently created tag is exact.
ensure_exact_tag() {
  local file=$work/tag.json
  if github_optional_get "repos/temporalio/sdk-java/git/ref/tags/$tag" "$file"; then
    [[ $(jq -r .object.type "$file") == commit && $(jq -r .object.sha "$file") == "$commit" ]] ||
      conflict "the Git tag points at another object."
    return
  fi
  gh api --method POST repos/temporalio/sdk-java/git/refs \
    --raw-field ref="refs/tags/$tag" --raw-field sha="$commit" >/dev/null || {
      github_optional_get "repos/temporalio/sdk-java/git/ref/tags/$tag" "$file" ||
        fail "The exact Git tag could not be reconciled."
      [[ $(jq -r .object.type "$file") == commit && $(jq -r .object.sha "$file") == "$commit" ]] ||
        conflict "the concurrently created tag differs."
    }
}

# Verify immutable GitHub release metadata including notes, target commit, and RC status.
verify_release_metadata() {
  local release=$1 draft=$2 prerelease=false
  [[ $tag == *-RC* ]] && prerelease=true
  jq -e --arg tag "$tag" --arg commit "$commit" --rawfile notes "$notes_file" \
    --argjson prerelease "$prerelease" --argjson draft "$draft" '
    .tag_name == $tag and .name == $tag and .body == $notes and .draft == $draft and
    .prerelease == $prerelease and .target_commitish == $commit' <<<"$release" >/dev/null ||
    conflict "GitHub release metadata differs."
}

# Require the public release to contain exactly the locally materialized asset set.
# GitHub's reported SHA-256 digest and size are compared with every local file before the
# draft is made public and again afterward, detecting both omission and substitution.
verify_exact_github_assets() {
  local release=$1 name state size asset_digest expected
  mapfile -t expected < <(find "$work/assets" -mindepth 1 -maxdepth 1 -type f -exec basename {} \; | sort)
  mapfile -t actual < <(jq -r '.assets[].name' <<<"$release" | sort)
  [[ ${actual[*]} == "${expected[*]}" ]] || fail "The GitHub asset set is not complete."
  while IFS=$'\t' read -r name state size asset_digest; do
    [[ $state == uploaded ]] || conflict "GitHub asset $name has unsupported state $state."
    [[ $size == $(wc -c <"$work/assets/$name" | tr -d ' ') &&
      $asset_digest == sha256:$(sha256sum "$work/assets/$name" | awk '{print $1}') ]] ||
      conflict "GitHub asset $name differs."
  done < <(jq -r '.assets[] | [.name,.state,.size,.digest] | @tsv' <<<"$release")
}

# Detect conflicting tags or releases before any Maven-side mutation begins.
verify_github_preflight() {
  local release draft remote
  if github_optional_get "repos/temporalio/sdk-java/git/ref/tags/$tag" "$work/preflight-tag.json"; then
    [[ $(jq -r .object.type "$work/preflight-tag.json") == commit &&
      $(jq -r .object.sha "$work/preflight-tag.json") == "$commit" ]] ||
      conflict "the existing tag points at another object."
  fi
  release=$(release_json)
  [[ -n $release ]] || return
  draft=$(jq -r .draft <<<"$release")
  verify_release_metadata "$release" "$draft"
  while IFS= read -r remote; do
    [[ -f $work/assets/$remote ]] || conflict "the release has unexpected asset $remote."
  done < <(jq -r '.assets[].name' <<<"$release")
  [[ $draft == true ]] || verify_exact_github_assets "$release"
}

# Reconcile an exact draft and upload only assets that are still absent.
# Existing uploaded assets must match byte-for-byte. Zero-byte starter assets may be removed
# only while the release is still draft, preserving public releases as immutable state.
reconcile_github_draft() {
  local release draft name state size asset_digest id
  ensure_exact_tag
  release=$(release_json)
  if [[ -z $release ]]; then
    args=(release create "$tag" --repo temporalio/sdk-java --draft --target "$commit" \
      --title "$tag" --notes-file "$notes_file")
    [[ $tag == *-RC* ]] && args+=(--prerelease)
    gh "${args[@]}" >/dev/null
    release=$(release_json)
  fi
  [[ -n $release ]] || fail "The GitHub draft is not visible yet."
  draft=$(jq -r .draft <<<"$release")
  verify_release_metadata "$release" "$draft"
  while IFS=$'\t' read -r id name state size asset_digest; do
    [[ -f $work/assets/$name ]] || conflict "the release has unexpected asset $name."
    if [[ $draft == true && $state == starter && $size == 0 ]]; then
      gh api --method DELETE "repos/temporalio/sdk-java/releases/assets/$id" >/dev/null
    elif [[ $state != uploaded || $size != $(wc -c <"$work/assets/$name" | tr -d ' ') ||
      $asset_digest != sha256:$(sha256sum "$work/assets/$name" | awk '{print $1}') ]]; then
      conflict "GitHub asset $name differs."
    fi
  done < <(jq -r '.assets[] | [.id,.name,.state,.size,.digest] | @tsv' <<<"$release")
  release=$(release_json)
  for asset in "$work/assets"/*; do
    name=$(basename "$asset")
    if ! jq -e --arg name "$name" '.assets[] | select(.name == $name)' <<<"$release" >/dev/null; then
      [[ $draft == true ]] || conflict "the public release is missing asset $name."
      gh release upload "$tag" "$asset" --repo temporalio/sdk-java >/dev/null
    fi
  done
}

# Publish GitHub only after Maven Central and every draft asset are exact.
publish_github() {
  local release
  central_state
  [[ $missing -eq 0 && $present -eq ${#maven_artifacts[@]} ]] ||
    fail "Maven Central is incomplete immediately before GitHub publication."
  validate_central_files
  reconcile_github_draft
  release=$(release_json)
  if [[ $(jq -r .draft <<<"$release") == true ]]; then
    ensure_exact_tag
    verify_exact_github_assets "$release"
    gh release edit "$tag" --repo temporalio/sdk-java --draft=false >/dev/null
  fi
  release=$(release_json)
  verify_release_metadata "$release" false
  verify_exact_github_assets "$release"
  jq -n --arg releaseDigest "$release_digest" --arg githubReleaseUrl "$(jq -er .html_url <<<"$release")" \
    --arg mavenCentralUrl "https://central.sonatype.com/artifact/io.temporal/temporal-sdk/$version" \
    '{releaseDigest:$releaseDigest,githubReleaseUrl:$githubReleaseUrl,
      mavenCentralUrl:$mavenCentralUrl}' >"$RELEASE_OUTPUT_FILE"
}

# Return current Central visibility and external identity/state for every generation.
# This stage is read-only: the Workflow uses its result to decide whether an ambiguous prior
# submission is active, terminally failed, or repeatedly absent before advancing generation.
inspect_maven() {
  local row generation description repository_id repository_state portal_id portal_state snapshot
  local profile_count discovered_portal
  central_state
  sonatype_snapshot
  : >"$work/inspections.jsonl"
  while IFS= read -r row; do
    IFS='|' read -r generation repository_id portal_id < <(
      jq -r '[.generation,.repositoryId // "",.portalDeploymentId // ""] | join("|")' <<<"$row")
    description=sdk-java:$release_digest:$generation
    if [[ -z $repository_id ]]; then
      repository_id=$(find_repository_by_description "$description") ||
        conflict "multiple repositories match Maven generation $generation."
    fi
    repository_state=absent
    portal_state=""
    if [[ -n $repository_id ]]; then
      snapshot=$(repository_snapshot "$repository_id")
      IFS='|' read -r repository_state profile_count discovered_portal < <(jq -r '
        [.manual[0].state // "",(.profiles | length),
         .manual[0].portal_deployment_id // ""] | join("|")' <<<"$snapshot")
      [[ -n $repository_state ]] ||
        repository_state=$([[ $profile_count -gt 0 ]] && echo open || echo absent)
      [[ -n $portal_id ]] || portal_id=$discovered_portal
    fi
    [[ -z $portal_id ]] || portal_state=$(portal_status "$portal_id")
    jq -cn --argjson generation "$generation" \
      --arg repositoryId "$repository_id" --arg repositoryState "$repository_state" \
      --arg portalDeploymentId "$portal_id" --arg portalDeploymentState "$portal_state" \
      '{generation:$generation,repositoryId:$repositoryId,
        repositoryState:$repositoryState,portalDeploymentId:$portalDeploymentId,
        portalDeploymentState:$portalDeploymentState}' >>"$work/inspections.jsonl"
  done < <(jq -c '.mavenGenerations[]' "$RELEASE_INPUT_FILE")
  jq -s --argjson centralPresent "$present" \
    '{centralPresent:$centralPresent,generations:.}' \
    "$work/inspections.jsonl" >"$RELEASE_OUTPUT_FILE"
}

# Reconcile the complete release in Maven-first, GitHub-public-last order.
publish_release() {
  materialize_native_assets
  materialize_maven_payload
  verify_github_preflight
  central_state
  if [[ $missing -eq 0 && $present -eq ${#maven_artifacts[@]} ]]; then
    publish_github
    return
  fi
  reconcile_maven_repository
  reconcile_maven_portal
  publish_maven
  publish_github
}

case $RELEASE_STAGE in
  all) publish_release ;;
  inspect) inspect_maven ;;
  *) fail "Temporal scheduled an unknown publication stage." ;;
esac
