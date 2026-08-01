#!/usr/bin/env bash

set -euo pipefail

fail() { echo "reconcile-publication: $*" >&2; exit 1; }
conflict() { echo "reconcile-publication: immutable release conflict: $*" >&2; exit 42; }
invalid_approval() { echo "reconcile-publication: invalid approval: $*" >&2; exit 43; }

required=(
  EXPECTED_APPROVAL_ACTOR EXPECTED_APPROVAL_RUN_ID EXPECTED_COMMIT_SHA
  EXPECTED_MANIFEST_SHA256 EXPECTED_NOTES_SHA256 EXPECTED_RELEASE_DIGEST
  EXPECTED_REPOSITORY EXPECTED_RUN_ID EXPECTED_TAG EXPECTED_WORKFLOW_ID
  GH_TOKEN
  RELEASE_ARTIFACT_BUCKET RELEASE_INPUT_FILE RELEASE_OUTPUT_FILE RELEASE_STAGE
  TRUSTED_AUTOMATION_ROOT TRUSTED_WORKER_COMMIT
)
if [[ ${RELEASE_STAGE:-} == maven ]]; then
  required+=(JAR_SIGNING_KEY JAR_SIGNING_KEY_ID JAR_SIGNING_KEY_PASSWORD RH_PASSWORD RH_USER)
elif [[ ${RELEASE_STAGE:-} == inspect ]]; then
  required+=(RH_PASSWORD RH_USER)
fi
for variable in "${required[@]}"; do
  [[ -n ${!variable:-} ]] || fail "Required value $variable is missing."
done

repository=$(jq -er '.release.candidate.repository' "$RELEASE_INPUT_FILE")
version=$(jq -er '.release.candidate.version' "$RELEASE_INPUT_FILE")
tag=$(jq -er '.release.candidate.tag' "$RELEASE_INPUT_FILE")
commit=$(jq -er '.release.candidate.commitSha' "$RELEASE_INPUT_FILE")
notes_file=$(jq -er '.release.candidate.releaseNotesPath' "$RELEASE_INPUT_FILE")
notes_hash=$(jq -er '.release.candidate.releaseNotesSha256' "$RELEASE_INPUT_FILE")
trusted_commit=$(jq -er '.release.candidate.trustedAutomationCommit' "$RELEASE_INPUT_FILE")
manifest_hash=$(jq -er '.release.manifestSha256' "$RELEASE_INPUT_FILE")
release_digest=$(jq -er '.approval.releaseDigest' "$RELEASE_INPUT_FILE")
workflow_id=$(jq -er '.workflowId' "$RELEASE_INPUT_FILE")
run_id=$(jq -er '.runId' "$RELEASE_INPUT_FILE")
approval_run_id=$(jq -er '.approval.githubApprovalRunId' "$RELEASE_INPUT_FILE")
approval_actor=$(jq -er '.approval.githubActor' "$RELEASE_INPUT_FILE")
approval_issue_number=$(jq -er '.approval.githubIssueNumber' "$RELEASE_INPUT_FILE")
approval_issue_node_id=$(jq -er '.approval.githubIssueNodeId' "$RELEASE_INPUT_FILE")
approval_issue_body_hash=$(jq -er '.approval.githubIssueBodySha256' "$RELEASE_INPUT_FILE")
approval_trusted_commit=$(jq -er '.approval.trustedWorkerCommit' "$RELEASE_INPUT_FILE")
maven_group=$(jq -er '.mavenGroup' "$RELEASE_INPUT_FILE")
central_base=$(jq -er '.mavenCentralBase' "$RELEASE_INPUT_FILE")

