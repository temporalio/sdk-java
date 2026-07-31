#!/usr/bin/env bash

set -euo pipefail

fail() { echo "reconcile-publication: $*" >&2; exit 1; }
conflict() { echo "reconcile-publication: immutable release conflict: $*" >&2; exit 42; }
invalid_approval() { echo "reconcile-publication: invalid approval: $*" >&2; exit 43; }

required=(
  EXPECTED_APPROVAL_ACTOR EXPECTED_APPROVAL_RUN_ID EXPECTED_COMMIT_SHA
  EXPECTED_MANIFEST_SHA256 EXPECTED_NOTES_SHA256 EXPECTED_RELEASE_DIGEST
  EXPECTED_REPOSITORY EXPECTED_RUN_ID EXPECTED_TAG EXPECTED_WORKFLOW_ID
  GH_TOKEN JAR_SIGNING_KEY JAR_SIGNING_KEY_ID JAR_SIGNING_KEY_PASSWORD
  RELEASE_ARTIFACT_BUCKET RELEASE_INPUT_FILE RELEASE_OUTPUT_FILE
  RH_PASSWORD RH_USER TRUSTED_WORKER_COMMIT
)
for variable in "${required[@]}"; do
  [[ -n ${!variable:-} ]] || fail "Required value $variable is missing."
done
[[ $EXPECTED_REPOSITORY == temporalio/sdk-java ]] || conflict "repository changed."

repository=$(jq -er '.release.candidate.repository' "$RELEASE_INPUT_FILE")
version=$(jq -er '.release.candidate.version' "$RELEASE_INPUT_FILE")
tag=$(jq -er '.release.candidate.tag' "$RELEASE_INPUT_FILE")
commit=$(jq -er '.release.candidate.commitSha' "$RELEASE_INPUT_FILE")
notes_file=$(jq -er '.release.candidate.releaseNotesPath' "$RELEASE_INPUT_FILE")
notes_hash=$(jq -er '.release.candidate.releaseNotesSha256' "$RELEASE_INPUT_FILE")
manifest_hash=$(jq -er '.release.manifestSha256' "$RELEASE_INPUT_FILE")
release_digest=$(jq -er '.approval.releaseDigest' "$RELEASE_INPUT_FILE")
workflow_id=$(jq -er '.workflowId' "$RELEASE_INPUT_FILE")
run_id=$(jq -er '.runId' "$RELEASE_INPUT_FILE")
approval_run_id=$(jq -er '.approval.githubApprovalRunId' "$RELEASE_INPUT_FILE")
approval_actor=$(jq -er '.approval.githubActor' "$RELEASE_INPUT_FILE")
trusted_commit=$(jq -er '.approval.trustedWorkerCommit' "$RELEASE_INPUT_FILE")

[[ $repository == "$EXPECTED_REPOSITORY" && $tag == "$EXPECTED_TAG" &&
  $commit == "$EXPECTED_COMMIT_SHA" && $notes_hash == "$EXPECTED_NOTES_SHA256" &&
  $manifest_hash == "$EXPECTED_MANIFEST_SHA256" &&
  $release_digest == "$EXPECTED_RELEASE_DIGEST" &&
  $workflow_id == "$EXPECTED_WORKFLOW_ID" && $run_id == "$EXPECTED_RUN_ID" &&
  $approval_run_id == "$EXPECTED_APPROVAL_RUN_ID" &&
  $approval_actor == "$EXPECTED_APPROVAL_ACTOR" &&
  $trusted_commit == "$TRUSTED_WORKER_COMMIT" ]] ||
  conflict "the Activity input differs from the privileged Actions run."

approval_run=$(gh api "repos/temporalio/sdk-java/actions/runs/$approval_run_id") ||
  invalid_approval "the approval Actions run is unavailable."
jq -e --arg actor "$approval_actor" \
  '.event == "workflow_dispatch" and
   .path == ".github/workflows/temporal-release-approve.yml" and
   .conclusion == "success" and .actor.login == $actor' \
  <<<"$approval_run" >/dev/null || invalid_approval "the GitHub run is not a completed approval."
"$(dirname "$0")/verify-approver.sh" "$approval_actor" >/dev/null ||
  invalid_approval "the actor is not an active release manager."

[[ $(git rev-parse --verify HEAD^{commit}) == "$commit" ]] ||
  conflict "the source checkout is not the approved commit."
[[ $notes_file == "releases/$tag" && -s $notes_file && ! -L $notes_file ]] ||
  conflict "the approved release notes are unavailable."
