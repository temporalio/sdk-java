#!/usr/bin/env bash

set -euo pipefail

fail() { echo "reconcile-publication: $*" >&2; exit 1; }
conflict() { echo "reconcile-publication: immutable release conflict: $*" >&2; exit 42; }
invalid_approval() { echo "reconcile-publication: invalid approval: $*" >&2; exit 43; }
maven_ambiguous() { echo "reconcile-publication: ambiguous Maven submission: $*" >&2; exit 44; }

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
maven_policy=$(jq -er '.release.candidate.mavenPolicy' "$RELEASE_INPUT_FILE")
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
manual_ownership="$work/manual-ownership.json"
jq -n --arg tag "$tag" --arg commitSha "$commit" --arg releaseDigest "$release_digest" \
  --arg owner manual '{tag:$tag,commitSha:$commitSha,releaseDigest:$releaseDigest,owner:$owner}' \
  >"$manual_ownership"

validate_ownership_identity() {
  jq -e --arg tag "$tag" --arg commit "$commit" --arg digest "$release_digest" \
    '.tag == $tag and .commitSha == $commit and .releaseDigest == $digest and
     (.owner == "temporal" or .owner == "manual")' "$1" >/dev/null ||
    conflict "the tag/version ownership key belongs to another release or SHA."
}

validate_ownership_tag_and_sha() {
  jq -e --arg tag "$tag" --arg commit "$commit" \
    '.tag == $tag and .commitSha == $commit and
     (.owner == "temporal" or .owner == "manual")' "$1" >/dev/null ||
    conflict "the tag/version ownership key belongs to another tag or SHA."
}

ensure_ownership() {
  expected_ownership=$ownership
  [[ ${RELEASE_MODE:-temporal} == emergency ]] && expected_ownership=$manual_ownership
  if [[ ${RELEASE_MODE:-temporal} == temporal ]] &&
    aws s3api head-object --bucket "$RELEASE_ARTIFACT_BUCKET" \
      --key "sdk-java/emergency/$tag.json" >/dev/null 2>&1; then
    aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/sdk-java/emergency/$tag.json" \
      "$work/emergency-request.json" --no-progress >/dev/null ||
      fail "Unable to inspect the durable emergency request."
    emergency_state=$(jq -er '.state' "$work/emergency-request.json")
    case "$emergency_state" in
      READY | BLOCKED | COMPLETE)
        conflict "durable ownership has been transferred to emergency automation."
        ;;
    esac
  fi
  if ! aws s3api put-object --bucket "$RELEASE_ARTIFACT_BUCKET" --key "$ownership_key" \
    --body "$expected_ownership" --if-none-match '*' >/dev/null 2>&1; then
    aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$ownership_key" "$work/existing-ownership.json" \
      --no-progress >/dev/null || fail "Unable to read the durable tag ownership key."
    cmp -s "$expected_ownership" "$work/existing-ownership.json" ||
      conflict "the tag/version ownership key belongs to another controller or SHA."
  fi
}

inspect_ownership() {
  ownership_state=absent
  if aws s3api head-object --bucket "$RELEASE_ARTIFACT_BUCKET" --key "$ownership_key" \
    >/dev/null 2>&1; then
    aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$ownership_key" "$work/existing-ownership.json" \
      --no-progress >/dev/null || fail "Unable to inspect the durable tag ownership key."
    if [[ ${RELEASE_MODE:-temporal} == emergency-inspect ]]; then
      validate_ownership_tag_and_sha "$work/existing-ownership.json"
    else
      validate_ownership_identity "$work/existing-ownership.json"
    fi
    ownership_state=$(jq -er .owner "$work/existing-ownership.json")
  fi
}