[[ $repository == temporalio/sdk-java && $repository == "$EXPECTED_REPOSITORY" &&
  $tag == "$EXPECTED_TAG" && $commit == "$EXPECTED_COMMIT_SHA" &&
  $notes_hash == "$EXPECTED_NOTES_SHA256" && $manifest_hash == "$EXPECTED_MANIFEST_SHA256" &&
  $release_digest == "$EXPECTED_RELEASE_DIGEST" && $workflow_id == "$EXPECTED_WORKFLOW_ID" &&
  $run_id == "$EXPECTED_RUN_ID" && $approval_run_id == "$EXPECTED_APPROVAL_RUN_ID" &&
  $approval_actor == "$EXPECTED_APPROVAL_ACTOR" && $trusted_commit == "$TRUSTED_WORKER_COMMIT" &&
  $approval_trusted_commit == "$TRUSTED_WORKER_COMMIT" && $maven_group == io.temporal &&
  $central_base == https://repo1.maven.org/maven2 ]] ||
  conflict "the Activity input differs from the privileged Actions run."

[[ $(git rev-parse --verify HEAD^{commit}) == "$commit" ]] ||
  conflict "the source checkout is not the approved commit."
[[ $notes_file == "releases/$tag" && -s $notes_file && ! -L $notes_file ]] ||
  conflict "the approved release notes are unavailable."
[[ $(sha256sum "$notes_file" | awk '{print $1}') == "$notes_hash" ]] ||
  conflict "release notes changed."

work=$(mktemp -d)
gradle_home="$work/gradle-home"
signing_key="$work/release-secring.gpg"
trap 'if [[ -f $work/versioning.gradle.original ]]; then cp "$work/versioning.gradle.original" gradle/versioning.gradle; cp "$work/publishing.gradle.original" gradle/publishing.gradle; fi; rm -rf "$work"' EXIT
mkdir -p "$work/assets" "$work/existing" "$gradle_home"

ownership_key="sdk-java/ownership/$tag.json"
ownership="$work/ownership.json"
jq -n --arg tag "$tag" --arg commitSha "$commit" --arg releaseDigest "$release_digest" \
  --arg owner temporal '{tag:$tag,commitSha:$commitSha,releaseDigest:$releaseDigest,owner:$owner}' >"$ownership"

ensure_ownership() {
  if ! aws s3api put-object --bucket "$RELEASE_ARTIFACT_BUCKET" --key "$ownership_key" \
    --body "$ownership" --if-none-match '*' >/dev/null 2>&1; then
    aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$ownership_key" "$work/existing-ownership.json" \
      --no-progress >/dev/null || fail "Unable to read the durable tag ownership key."
    cmp -s "$ownership" "$work/existing-ownership.json" ||
      conflict "the tag/version ownership key belongs to another controller or SHA."
  fi
}

inspect_ownership() {
  if aws s3api head-object --bucket "$RELEASE_ARTIFACT_BUCKET" --key "$ownership_key" \
    >/dev/null 2>&1; then
    aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$ownership_key" "$work/existing-ownership.json" \
      --no-progress >/dev/null || fail "Unable to inspect the durable tag ownership key."
    cmp -s "$ownership" "$work/existing-ownership.json" ||
      conflict "the tag/version ownership key belongs to another controller or SHA."
  fi
}

verify_approval() {
  local approval_run
  if [[ ${RELEASE_MODE:-temporal} != emergency && ${RELEASE_MODE:-temporal} != emergency-inspect ]]; then
    [[ $approval_issue_number == "${EXPECTED_APPROVAL_ISSUE_NUMBER:-}" &&
      $approval_issue_node_id == "${EXPECTED_APPROVAL_ISSUE_NODE_ID:-}" &&
      $approval_issue_body_hash == "${EXPECTED_APPROVAL_ISSUE_BODY_SHA256:-}" ]] ||
      conflict "the approval issue differs from the privileged Actions run."
  fi
  if ! approval_run=$(gh api "repos/temporalio/sdk-java/actions/runs/$approval_run_id"); then
    fail "the approval Actions run is temporarily unavailable."
  fi
  if [[ ${RELEASE_MODE:-temporal} == emergency || ${RELEASE_MODE:-temporal} == emergency-inspect ]]; then
    jq -e --arg actor "$approval_actor" \
      '.event == "workflow_dispatch" and .path == ".github/workflows/prepare-release.yml" and
       (.status == "in_progress" or (.status == "completed" and .conclusion == "success")) and
       .triggering_actor.login == $actor' <<<"$approval_run" >/dev/null ||
      invalid_approval "the emergency run is not the exact authenticated handoff owner."
    if [[ ${RELEASE_MODE:-temporal} == emergency ]]; then
      jq -e --arg tag "$tag" --arg commit "$commit" \
        '.emergencyHandoff == true and .handoff.action == "handoff-manual" and
         .handoff.tag == $tag and .handoff.commitSha == $commit' "$RELEASE_INPUT_FILE" >/dev/null ||
        invalid_approval "durable Temporal handoff evidence is missing."
    fi
  elif ! jq -e --arg actor "$approval_actor" \
    '.status == "completed" and .conclusion == "success" and .event == "issues" and
     .path == ".github/workflows/temporal-release-approve.yml" and
     .triggering_actor.login == $actor' <<<"$approval_run" >/dev/null; then
    if jq -e '.status != "completed"' <<<"$approval_run" >/dev/null; then
      fail "the exact approval run has not completed yet."
    fi
    invalid_approval "the completed GitHub run is not the recorded release approval."
  else
    local issue actual_body_hash
    if ! issue=$(gh api "repos/temporalio/sdk-java/issues/$approval_issue_number"); then
      fail "the exact approval issue is temporarily unavailable."
    fi
    actual_body_hash=$(python3 -c 'import hashlib,json,sys; print(hashlib.sha256(json.load(sys.stdin)["body"].encode()).hexdigest())' <<<"$issue")
    jq -e --arg actor "$approval_actor" --arg node "$approval_issue_node_id" \
      --argjson number "$approval_issue_number" \
      '.number == $number and .node_id == $node and .state == "closed" and .locked == true and
       .closed_by.login == $actor' <<<"$issue" >/dev/null ||
      invalid_approval "the GitHub issue is not the locked release-specific approval event."
    [[ $actual_body_hash == "$approval_issue_body_hash" ]] ||
      invalid_approval "the immutable approval issue body changed."
  fi
  set +e
  "$TRUSTED_AUTOMATION_ROOT/.github/scripts/temporal-release/verify-approver.sh" \
    "$approval_actor" >/dev/null
  status=$?
  set -e
  [[ $status -eq 0 ]] || {
    [[ $status -eq 43 ]] && invalid_approval "the actor is not an active release manager."
    fail "release-manager membership is temporarily unavailable."
  }
}

materialize_assets() {
  local manifest="$work/manifest.tsv"
  jq -r '.release.manifest.artifacts | sort_by(.name)[] |
    [.name, .sha256, (.size | tostring), .storageKey] | @tsv' \
    "$RELEASE_INPUT_FILE" >"$manifest"
  [[ -s $manifest && $(wc -l <"$manifest" | tr -d ' ') -eq 6 ]] ||
    conflict "the approved native artifact manifest is incomplete."
  while IFS=$'\t' read -r name sha size storage_key; do
    [[ $name =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ && $sha =~ ^[0-9a-f]{64}$ &&
      $size =~ ^[1-9][0-9]*$ ]] || conflict "the artifact manifest contains an invalid record."
    [[ $storage_key =~ ^sdk-java/[0-9a-f]{64}/$name$ ]] ||
      conflict "the artifact manifest contains an invalid storage key."
    aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$storage_key" "$work/assets/$name" \
      --no-progress >/dev/null
    [[ $(wc -c <"$work/assets/$name" | tr -d ' ') == "$size" ]] ||
      conflict "$name has the wrong size in durable storage."
    [[ $(sha256sum "$work/assets/$name" | awk '{print $1}') == "$sha" ]] ||
      conflict "$name has the wrong checksum in durable storage."
  done <"$manifest"
  awk -F '\t' '{print $2 "  " $1}' "$manifest" >"$work/assets/SHA256SUMS"
}

configure_gradle() {
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

  # Frozen trusted hooks provide releaseVersion/releaseCommit support to older maintenance SHAs.
  cp gradle/versioning.gradle "$work/versioning.gradle.original"
  cp gradle/publishing.gradle "$work/publishing.gradle.original"
  cp "$TRUSTED_AUTOMATION_ROOT/gradle/versioning.gradle" gradle/versioning.gradle
  cp "$TRUSTED_AUTOMATION_ROOT/gradle/publishing.gradle" gradle/publishing.gradle
}

central_state() {
  present=0
  missing=0
  mapfile -t maven_artifacts < <(jq -er '.mavenArtifacts[]' "$RELEASE_INPUT_FILE")
  [[ ${#maven_artifacts[@]} -eq 17 ]] || conflict "the fixed Maven artifact policy is incomplete."
  for artifact in "${maven_artifacts[@]}"; do
    pom="$work/$artifact.pom"
    central_url="$central_base/io/temporal/$artifact/$version/$artifact-$version.pom"
    central_status=$(curl --silent --show-error --location --output "$pom" \
      --write-out '%{http_code}' "$central_url")
    case "$central_status" in
      200)
        published_commit=$(python3 - "$pom" <<'PY'
import sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
ns = root.tag.partition("}")[0] + "}" if root.tag.startswith("{") else ""
tag = root.find(f"{ns}scm/{ns}tag")
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
}

reconcile_maven() {
  central_state
  if [[ $present -eq ${#maven_artifacts[@]} ]]; then
    jq -n --arg value "https://central.sonatype.com/artifact/io.temporal/temporal-sdk/$version" \
      '$value' >"$RELEASE_OUTPUT_FILE"
    return
  fi
  [[ $present -eq 0 ]] || fail "Maven publication is partially visible; Temporal will retry."

  intent_key="sdk-java/$release_digest/state/maven-intent.json"
  intent="$work/maven-intent.json"
  jq -n --arg tag "$tag" --arg commitSha "$commit" --arg releaseDigest "$release_digest" \
    '{tag:$tag,commitSha:$commitSha,releaseDigest:$releaseDigest}' >"$intent"
  intent_created=false
  if aws s3api put-object --bucket "$RELEASE_ARTIFACT_BUCKET" --key "$intent_key" \
    --body "$intent" --if-none-match '*' >/dev/null 2>&1; then
    intent_created=true
  else
    aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$intent_key" "$work/existing-intent.json" \
      --no-progress >/dev/null || fail "Unable to reconcile the Maven submission intent."
    cmp -s "$intent" "$work/existing-intent.json" || conflict "the Maven intent differs."
  fi

  configure_gradle
  if [[ $intent_created == true ]]; then
    ./gradlew --no-daemon "-PreleaseVersion=$version" "-PreleaseCommit=$commit" \
      publishToSonatype closeAndReleaseSonatypeStagingRepository >&2
    fail "Maven publication was submitted; Temporal will check Central after backoff."
  fi

  staging="$work/staging.json"
  curl --silent --show-error --fail --user "$RH_USER:$RH_PASSWORD" \
    --header 'Accept: application/json' \
    'https://ossrh-staging-api.central.sonatype.com/service/local/staging/profile_repositories' \
    >"$staging" || fail "Unable to inspect Sonatype after an ambiguous submission."
  mapfile -t repository_ids < <(jq -r '(.data // .profileRepositories // [])[] |
    select((.type // .state // "") != "released") | .repositoryId // .id' "$staging")
  matches=()
  for repository_id in "${repository_ids[@]}"; do
    staged_pom="$work/$repository_id.pom"
    url="https://ossrh-staging-api.central.sonatype.com/service/local/repositories/$repository_id/content/io/temporal/temporal-sdk/$version/temporal-sdk-$version.pom"
    status=$(curl --silent --show-error --user "$RH_USER:$RH_PASSWORD" --output "$staged_pom" \
      --write-out '%{http_code}' "$url")
    [[ $status == 404 ]] && continue
    [[ $status == 200 ]] || fail "Sonatype returned HTTP $status for staging repository $repository_id."
    grep -Fq "<tag>$commit</tag>" "$staged_pom" && matches+=("$repository_id")
  done
  [[ ${#matches[@]} -le 1 ]] || conflict "multiple Sonatype repositories match the immutable release."
  [[ ${#matches[@]} -eq 1 ]] ||
    fail "Maven submission is ambiguous; no duplicate submission will be attempted."
  ./gradlew --no-daemon "-PreleaseVersion=$version" "-PreleaseCommit=$commit" \
    "-PsonatypeStagingRepositoryId=${matches[0]}" closeAndReleaseSonatypeStagingRepository >&2
  fail "The recovered Sonatype repository was released; Temporal will check Central after backoff."
}

release_json() {
  gh api 'repos/temporalio/sdk-java/releases?per_page=100' 2>/dev/null |
    jq -c --arg tag "$tag" 'map(select(.tag_name == $tag)) | first // empty' || true
}

verify_release_metadata() {
  local release=$1 expected_draft=$2 prerelease=false
  [[ $tag == *-RC* ]] && prerelease=true
  jq -e --arg tag "$tag" --arg commit "$commit" --rawfile notes "$notes_file" --argjson prerelease "$prerelease" \
    --argjson draft "$expected_draft" \
    '.tag_name == $tag and .name == $tag and .body == $notes and
     .draft == $draft and .prerelease == $prerelease and .target_commitish == $commit' \
    <<<"$release" >/dev/null || conflict "GitHub release metadata differs from the approved release."
}

reconcile_github_draft() {
  materialize_assets
  tag_ref=$(gh api "repos/temporalio/sdk-java/git/ref/tags/$tag" 2>/dev/null || true)
  if [[ -n $tag_ref ]]; then
    [[ $(jq -r '.object.type' <<<"$tag_ref") == commit &&
      $(jq -r '.object.sha' <<<"$tag_ref") == "$commit" ]] ||
      conflict "the Git tag points at another object."
  fi
  release=$(release_json)
  if [[ -z $release ]]; then
    create_args=(release create "$tag" --repo temporalio/sdk-java --draft --target "$commit"
      --title "$tag" --notes-file "$notes_file")
    [[ $tag == *-RC* ]] && create_args+=(--prerelease)
    gh "${create_args[@]}" >/dev/null
    release=$(release_json)
  fi
  draft=$(jq -r '.draft' <<<"$release")
  [[ $draft == true || $draft == false ]] || conflict "GitHub returned an invalid release state."
  verify_release_metadata "$release" "$draft"

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
      [[ $draft == true ]] || conflict "a public release is missing approved asset $name."
      gh release upload "$tag" "$work/assets/$name" --repo temporalio/sdk-java >/dev/null
    fi
  done
  release=$(release_json)
  mapfile -t final_assets < <(jq -r '.assets[].name' <<<"$release" | sort)
  [[ ${final_assets[*]} == "${expected_assets[*]}" ]] ||
    conflict "the final GitHub asset set is not exact."
  jq -n --arg value "$(jq -er '.html_url' <<<"$release")" '$value' >"$RELEASE_OUTPUT_FILE"
}

publish_github_release() {
  # Re-read and checksum the exact seven assets immediately before the final public mutation.
  reconcile_github_draft
  release=$(release_json)
  [[ -n $release ]] || conflict "the verified GitHub draft disappeared."
  if [[ $(jq -r '.draft' <<<"$release") == true ]]; then
    verify_release_metadata "$release" true
    gh release edit "$tag" --repo temporalio/sdk-java --draft=false >/dev/null
  fi
  release=$(release_json)
  verify_release_metadata "$release" false
  tag_ref=$(gh api "repos/temporalio/sdk-java/git/ref/tags/$tag")
  [[ $(jq -r '.object.type' <<<"$tag_ref") == commit &&
    $(jq -r '.object.sha' <<<"$tag_ref") == "$commit" ]] ||
    conflict "the published tag does not point at the approved commit."
  release_url=$(jq -er '.html_url' <<<"$release")
  jq -n --arg releaseDigest "$release_digest" --arg githubReleaseUrl "$release_url" \
    --arg mavenCentralUrl "https://central.sonatype.com/artifact/io.temporal/temporal-sdk/$version" \
    '{releaseDigest:$releaseDigest,githubReleaseUrl:$githubReleaseUrl,
      mavenCentralUrl:$mavenCentralUrl}' >"$RELEASE_OUTPUT_FILE"
}

inspect_external_state() {
  inspect_ownership
  materialize_assets
  central_state

  intent_key="sdk-java/$release_digest/state/maven-intent.json"
  sonatype_state=not-submitted
  if aws s3api head-object --bucket "$RELEASE_ARTIFACT_BUCKET" --key "$intent_key" \
    >/dev/null 2>&1; then
    sonatype_state=intent-recorded
    curl --silent --show-error --fail --user "$RH_USER:$RH_PASSWORD" \
      --header 'Accept: application/json' \
      'https://ossrh-staging-api.central.sonatype.com/service/local/staging/profile_repositories' \
      >"$work/staging-inspection.json" || fail "Unable to inspect Sonatype staging state."
    jq -e '(.data // .profileRepositories // []) | type == "array"' \
      "$work/staging-inspection.json" >/dev/null || fail "Sonatype returned invalid staging state."
  fi

  tag_state=absent
  tag_ref=$(gh api "repos/temporalio/sdk-java/git/ref/tags/$tag" 2>/dev/null || true)
  if [[ -n $tag_ref ]]; then
    [[ $(jq -r '.object.type' <<<"$tag_ref") == commit &&
      $(jq -r '.object.sha' <<<"$tag_ref") == "$commit" ]] ||
      conflict "the Git tag points at another object."
    tag_state=exact
  fi

  release_state=absent
  release=$(release_json)
  if [[ -n $release ]]; then
    draft=$(jq -r '.draft' <<<"$release")
    verify_release_metadata "$release" "$draft"
    release_state=$([[ $draft == true ]] && echo exact-draft || echo exact-public)
    mapfile -t expected_assets < <(find "$work/assets" -mindepth 1 -maxdepth 1 -type f -exec basename {} \; | sort)
    mapfile -t remote_assets < <(jq -r '.assets[].name' <<<"$release" | sort)
    for remote_name in "${remote_assets[@]}"; do
      printf '%s\n' "${expected_assets[@]}" | grep -Fxq "$remote_name" ||
        conflict "the GitHub release has unexpected asset $remote_name."
      gh release download "$tag" --repo temporalio/sdk-java --pattern "$remote_name" \
        --dir "$work/existing" --clobber >/dev/null
      [[ $(sha256sum "$work/existing/$remote_name" | awk '{print $1}') ==
        $(sha256sum "$work/assets/$remote_name" | awk '{print $1}') ]] ||
        conflict "existing GitHub asset $remote_name has different bytes."
    done
  fi

  jq -n --argjson mavenPresent "$present" --argjson mavenMissing "$missing" \
    --arg sonatype "$sonatype_state" --arg tag "$tag_state" --arg release "$release_state" \
    '{mavenPresent:$mavenPresent,mavenMissing:$mavenMissing,sonatype:$sonatype,
      tag:$tag,release:$release}' >"$RELEASE_OUTPUT_FILE"
}

verify_approval
case "$RELEASE_STAGE" in
  inspect) inspect_external_state ;;
  preflight)
    ensure_ownership
    materialize_assets
    ;;
  maven)
    ensure_ownership
    reconcile_maven
    ;;
  github-draft)
    ensure_ownership
    reconcile_github_draft
    ;;
  github-publish)
    ensure_ownership
    publish_github_release
    ;;
  *) fail "Temporal scheduled an unknown publication stage." ;;
esac