[[ $(sha256sum "$notes_file" | awk '{print $1}') == "$notes_hash" ]] ||
  conflict "release notes changed."

work=$(mktemp -d)
gradle_home="$work/gradle-home"
signing_key="$work/release-secring.gpg"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/assets" "$work/existing" "$gradle_home"
umask 077
printf '%s' "$JAR_SIGNING_KEY" | base64 --decode >"$signing_key"
{
  printf 'signing.keyId = %s\n' "$JAR_SIGNING_KEY_ID"
  printf 'signing.password = %s\n' "$JAR_SIGNING_KEY_PASSWORD"
  printf 'signing.secretKeyRingFile = %s\n' "$signing_key"
  printf 'ossrhUsername = %s\n' "$RH_USER"
  printf 'ossrhPassword = %s\n' "$RH_PASSWORD"
} >"$gradle_home/gradle.properties"
export GRADLE_USER_HOME=$gradle_home

manifest="$work/manifest.tsv"
jq -r '.release.manifest.artifacts | sort_by(.name)[] |
  [.name, .sha256, (.size | tostring), .storageKey] | @tsv' \
  "$RELEASE_INPUT_FILE" >"$manifest"
[[ -s $manifest && $(wc -l <"$manifest" | tr -d ' ') -eq 6 ]] ||
  conflict "the approved native artifact manifest is incomplete."

while IFS=$'\t' read -r name sha size storage_key; do
  [[ $name =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ && $sha =~ ^[0-9a-f]{64}$ &&
    $size =~ ^[1-9][0-9]*$ &&
    $storage_key =~ ^sdk-java/[0-9a-f]{64}/$name$ ]] ||
    conflict "the artifact manifest contains an invalid record."
  aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$storage_key" "$work/assets/$name" \
    --no-progress >/dev/null
  [[ $(wc -c <"$work/assets/$name" | tr -d ' ') == "$size" ]] ||
    conflict "$name has the wrong size in durable storage."
  [[ $(sha256sum "$work/assets/$name" | awk '{print $1}') == "$sha" ]] ||
    conflict "$name has the wrong checksum in durable storage."
done <"$manifest"
awk -F '\t' '{print $2 "  " $1}' "$manifest" >"$work/assets/SHA256SUMS"

maven_artifacts=(
  temporal-aws-lambda
  temporal-bom
  temporal-envconfig
  temporal-kotlin
  temporal-opentelemetry
  temporal-opentracing
  temporal-remote-data-encoder
  temporal-sdk
  temporal-serviceclient
  temporal-shaded
  temporal-spring-ai
  temporal-spring-boot-autoconfigure
  temporal-spring-boot-starter
  temporal-test-server
  temporal-testing
  temporal-workflowcheck
  temporal-workflowstreams
)
present=0
missing=0
for artifact in "${maven_artifacts[@]}"; do
  pom="$work/$artifact.pom"
  central_url="https://repo1.maven.org/maven2/io/temporal/$artifact/$version/$artifact-$version.pom"
  central_status=$(curl --silent --show-error --location --output "$pom" \
    --write-out '%{http_code}' "$central_url")
  case "$central_status" in
    200)
      published_commit=$(python3 - "$pom" <<'PY'
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
namespace = root.tag.partition("}")[0] + "}" if root.tag.startswith("{") else ""
tag = root.find(f"{namespace}scm/{namespace}tag")
print("" if tag is None or tag.text is None else tag.text.strip().lower())
PY
)
      [[ $published_commit == "$commit" ]] ||
        conflict "$artifact coordinates belong to another commit."
      present=$((present + 1))
      ;;
    404) missing=$((missing + 1)) ;;
    *) fail "Maven Central returned HTTP $central_status for $artifact." ;;
  esac