claim_manual_ownership() {
  if aws s3api head-object --bucket "$RELEASE_ARTIFACT_BUCKET" --key "$ownership_key" \
    >"$work/ownership-head.json" 2>/dev/null; then
    aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$ownership_key" "$work/existing-ownership.json" \
      --no-progress >/dev/null || fail "Unable to read the durable tag ownership key."
    validate_ownership_tag_and_sha "$work/existing-ownership.json"
    existing_owner=$(jq -er .owner "$work/existing-ownership.json")
    if [[ $existing_owner == manual ]]; then
      validate_ownership_identity "$work/existing-ownership.json"
    elif jq -e --arg digest "$release_digest" '.releaseDigest != $digest' \
      "$work/existing-ownership.json" >/dev/null; then
      jq -e '.release.manifest.artifacts | all(.storageKey |
        startswith("sdk-java/emergency-artifacts/"))' "$RELEASE_INPUT_FILE" >/dev/null ||
        conflict "a Temporal-owned release can change digest only to one replacement manifest."
    fi
    if cmp -s "$manual_ownership" "$work/existing-ownership.json"; then
      return
    fi
    etag=$(jq -er '.ETag' "$work/ownership-head.json")
    aws s3api put-object --bucket "$RELEASE_ARTIFACT_BUCKET" --key "$ownership_key" \
      --body "$manual_ownership" --if-match "$etag" >/dev/null ||
      fail "The ownership key changed while manual handoff was being claimed."
  else
    aws s3api put-object --bucket "$RELEASE_ARTIFACT_BUCKET" --key "$ownership_key" \
      --body "$manual_ownership" --if-none-match '*' >/dev/null ||
      fail "Unable to claim durable manual ownership."
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
    [[ $storage_key =~ ^sdk-java/[0-9a-f]{64}/$name$ ||
      $storage_key =~ ^sdk-java/emergency-artifacts/[0-9a-f]{64}/[0-9a-f]{64}/$name$ ]] ||
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