done
[[ $((present + missing)) -eq ${#maven_artifacts[@]} ]] ||
  conflict "the fixed Maven publication set was not fully reconciled."

marker="sdk-java/$release_digest/state/maven-submitted"
marker_exists=false
if aws s3api head-object --bucket "$RELEASE_ARTIFACT_BUCKET" --key "$marker" >/dev/null 2>&1; then
  marker_exists=true
  submitted_commit=$(aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$marker" - --no-progress)
  [[ $submitted_commit == "$commit" ]] || conflict "the Maven submission marker differs."
fi

if [[ $present -ne ${#maven_artifacts[@]} ]]; then
  if [[ $present -gt 0 || $marker_exists == true ]]; then
    if [[ $marker_exists == false ]]; then
      printf '%s\n' "$commit" >"$work/maven-submitted"
      aws s3api put-object --bucket "$RELEASE_ARTIFACT_BUCKET" --key "$marker" \
        --body "$work/maven-submitted" --if-none-match '*' >/dev/null || true
    fi
    fail "Maven publication is partially visible; Temporal will retry the exact-state check."
  fi

  set +e
  ./gradlew --no-daemon "-PreleaseVersion=$version" "-PreleaseCommit=$commit" \
    publishToSonatype closeAndReleaseSonatypeStagingRepository >&2
  publish_status=$?
  set -e
  [[ $publish_status -eq 0 ]] || exit "$publish_status"
  printf '%s\n' "$commit" >"$work/maven-submitted"
  aws s3api put-object --bucket "$RELEASE_ARTIFACT_BUCKET" --key "$marker" \
    --body "$work/maven-submitted" --if-none-match '*' >/dev/null || true
  fail "Maven publication was submitted; Temporal will retry the exact-state check."
fi

tag_ref=$(gh api "repos/temporalio/sdk-java/git/ref/tags/$tag" 2>/dev/null || true)
if [[ -n $tag_ref ]]; then
  [[ $(jq -r '.object.type' <<<"$tag_ref") == commit &&
    $(jq -r '.object.sha' <<<"$tag_ref") == "$commit" ]] ||
    conflict "the Git tag points at another object."
else
  gh api --method POST repos/temporalio/sdk-java/git/refs \
    -f ref="refs/tags/$tag" -f sha="$commit" >/dev/null
fi

prerelease=false
[[ $tag == *-RC* ]] && prerelease=true
release=$(gh api "repos/temporalio/sdk-java/releases/tags/$tag" 2>/dev/null || true)
if [[ -z $release ]]; then
  create_args=(release create "$tag" --repo temporalio/sdk-java --verify-tag --title "$tag" --notes-file "$notes_file")
  [[ $prerelease == true ]] && create_args+=(--prerelease)
  gh "${create_args[@]}" >/dev/null
  release=$(gh api "repos/temporalio/sdk-java/releases/tags/$tag")
fi

jq -e --arg tag "$tag" --rawfile notes "$notes_file" --argjson prerelease "$prerelease" \
  '.tag_name == $tag and .name == $tag and .body == $notes and
   .draft == false and .prerelease == $prerelease' <<<"$release" >/dev/null ||
  conflict "the GitHub release metadata differs from the approved release."

mapfile -t expected_assets < <(find "$work/assets" -mindepth 1 -maxdepth 1 -type f -exec basename {} \; | sort)
mapfile -t remote_assets < <(jq -r '.assets[].name' <<<"$release" | sort)
for remote_name in "${remote_assets[@]}"; do
  printf '%s\n' "${expected_assets[@]}" | grep -Fxq "$remote_name" ||
    conflict "the GitHub release has unexpected asset $remote_name."
done
for name in "${expected_assets[@]}"; do
  if printf '%s\n' "${remote_assets[@]}" | grep -Fxq "$name"; then
    gh release download "$tag" --repo temporalio/sdk-java --pattern "$name" \
      --dir "$work/existing" --clobber >/dev/null
    [[ $(sha256sum "$work/existing/$name" | awk '{print $1}') ==
      $(sha256sum "$work/assets/$name" | awk '{print $1}') ]] ||
      conflict "existing GitHub asset $name has different bytes."
  else
    gh release upload "$tag" "$work/assets/$name" --repo temporalio/sdk-java >/dev/null
  fi
done

release=$(gh api "repos/temporalio/sdk-java/releases/tags/$tag")
mapfile -t final_assets < <(jq -r '.assets[].name' <<<"$release" | sort)
[[ ${final_assets[*]} == "${expected_assets[*]}" ]] ||
  conflict "the final GitHub asset set is not exact."
release_url=$(jq -er '.html_url' <<<"$release")
jq -n --arg releaseDigest "$release_digest" --arg githubReleaseUrl "$release_url" \
  --arg mavenCentralUrl "https://central.sonatype.com/artifact/io.temporal/temporal-sdk/$version" \
  '{releaseDigest: $releaseDigest, githubReleaseUrl: $githubReleaseUrl,
    mavenCentralUrl: $mavenCentralUrl}' >"$RELEASE_OUTPUT_FILE"