verify_source_maven_policy() {
  mapfile -t source_projects < <(
    sed -n -E "s/^include ['\"]([^'\"]+)['\"]$/\1/p" settings.gradle | sort
  )
  mapfile -t policy_projects < <(jq -er '.mavenArtifacts[]' "$RELEASE_INPUT_FILE" | sort)
  [[ ${source_projects[*]} == "${policy_projects[*]}" ]] ||
    conflict "the immutable source projects differ from the approved Maven policy."
  case "$maven_policy:${#policy_projects[@]}" in
    current:17 | classic:11 | classic-alpha:11 | classic-alpha-lite:9) ;;
    *) conflict "the immutable Maven policy is not a reviewed sdk-java profile." ;;
  esac
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
  verify_source_maven_policy
  for artifact in "${maven_artifacts[@]}"; do
    pom="$work/$artifact.pom"
    central_url="$central_base/io/temporal/$artifact/$version/$artifact-$version.pom"
    central_status=$(curl --silent --show-error --location --output "$pom" \
      --write-out '%{http_code}' "$central_url")
    case "$central_status" in
      200)
        published_identity=$(python3 - "$pom" <<'PY'
import sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
ns = root.tag.partition("}")[0] + "}" if root.tag.startswith("{") else ""
tag = root.find(f"{ns}scm/{ns}tag")
values = [
    root.findtext(f"{ns}groupId", "").strip(),
    root.findtext(f"{ns}artifactId", "").strip(),
    root.findtext(f"{ns}version", "").strip(),
    "" if tag is None or tag.text is None else tag.text.strip().lower(),
]
print("\t".join(values))
PY
)
        IFS=$'\t' read -r published_group published_artifact published_version \
          published_commit <<<"$published_identity"
        [[ $published_group == "$maven_group" && $published_artifact == "$artifact" &&
          $published_version == "$version" && $published_commit == "$commit" ]] ||
          conflict "$artifact coordinates contain another immutable Maven identity."
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
    repository_id=""
    intent_key="sdk-java/$release_digest/state/maven-intent.json"
    if aws s3api head-object --bucket "$RELEASE_ARTIFACT_BUCKET" --key "$intent_key" \
      >/dev/null 2>&1; then
      aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$intent_key" "$work/completed-intent.json" \
        --no-progress >/dev/null || fail "Unable to read the completed Maven intent."
      current_generation=$(jq -er '.mavenSubmissionGeneration' "$RELEASE_INPUT_FILE")
      jq -e --arg tag "$tag" --arg commit "$commit" --arg digest "$release_digest" \
        --argjson currentGeneration "$current_generation" \
        '.tag == $tag and .commitSha == $commit and .releaseDigest == $digest and
         (.generation | type == "number") and .generation <= $currentGeneration and
         (.description | type == "string")' "$work/completed-intent.json" >/dev/null ||
        conflict "the completed Maven intent differs."
      completed_generation=$(jq -er .generation "$work/completed-intent.json")
      completed_description=$(jq -er .description "$work/completed-intent.json")
      [[ $completed_description == "sdk-java:$release_digest:$completed_generation" ]] ||
        conflict "the completed Maven intent description differs."
      repository_id=$(jq -r '.repositoryId // ""' "$work/completed-intent.json")
    fi
    jq -n --arg mavenCentralUrl \
      "https://central.sonatype.com/artifact/io.temporal/temporal-sdk/$version" \
      --arg sonatypeRepositoryId "$repository_id" \
      '{mavenCentralUrl:$mavenCentralUrl,sonatypeRepositoryId:$sonatypeRepositoryId}' \
      >"$RELEASE_OUTPUT_FILE"
    return
  fi
  [[ $present -eq 0 ]] || fail "Maven publication is partially visible; Temporal will retry."

  intent_key="sdk-java/$release_digest/state/maven-intent.json"
  intent="$work/maven-intent.json"
  submission_generation=$(jq -er '.mavenSubmissionGeneration' "$RELEASE_INPUT_FILE")
  [[ $submission_generation =~ ^[0-9]+$ ]] || conflict "invalid Maven submission generation."
  staging_description="sdk-java:$release_digest:$submission_generation"
  jq -n --arg tag "$tag" --arg commitSha "$commit" --arg releaseDigest "$release_digest" \
    --arg description "$staging_description" --argjson generation "$submission_generation" \
    '{tag:$tag,commitSha:$commitSha,releaseDigest:$releaseDigest,
      description:$description,generation:$generation}' >"$intent"
  may_create_repository=false
  if aws s3api put-object --bucket "$RELEASE_ARTIFACT_BUCKET" --key "$intent_key" \
    --body "$intent" --if-none-match '*' >/dev/null 2>&1; then
    may_create_repository=true
  else
    aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$intent_key" "$work/existing-intent.json" \
      --no-progress >/dev/null || fail "Unable to reconcile the Maven submission intent."
    jq -e --arg tag "$tag" --arg commit "$commit" --arg digest "$release_digest" \
      '.tag == $tag and .commitSha == $commit and .releaseDigest == $digest and
       (.generation | type == "number") and (.description | type == "string")' \
      "$work/existing-intent.json" >/dev/null || conflict "the Maven intent differs."
    stored_generation=$(jq -er '.generation' "$work/existing-intent.json")
    stored_description=$(jq -er .description "$work/existing-intent.json")
    [[ $stored_description == "sdk-java:$release_digest:$stored_generation" ]] ||
      conflict "the Maven intent description differs."
    if (( submission_generation == stored_generation + 1 )); then
      # This generation is reachable only through the authenticated Workflow control Update.
      aws s3api put-object --bucket "$RELEASE_ARTIFACT_BUCKET" --key "$intent_key" \
        --body "$intent" >/dev/null
      may_create_repository=true
    elif (( submission_generation != stored_generation )); then
      conflict "the Maven submission generation is not the durable next generation."
    else
      cp "$work/existing-intent.json" "$intent"
      staging_description=$(jq -er '.description' "$intent")
    fi
  fi

  configure_gradle
  staging="$work/staging.json"
  curl --silent --show-error --fail --user "$RH_USER:$RH_PASSWORD" \
    --header 'Accept: application/json' \
    'https://ossrh-staging-api.central.sonatype.com/service/local/staging/profile_repositories' \
    >"$staging" || fail "Unable to inspect Sonatype after an ambiguous submission."
  mapfile -t matches < <(jq -r --arg description "$staging_description" \
    '(.data // .profileRepositories // [])[] |
     select(.description == $description) | .repositoryId // .id' "$staging")
  [[ ${#matches[@]} -le 1 ]] || conflict "multiple Sonatype repositories match the immutable release."
  if [[ ${#matches[@]} -eq 0 ]]; then
    [[ $may_create_repository == true ]] ||
      maven_ambiguous "intent exists but its exact described repository is absent."
    initialize_log="$work/initialize-sonatype.log"
    ./gradlew --no-daemon "-PreleaseVersion=$version" "-PreleaseCommit=$commit" \
      "-PreleaseDigest=$release_digest" \
      "-PmavenSubmissionGeneration=$submission_generation" \
      initializeSonatypeStagingRepository 2>&1 | tee "$initialize_log" >&2
    repository_id=$(sed -n -E "s/.*Created staging repository '([^']+)'.*/\1/p" \
      "$initialize_log" | tail -1)
    [[ -n $repository_id ]] || fail "Unable to capture the created Sonatype repository ID."
    matches=("$repository_id")
  fi

  repository_id=${matches[0]}
  jq --arg repositoryId "$repository_id" '.repositoryId = $repositoryId' "$intent" \
    >"$work/intent-with-repository.json"
  aws s3api put-object --bucket "$RELEASE_ARTIFACT_BUCKET" --key "$intent_key" \
    --body "$work/intent-with-repository.json" >/dev/null

  ./gradlew --no-daemon "-PreleaseVersion=$version" "-PreleaseCommit=$commit" \
    "-PreleaseDigest=$release_digest" \
    "-PmavenSubmissionGeneration=$submission_generation" \
    findSonatypeStagingRepository publishToSonatype \
    closeAndReleaseSonatypeStagingRepository >&2
  fail "Sonatype repository $repository_id was reconciled; Temporal will check Central after backoff."
}

release_json() {
  local releases
  releases=$(gh api --paginate --slurp 'repos/temporalio/sdk-java/releases?per_page=100') ||
    fail "GitHub releases are temporarily unavailable."
  jq -c --arg tag "$tag" '[.[][]] | map(select(.tag_name == $tag)) | first // empty' \
    <<<"$releases"
}

github_optional_get() {
  local path=$1 output=$2 status
  status=$(curl --silent --show-error --location --output "$output" --write-out '%{http_code}' \
    --header "Authorization: Bearer $GH_TOKEN" --header 'Accept: application/vnd.github+json' \
    --header 'X-GitHub-Api-Version: 2022-11-28' "https://api.github.com/$path") ||
    fail "GitHub is temporarily unavailable while reading $path."
  case "$status" in
    200) return 0 ;;
    404) : >"$output"; return 1 ;;
    *) fail "GitHub returned HTTP $status while reading $path." ;;
  esac
}

ensure_exact_tag() {
  local tag_file="$work/tag.json"
  if github_optional_get "repos/temporalio/sdk-java/git/ref/tags/$tag" "$tag_file"; then
    [[ $(jq -r '.object.type' "$tag_file") == commit &&
      $(jq -r '.object.sha' "$tag_file") == "$commit" ]] ||
      conflict "the Git tag points at another object."
    return
  fi
  if ! gh api --method POST repos/temporalio/sdk-java/git/refs \
    --raw-field ref="refs/tags/$tag" --raw-field sha="$commit" >/dev/null; then
    # A concurrent creator may have won. A fresh authoritative read decides whether it is exact.
    github_optional_get "repos/temporalio/sdk-java/git/ref/tags/$tag" "$tag_file" ||
      fail "The exact Git tag could not be created or reconciled."
    [[ $(jq -r '.object.type' "$tag_file") == commit &&
      $(jq -r '.object.sha' "$tag_file") == "$commit" ]] ||
      conflict "the concurrently created Git tag points at another object."
  fi
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
  ensure_exact_tag
  release=$(release_json)
  if [[ -z $release ]]; then
    create_args=(release create "$tag" --repo temporalio/sdk-java --draft --target "$commit"
      --title "$tag" --notes-file "$notes_file")
    [[ $tag == *-RC* ]] && create_args+=(--prerelease)
    gh "${create_args[@]}" >/dev/null
    release=$(release_json)
    [[ -n $release ]] || fail "The new GitHub draft is not visible yet."
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
  [[ -n $release ]] || fail "The GitHub draft is temporarily unavailable."
  mapfile -t final_assets < <(jq -r '.assets[].name' <<<"$release" | sort)
  for final_name in "${final_assets[@]}"; do
    printf '%s\n' "${expected_assets[@]}" | grep -Fxq "$final_name" ||
      conflict "the GitHub release gained unexpected asset $final_name."
  done
  [[ ${final_assets[*]} == "${expected_assets[*]}" ]] ||
    fail "The uploaded GitHub asset set is not fully visible yet."
  jq -n --arg value "$(jq -er '.html_url' <<<"$release")" '$value' >"$RELEASE_OUTPUT_FILE"
}

publish_github_release() {
  # Re-read and checksum the exact seven assets immediately before the final public mutation.
  reconcile_github_draft
  release=$(release_json)
  [[ -n $release ]] || conflict "the verified GitHub draft disappeared."
  if [[ $(jq -r '.draft' <<<"$release") == true ]]; then
    verify_release_metadata "$release" true
    # This is the last check before the public mutation; creation happens earlier and is immutable.
    ensure_exact_tag
    gh release edit "$tag" --repo temporalio/sdk-java --draft=false >/dev/null
  fi
  release=$(release_json)
  [[ -n $release ]] || fail "The published GitHub release is not visible yet."
  [[ $(jq -r '.draft' <<<"$release") == false ]] ||
    fail "GitHub has not made the release public yet."
  verify_release_metadata "$release" false
  ensure_exact_tag
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
  sonatype_repository_id=""
  if aws s3api head-object --bucket "$RELEASE_ARTIFACT_BUCKET" --key "$intent_key" \
    >/dev/null 2>&1; then
    sonatype_state=intent-recorded
    aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$intent_key" "$work/inspection-intent.json" \
      --no-progress >/dev/null || fail "Unable to inspect the Maven intent."
    current_generation=$(jq -er '.mavenSubmissionGeneration' "$RELEASE_INPUT_FILE")
    jq -e --arg tag "$tag" --arg commit "$commit" --arg digest "$release_digest" \
      --argjson generation "$current_generation" \
      '.tag == $tag and .commitSha == $commit and .releaseDigest == $digest and
       (.generation | type == "number") and .generation <= $generation and
       (.description | type == "string")' \
      "$work/inspection-intent.json" >/dev/null || conflict "the Maven intent differs."
    intent_generation=$(jq -er .generation "$work/inspection-intent.json")
    sonatype_description=$(jq -er .description "$work/inspection-intent.json")
    [[ $sonatype_description == "sdk-java:$release_digest:$intent_generation" ]] ||
      conflict "the Maven intent description differs."
    curl --silent --show-error --fail --user "$RH_USER:$RH_PASSWORD" \
      --header 'Accept: application/json' \
      'https://ossrh-staging-api.central.sonatype.com/service/local/staging/profile_repositories' \
      >"$work/staging-inspection.json" || fail "Unable to inspect Sonatype staging state."
    jq -e '(.data // .profileRepositories // []) | type == "array"' \
      "$work/staging-inspection.json" >/dev/null || fail "Sonatype returned invalid staging state."
    mapfile -t inspection_matches < <(jq -r --arg description "$sonatype_description" \
      '(.data // .profileRepositories // [])[] |
       select(.description == $description) | .repositoryId // .id' \
      "$work/staging-inspection.json")
    [[ ${#inspection_matches[@]} -le 1 ]] ||
      conflict "multiple Sonatype repositories match the immutable release."
    if [[ ${#inspection_matches[@]} -eq 1 ]]; then
      sonatype_state=exact-repository-found
      sonatype_repository_id=${inspection_matches[0]}
    else
      sonatype_state=exact-repository-absent
      sonatype_repository_id=$(jq -r '.repositoryId // ""' "$work/inspection-intent.json")
    fi
  fi

  tag_state=absent
  if github_optional_get "repos/temporalio/sdk-java/git/ref/tags/$tag" "$work/inspect-tag.json"; then
    [[ $(jq -r '.object.type' "$work/inspect-tag.json") == commit &&
      $(jq -r '.object.sha' "$work/inspect-tag.json") == "$commit" ]] ||
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
    --arg sonatype "$sonatype_state" --arg sonatypeRepositoryId "$sonatype_repository_id" \
    --argjson mavenSubmissionGeneration \
      "$(jq -er '.mavenSubmissionGeneration' "$RELEASE_INPUT_FILE")" \
    --argjson mavenIntentGeneration "${intent_generation:--1}" \
    --arg ownership "$ownership_state" --arg tag "$tag_state" --arg release "$release_state" \
    '{mavenPresent:$mavenPresent,mavenMissing:$mavenMissing,sonatype:$sonatype,
      sonatypeRepositoryId:$sonatypeRepositoryId,
      mavenSubmissionGeneration:$mavenSubmissionGeneration,
      mavenIntentGeneration:$mavenIntentGeneration,ownership:$ownership,
      tag:$tag,release:$release}' \
    >"$RELEASE_OUTPUT_FILE"
}

verify_approval
case "$RELEASE_STAGE" in
  inspect) inspect_external_state ;;
  handoff) claim_manual_ownership ;;
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
