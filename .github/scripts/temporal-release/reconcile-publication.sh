#!/usr/bin/env bash

set -euo pipefail

fail() { echo "reconcile-publication: $*" >&2; exit 1; }
conflict() { echo "reconcile-publication: immutable release conflict: $*" >&2; exit 42; }
invalid_approval() { echo "reconcile-publication: invalid approval: $*" >&2; exit 43; }
maven_ambiguous() { echo "reconcile-publication: ambiguous Maven submission: $*" >&2; exit 44; }

s3_object_exists() {
  local key=$1 output=${2:-$work/s3-head.json} error="$work/s3-head-error.txt" status
  set +e
  aws s3api head-object --bucket "$RELEASE_ARTIFACT_BUCKET" --key "$key" \
    >"$output" 2>"$error"
  status=$?
  set -e
  if [[ $status -eq 0 ]]; then
    return 0
  fi
  if grep -Eqi '(404|Not Found|NoSuchKey|NotFound)' "$error"; then
    return 1
  fi
  fail "durable object state for $key is temporarily unavailable."
}

required=(
  EXPECTED_APPROVAL_ACTOR EXPECTED_APPROVAL_RUN_ID EXPECTED_COMMIT_SHA
  EXPECTED_MANIFEST_SHA256 EXPECTED_NOTES_SHA256 EXPECTED_RELEASE_DIGEST
  EXPECTED_MAVEN_SUBMISSION_GENERATION
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
maven_submission_generation=$(jq -er '.mavenSubmissionGeneration' "$RELEASE_INPUT_FILE")
maven_group=$(jq -er '.mavenGroup' "$RELEASE_INPUT_FILE")
central_base=$(jq -er '.mavenCentralBase' "$RELEASE_INPUT_FILE")
candidate_digest=$(jq -er '.candidateDigest' "$RELEASE_INPUT_FILE")

[[ $repository == temporalio/sdk-java && $repository == "$EXPECTED_REPOSITORY" &&
  $tag == "$EXPECTED_TAG" && $commit == "$EXPECTED_COMMIT_SHA" &&
  $notes_hash == "$EXPECTED_NOTES_SHA256" && $manifest_hash == "$EXPECTED_MANIFEST_SHA256" &&
  $release_digest == "$EXPECTED_RELEASE_DIGEST" && $workflow_id == "$EXPECTED_WORKFLOW_ID" &&
  $run_id == "$EXPECTED_RUN_ID" && $approval_run_id == "$EXPECTED_APPROVAL_RUN_ID" &&
  $approval_actor == "$EXPECTED_APPROVAL_ACTOR" && $trusted_commit == "$TRUSTED_WORKER_COMMIT" &&
  $maven_submission_generation == "$EXPECTED_MAVEN_SUBMISSION_GENERATION" &&
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
trap 'if [[ -f $work/versioning.gradle.original ]]; then cp "$work/versioning.gradle.original" gradle/versioning.gradle; cp "$work/publishing.gradle.original" gradle/publishing.gradle; cp "$work/build.gradle.original" build.gradle; fi; rm -rf "$work"' EXIT
mkdir -p "$work/assets" "$work/existing"
source_hooks_configured=false

verify_candidate_receipts() {
  [[ ${RELEASE_MODE:-temporal} == temporal ]] || return
  [[ $candidate_digest =~ ^[0-9a-f]{64}$ ]] ||
    conflict "the Activity input has an invalid candidate digest."
  candidate_receipt="$work/candidate-receipt.json"
  candidate_from_input="$work/candidate-from-input.json"
  aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/sdk-java/candidates/$tag.json" \
    "$candidate_receipt" --no-progress >/dev/null ||
    fail "the protected immutable candidate receipt is temporarily unavailable."
  jq -S .release.candidate "$RELEASE_INPUT_FILE" >"$candidate_from_input"
  jq -S . "$candidate_receipt" >"$work/candidate-receipt.sorted.json"
  cmp -s "$candidate_from_input" "$work/candidate-receipt.sorted.json" ||
    invalid_approval "the protected candidate receipt differs from the Activity identity."
  start_receipt="$work/candidate-start-receipt.json"
  aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/sdk-java/candidates-started/$tag.json" \
    "$start_receipt" --no-progress >/dev/null ||
    fail "the protected Candidate Workflow start receipt is temporarily unavailable."
  jq -e --arg tag "$tag" --arg digest "$candidate_digest" \
    --arg workflow "sdk-java-release-candidate/$candidate_digest" \
    '.tag == $tag and .candidateDigest == $digest and .workflowId == $workflow and
     (.runId | test("^[0-9a-fA-F-]{16,64}$"))' "$start_receipt" >/dev/null ||
    invalid_approval "the protected Candidate Workflow start receipt differs."
}

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
  manual_title="[sdk-java manual release ownership] $tag"
  manual_issues=$(gh api --paginate --slurp \
    'repos/temporalio/sdk-java/issues?state=open&per_page=100') ||
    fail "Unable to inspect independent manual ownership."
  manual_creator=$(gh api user --jq .login) ||
    fail "Unable to identify the trusted publication bot."
  manual_matches=$(jq -c --arg title "$manual_title" --arg creator "$manual_creator" \
    '[.[][] | select((has("pull_request") | not) and .title == $title and
      .user.login == $creator)]' \
    <<<"$manual_issues")
  [[ $(jq 'length' <<<"$manual_matches") -le 1 ]] ||
    conflict "multiple independent manual ownership records exist."
  if [[ $(jq 'length' <<<"$manual_matches") -eq 1 ]]; then
    manual_issue=$(jq -c '.[0]' <<<"$manual_matches")
    manual_body=$(jq -r .body <<<"$manual_issue")
    [[ $manual_body == *"- Tag: \`$tag\`"* &&
      $manual_body == *"- Full SHA: \`$commit\`"* &&
      $manual_body == *"- Controller SHA: \`$TRUSTED_WORKER_COMMIT\`"* ]] ||
      conflict "independent manual ownership identifies another SHA."
    manual_actor=$(sed -n -E 's/^- Actor: `([^`]+)`$/\1/p' <<<"$manual_body")
    manual_run=$(sed -n -E 's/^- Actions run: `([0-9]+)`$/\1/p' <<<"$manual_body")
    [[ -n $manual_actor && $manual_run =~ ^[0-9]+$ ]] ||
      conflict "independent manual ownership lacks authenticated run evidence."
    jq -e --arg creator "$manual_creator" \
      '.locked == true and .user.login == $creator' <<<"$manual_issue" >/dev/null ||
      conflict "independent manual ownership was not created by the trusted release bot."
    manual_run_json=$(gh api "repos/temporalio/sdk-java/actions/runs/$manual_run") ||
      fail "Unable to inspect the independent ownership Actions run."
    jq -e --arg actor "$manual_actor" \
      '.path == ".github/workflows/prepare-release.yml" and
       .event == "workflow_dispatch" and .actor.login == $actor and
       (.status == "in_progress" or .status == "completed")' \
      <<<"$manual_run_json" >/dev/null ||
      conflict "independent ownership is not bound to the trusted fallback workflow."
    set +e
    "$TRUSTED_AUTOMATION_ROOT/.github/scripts/temporal-release/verify-approver.sh" \
      "$manual_actor" >/dev/null
    manual_actor_status=$?
    set -e
    if [[ $manual_actor_status -eq 43 ]]; then
      invalid_approval "the independent fallback owner is not an active release manager."
    elif [[ $manual_actor_status -ne 0 ]]; then
      fail "release-manager membership is temporarily unavailable for manual ownership."
    fi
    conflict "independent manual ownership is active for this tag."
  fi
  expected_ownership=$ownership
  [[ ${RELEASE_MODE:-temporal} == emergency ]] && expected_ownership=$manual_ownership
  if [[ ${RELEASE_MODE:-temporal} == temporal ]] &&
    s3_object_exists "sdk-java/emergency/$tag.json"; then
    aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/sdk-java/emergency/$tag.json" \
      "$work/emergency-request.json" --no-progress >/dev/null ||
      fail "Unable to inspect the durable emergency request."
    emergency_state=$(jq -er '.state' "$work/emergency-request.json")
    if jq -e '.handoff != null' "$work/emergency-request.json" >/dev/null; then
      conflict "durable ownership has been transferred to emergency automation."
    fi
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
  if s3_object_exists "$ownership_key"; then
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
  if s3_object_exists "$ownership_key" "$work/ownership-head.json"; then
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
      '.event == "workflow_dispatch" and
       .path == ".github/workflows/temporal-release-emergency-control.yml" and
       (.status == "in_progress" or (.status == "completed" and .conclusion == "success")) and
       .actor.login == $actor' <<<"$approval_run" >/dev/null ||
      invalid_approval "the emergency run is not the exact authenticated handoff owner."
    if [[ ${RELEASE_MODE:-temporal} == emergency ]]; then
      jq -e --arg tag "$tag" --arg commit "$commit" \
        '.emergencyHandoff == true and .handoff.action == "handoff-manual" and
         .handoff.tag == $tag and .handoff.commitSha == $commit' "$RELEASE_INPUT_FILE" >/dev/null ||
        invalid_approval "durable Temporal handoff evidence is missing."
    fi
  elif ! jq -e --arg actor "$approval_actor" \
    '(.status == "in_progress" or .status == "completed") and
     .path == ".github/workflows/temporal-release-approve.yml" and
     ((.event == "issues" and .actor.login == $actor) or .event == "schedule")' \
    <<<"$approval_run" >/dev/null; then
    invalid_approval "the GitHub run is not the exact trusted approval delivery."
  else
    local issue actual_body_hash approval_request_receipt approval_request_key
    approval_request_key="sdk-java/$release_digest/state/approval-request.json"
    approval_request_receipt="$work/approval-request.json"
    aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$approval_request_key" \
      "$approval_request_receipt" --no-progress >/dev/null ||
      fail "the immutable approval-request receipt is temporarily unavailable."
    jq -e --arg repository "$repository" --arg digest "$release_digest" \
      --arg workflow "$workflow_id" --arg run "$run_id" \
      --argjson number "$approval_issue_number" --arg node "$approval_issue_node_id" \
      --arg body "$approval_issue_body_hash" --arg trusted "$trusted_commit" \
      '.repository == $repository and .releaseDigest == $digest and
       .workflowId == $workflow and .runId == $run and .issueNumber == $number and
       .issueNodeId == $node and .issueBodySha256 == $body and
       .trustedAutomationCommit == $trusted and
       (.issueCreator | type == "string" and length > 0)' \
      "$approval_request_receipt" >/dev/null ||
      invalid_approval "the immutable approval-request receipt differs."
    if ! issue=$(gh api "repos/temporalio/sdk-java/issues/$approval_issue_number"); then
      fail "the exact approval issue is temporarily unavailable."
    fi
    actual_body_hash=$(python3 -c 'import hashlib,json,sys; print(hashlib.sha256(json.load(sys.stdin)["body"].encode()).hexdigest())' <<<"$issue")
    issue_creator=$(jq -er .issueCreator "$approval_request_receipt")
    jq -e --arg actor "$approval_actor" --arg node "$approval_issue_node_id" \
      --arg creator "$issue_creator" \
      --argjson number "$approval_issue_number" \
      '.number == $number and .node_id == $node and .state == "closed" and .locked == true and
       .closed_by.login == $actor and .user.login == $creator' <<<"$issue" >/dev/null ||
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

configure_source_hooks() {
  [[ $source_hooks_configured == false ]] || return
  # Frozen trusted hooks provide releaseVersion/releaseCommit support to older maintenance SHAs.
  cp gradle/versioning.gradle "$work/versioning.gradle.original"
  cp gradle/publishing.gradle "$work/publishing.gradle.original"
  cp build.gradle "$work/build.gradle.original"
  cp "$TRUSTED_AUTOMATION_ROOT/gradle/versioning.gradle" gradle/versioning.gradle
  cp "$TRUSTED_AUTOMATION_ROOT/gradle/publishing.gradle" gradle/publishing.gradle
  python3 - build.gradle <<'PY' || conflict "the trusted Gradle hooks do not match fixed sdk-java policy."
import pathlib, re, sys
path = pathlib.Path(sys.argv[1])
source = path.read_text()
matches = list(re.finditer(r"id ['\"]io\.github\.gradle-nexus\.publish-plugin['\"] version ['\"][^'\"]+['\"]", source))
if len(matches) != 1:
    raise SystemExit("Expected exactly one Gradle Nexus publish plugin declaration")
source = source[:matches[0].start()] + "id 'io.github.gradle-nexus.publish-plugin' version '1.3.0'" + source[matches[0].end():]
path.write_text(source)
PY
  source_hooks_configured=true
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
) || conflict "$artifact published POM is malformed."
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

put_immutable_receipt() {
  local key=$1 receipt=$2 existing="$work/existing-$(basename "$receipt")"
  if ! aws s3api put-object --bucket "$RELEASE_ARTIFACT_BUCKET" --key "$key" \
    --body "$receipt" --if-none-match '*' >/dev/null 2>&1; then
    aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$key" "$existing" --no-progress >/dev/null ||
      fail "Unable to read immutable receipt $key."
    cmp -s "$receipt" "$existing" || conflict "immutable receipt $key differs."
  fi
}

stage_receipt_key() {
  if [[ $1 == maven ]]; then
    printf 'sdk-java/%s/state/stages/maven-%s.json' \
      "$release_digest" "$maven_submission_generation"
  else
    printf 'sdk-java/%s/state/stages/%s.json' "$release_digest" "$1"
  fi
}

read_stage_receipt() {
  local stage=$1 destination=$2 key
  key=$(stage_receipt_key "$stage")
  s3_object_exists "$key" ||
    conflict "the protected $stage completion receipt is absent."
  aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$key" "$destination" --no-progress >/dev/null ||
    fail "the protected $stage completion receipt is temporarily unavailable."
  jq -e --arg stage "$stage" --arg repository "$repository" --arg tag "$tag" \
    --arg commit "$commit" --arg candidate "$candidate_digest" \
    --arg release "$release_digest" --arg manifest "$manifest_hash" \
    '.stage == $stage and .repository == $repository and .tag == $tag and
     .commitSha == $commit and .candidateDigest == $candidate and
     .releaseDigest == $release and .manifestSha256 == $manifest and
     (.outputSha256 | test("^[0-9a-f]{64}$")) and
     (.predecessorSha256 == "" or
       (.predecessorSha256 | test("^[0-9a-f]{64}$")))' "$destination" >/dev/null ||
    conflict "the protected $stage completion receipt has another immutable identity."
  if [[ $stage != preflight ]]; then
    jq -e --argjson generation "$maven_submission_generation" \
      '.mavenSubmissionGeneration == $generation' "$destination" >/dev/null ||
      conflict "the protected $stage completion receipt has another Maven generation."
  fi
}

require_stage_receipt() {
  local stage=$1 receipt="$work/stage-$stage.json"
  read_stage_receipt "$stage" "$receipt"
  sha256sum "$receipt" | awk '{print $1}'
}

record_stage_receipt() {
  local stage=$1 predecessor=${2:-} predecessor_sha="" output_sha receipt key
  if [[ -n $predecessor ]]; then
    predecessor_sha=$(require_stage_receipt "$predecessor")
  fi
  if [[ -f $RELEASE_OUTPUT_FILE ]]; then
    output_sha=$(sha256sum "$RELEASE_OUTPUT_FILE" | awk '{print $1}')
  else
    output_sha=$(printf '' | sha256sum | awk '{print $1}')
  fi
  receipt="$work/stage-$stage-complete.json"
  jq -n --arg stage "$stage" --arg repository "$repository" --arg tag "$tag" \
    --arg commitSha "$commit" --arg candidateDigest "$candidate_digest" \
    --arg releaseDigest "$release_digest" --arg manifestSha256 "$manifest_hash" \
    --arg outputSha256 "$output_sha" \
    --arg predecessorSha256 "$predecessor_sha" \
    --argjson mavenSubmissionGeneration "$maven_submission_generation" \
    '{stage:$stage,repository:$repository,tag:$tag,commitSha:$commitSha,
      candidateDigest:$candidateDigest,releaseDigest:$releaseDigest,
      manifestSha256:$manifestSha256,
      mavenSubmissionGeneration:$mavenSubmissionGeneration,
      predecessorSha256:$predecessorSha256,outputSha256:$outputSha256}' >"$receipt"
  key=$(stage_receipt_key "$stage")
  put_immutable_receipt "$key" "$receipt"
}

sonatype_snapshot() {
  curl --silent --show-error --fail --user "$RH_USER:$RH_PASSWORD" \
    --header 'Accept: application/json' \
    'https://ossrh-staging-api.central.sonatype.com/service/local/staging/profile_repositories' \
    >"$work/profile-repositories.json" || fail "Unable to inspect Sonatype repositories."
  jq -e '((.data // .profileRepositories) | type == "array") and
    ((.data // .profileRepositories) | all(
      ((.repositoryId // .id) | type == "string" and length > 0) and
      (.description | type == "string")))' "$work/profile-repositories.json" >/dev/null ||
    fail "Sonatype returned an invalid profile-repository schema."
  portal_token=$(printf '%s:%s' "$RH_USER" "$RH_PASSWORD" | base64 | tr -d '\n')
  curl --silent --show-error --fail --header "Authorization: Bearer $portal_token" \
    --header 'Accept: application/json' \
    'https://ossrh-staging-api.central.sonatype.com/manual/search/repositories?ip=any&profile_id=io.temporal' \
    >"$work/manual-repositories.json" || fail "Unable to inspect Portal compatibility state."
  jq -e '(.repositories // []) | type == "array"' "$work/manual-repositories.json" >/dev/null ||
    fail "Sonatype returned invalid repository state."
}

validate_retry_authorization() {
  (( submission_generation > 0 )) || return
  local key="sdk-java/$release_digest/state/maven/retry-authorizations/$submission_generation.json"
  local receipt="$work/retry-authorization.json"
  aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$key" "$receipt" --no-progress >/dev/null ||
    conflict "the protected Maven retry authorization receipt is absent."
  expected_authorization=$(jq -er '.mavenRetryAuthorization.authorizationSha256' "$RELEASE_INPUT_FILE")
  [[ $(sha256sum "$receipt" | awk '{print $1}') == "$expected_authorization" ]] ||
    conflict "the Maven retry authorization checksum differs."
  jq -e --arg repository "$repository" --arg tag "$tag" --arg commit "$commit" \
    --arg digest "$release_digest" --arg workflow "$workflow_id" --arg run "$run_id" \
    --argjson generation "$submission_generation" \
    '.repository == $repository and .tag == $tag and .commitSha == $commit and
     .releaseDigest == $digest and .workflowId == $workflow and .runId == $run and
     .authorizedGeneration == $generation and .freshInspection == true and
     (.githubRunId | type == "number") and (.githubActor | type == "string")' \
    "$receipt" >/dev/null || conflict "the Maven retry authorization identity differs."
  jq -e --slurpfile receipt "$receipt" \
    '.mavenRetryAuthorization.action == "retry-maven-submission" and
     .mavenRetryAuthorization.mavenSubmissionGeneration == $receipt[0].authorizedGeneration and
     .mavenRetryAuthorization.githubRunId == $receipt[0].githubRunId and
     .mavenRetryAuthorization.githubActor == $receipt[0].githubActor' \
    "$RELEASE_INPUT_FILE" >/dev/null ||
    conflict "the Workflow retry Update is not bound to the protected authorization."
  retry_actor=$(jq -er .githubActor "$receipt")
  set +e
  "$TRUSTED_AUTOMATION_ROOT/.github/scripts/temporal-release/verify-approver.sh" \
    "$retry_actor" >/dev/null
  retry_actor_status=$?
  set -e
  [[ $retry_actor_status -eq 0 ]] || {
    [[ $retry_actor_status -eq 43 ]] &&
      invalid_approval "the Maven retry receipt actor is not an active release manager."
    fail "release-manager membership is temporarily unavailable for the Maven retry receipt."
  }
}

validate_prior_generations_inactive() {
  (( submission_generation > 0 )) || return
  local listing="$work/prior-generation-list.json" intent_key prior_generation prefix
  local description repository_id portal_id portal_state profile_count manual_count
  aws s3api list-objects-v2 --bucket "$RELEASE_ARTIFACT_BUCKET" \
    --prefix "sdk-java/$release_digest/state/maven/generations/" --output json >"$listing" ||
    fail "Unable to inspect earlier Maven submission generations."
  while IFS= read -r intent_key; do
    prior_generation=$(sed -n -E 's#^.*/generations/([0-9]+)/intent.json$#\1#p' \
      <<<"$intent_key")
    [[ $prior_generation =~ ^[0-9]+$ ]] ||
      conflict "an earlier Maven generation has an invalid durable key."
    (( prior_generation < submission_generation )) || continue
    prefix="sdk-java/$release_digest/state/maven/generations/$prior_generation"
    description="sdk-java:$release_digest:$prior_generation"
    repository_id=""
    if s3_object_exists "$prefix/repository.json"; then
      aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$prefix/repository.json" \
        "$work/prior-repository-$prior_generation.json" --no-progress >/dev/null ||
        fail "Unable to inspect the earlier Maven repository receipt."
      repository_id=$(jq -er --arg description "$description" \
        '.description == $description and .repositoryId' \
        "$work/prior-repository-$prior_generation.json") ||
        conflict "an earlier Maven repository receipt has another identity."
    else
      mapfile -t prior_matches < <(jq -r --arg description "$description" \
        '(.data // .profileRepositories // [])[] |
         select(.description == $description) | .repositoryId // .id' \
        "$work/profile-repositories.json")
      [[ ${#prior_matches[@]} -le 1 ]] ||
        conflict "multiple repositories match an earlier Maven generation."
      [[ ${#prior_matches[@]} -eq 0 ]] || repository_id=${prior_matches[0]}
    fi

    portal_id=""
    if s3_object_exists "$prefix/portal.json"; then
      aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$prefix/portal.json" \
        "$work/prior-portal-$prior_generation.json" --no-progress >/dev/null ||
        fail "Unable to inspect the earlier Portal receipt."
      portal_id=$(jq -er --arg repository "$repository_id" --argjson generation "$prior_generation" \
        '.generation == $generation and .repositoryId == $repository and .portalDeploymentId' \
        "$work/prior-portal-$prior_generation.json") ||
        conflict "an earlier Portal receipt has another identity."
      portal_state=$(portal_status "$portal_id")
      [[ $portal_state == FAILED ]] ||
        maven_ambiguous "earlier Maven generation $prior_generation has live Portal state $portal_state."
    fi

    if [[ -n $repository_id ]]; then
      profile_count=$(jq --arg id "$repository_id" \
        '[((.data // .profileRepositories // [])[]) |
          select((.repositoryId // .id) == $id)] | length' "$work/profile-repositories.json")
      manual_count=$(jq --arg id "$repository_id" \
        '[.repositories[] | select(.key == $id)] | length' "$work/manual-repositories.json")
      if (( profile_count > 0 || manual_count > 0 )); then
        maven_ambiguous "earlier Maven generation $prior_generation still has a live Sonatype repository."
      fi
    fi
  done < <(jq -r '.Contents // [] | .[].Key | select(endswith("/intent.json"))' "$listing")
}

prepare_maven_payload() {
  payload_receipt_key="sdk-java/$release_digest/state/maven/payload.json"
  payload_receipt="$work/maven-payload-receipt.json"
  payload_archive="$work/maven-payload.tar"
  payload_bundle="$work/maven-payload"
  payload_root="$payload_bundle/repository"
  payload_manifest="$payload_bundle/manifest.tsv"
  if s3_object_exists "$payload_receipt_key"; then
    aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$payload_receipt_key" \
      "$payload_receipt" --no-progress >/dev/null ||
      fail "Unable to read the frozen Maven payload receipt."
    payload_archive_key=$(jq -er --arg digest "$release_digest" \
      '.releaseDigest == $digest and
       (.archiveSha256 | test("^[0-9a-f]{64}$")) and
       (.manifestSha256 | test("^[0-9a-f]{64}$")) and .archiveKey' \
      "$payload_receipt") || conflict "the frozen Maven payload receipt differs."
    aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$payload_archive_key" \
      "$payload_archive" --no-progress >/dev/null ||
      fail "Unable to download the frozen Maven payload."
    expected_archive_sha=$(jq -er .archiveSha256 "$payload_receipt")
    [[ $(sha256sum "$payload_archive" | awk '{print $1}') == "$expected_archive_sha" ]] ||
      conflict "the frozen Maven payload archive checksum differs."
    while IFS= read -r archive_entry; do
      [[ $archive_entry == manifest.tsv || $archive_entry == repository ||
        $archive_entry == repository/ || $archive_entry == repository/io ||
        $archive_entry == repository/io/ || $archive_entry == repository/io/temporal ||
        $archive_entry == repository/io/temporal/ ||
        $archive_entry == repository/io/temporal/* ]] ||
        conflict "the frozen Maven payload archive contains an unexpected path."
      [[ $archive_entry != /* && $archive_entry != *../* ]] ||
        conflict "the frozen Maven payload archive contains an unsafe path."
    done < <(tar -tf "$payload_archive")
    mkdir -p "$payload_bundle"
    tar -xf "$payload_archive" -C "$payload_bundle"
    expected_manifest_sha=$(jq -er .manifestSha256 "$payload_receipt")
    [[ $(sha256sum "$payload_manifest" | awk '{print $1}') == "$expected_manifest_sha" ]] ||
      conflict "the frozen Maven payload manifest checksum differs."
  else
    (( submission_generation == 0 )) ||
      conflict "the release-wide Maven payload is absent during an authorized retry."
    configure_source_hooks
    generated="$work/generated-maven-local"
    mkdir -p "$generated" "$payload_root"
    MAVEN_PAYLOAD_COMMIT=$commit MAVEN_PAYLOAD_OUTPUT=$generated \
      MAVEN_PAYLOAD_VERSION=$version \
      "$TRUSTED_AUTOMATION_ROOT/.github/scripts/temporal-release/build-and-sign-maven-payload.sh"
    mkdir -p "$payload_root/io/temporal"
    for artifact in "${maven_artifacts[@]}"; do
      generated_artifact="$generated/io/temporal/$artifact/$version"
      [[ -d $generated_artifact ]] ||
        conflict "Gradle did not generate $artifact Maven payload."
      mkdir -p "$payload_root/io/temporal/$artifact"
      cp -R "$generated_artifact" "$payload_root/io/temporal/$artifact/$version"
    done
    : >"$payload_manifest"
    while IFS= read -r -d '' payload; do
      relative=${payload#"$payload_root/"}
      printf '%s\t%s\t%s\n' "$relative" \
        "$(sha256sum "$payload" | awk '{print $1}')" \
        "$(wc -c <"$payload" | tr -d ' ')" >>"$payload_manifest"
    done < <(find "$payload_root/io/temporal" -type f -print0 | sort -z)
  fi
  printf '%s\n' "${maven_artifacts[@]}" >"$work/approved-maven-artifacts.txt"
  python3 - "$payload_root" "$payload_manifest" "$work/approved-maven-artifacts.txt" \
    "$version" "$commit" <<'PY' || conflict "the frozen Maven payload violates fixed sdk-java policy."
import hashlib, pathlib, re, sys, xml.etree.ElementTree as ET
root = pathlib.Path(sys.argv[1]).resolve()
manifest = pathlib.Path(sys.argv[2])
approved = set(pathlib.Path(sys.argv[3]).read_text().splitlines())
version, commit = sys.argv[4:]
records = []
for line in manifest.read_text().splitlines():
    relative, sha, size = line.split("\t")
    parts = pathlib.PurePosixPath(relative).parts
    if len(parts) != 5 or parts[:2] != ("io", "temporal"):
        raise SystemExit("payload path is outside fixed Maven coordinates")
    artifact, found_version, filename = parts[2:]
    if artifact not in approved or found_version != version:
        raise SystemExit("payload contains an unapproved Maven coordinate")
    escaped = re.escape(f"{artifact}-{version}")
    if not re.fullmatch(escaped + r"(?:-(?:sources|javadoc))?\.(?:jar|pom|module)(?:\.(?:asc|md5|sha1))?", filename):
        raise SystemExit("payload contains an unapproved Maven filename")
    path = (root / relative).resolve()
    if root not in path.parents or not path.is_file() or path.is_symlink():
        raise SystemExit("payload path is not a regular file")
    data = path.read_bytes()
    if hashlib.sha256(data).hexdigest() != sha or len(data) != int(size):
        raise SystemExit("payload manifest checksum or size differs")
    records.append(relative)
if records != sorted(set(records)) or not records:
    raise SystemExit("payload manifest is empty, duplicated, or unsorted")
actual = sorted(str(path.relative_to(root)).replace("\\", "/")
                for path in (root / "io" / "temporal").rglob("*") if path.is_file())
if actual != records:
    raise SystemExit("payload archive and manifest contain different file sets")
for artifact in approved:
    directory = root / "io" / "temporal" / artifact / version
    pom = directory / f"{artifact}-{version}.pom"
    signature = directory / f"{artifact}-{version}.pom.asc"
    bases = {f"{artifact}-{version}.pom", f"{artifact}-{version}.module"}
    if artifact != "temporal-bom":
        bases.update({
            f"{artifact}-{version}.jar",
            f"{artifact}-{version}-sources.jar",
            f"{artifact}-{version}-javadoc.jar",
        })
    expected_names = set(bases)
    for base in bases:
        expected_names.update({base + ".asc", base + ".md5", base + ".sha1"})
    actual_names = {path.name for path in directory.iterdir() if path.is_file()}
    if actual_names != expected_names:
        raise SystemExit(f"Maven payload file set differs for {artifact}")
    if not pom.is_file() or not signature.is_file():
        raise SystemExit(f"missing signed POM for {artifact}")
    document = ET.parse(pom).getroot()
    ns = document.tag.partition("}")[0] + "}" if document.tag.startswith("{") else ""
    values = (document.findtext(f"{ns}groupId", "").strip(),
              document.findtext(f"{ns}artifactId", "").strip(),
              document.findtext(f"{ns}version", "").strip(),
              document.findtext(f"{ns}scm/{ns}tag", "").strip().lower())
    if values != ("io.temporal", artifact, version, commit):
        raise SystemExit(f"generated POM identity differs for {artifact}")
PY
  if [[ ! -f $payload_receipt ]]; then
    tar --sort=name --mtime='UTC 1970-01-01' --owner=0 --group=0 --numeric-owner \
      -cf "$payload_archive" -C "$payload_bundle" manifest.tsv repository
    archive_sha=$(sha256sum "$payload_archive" | awk '{print $1}')
    manifest_sha=$(sha256sum "$payload_manifest" | awk '{print $1}')
    payload_archive_key="sdk-java/$release_digest/state/maven/payloads/$archive_sha.tar"
    if ! aws s3api put-object --bucket "$RELEASE_ARTIFACT_BUCKET" \
      --key "$payload_archive_key" --body "$payload_archive" --if-none-match '*' \
      >/dev/null 2>&1; then
      aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$payload_archive_key" \
        "$work/existing-maven-payload.tar" --no-progress >/dev/null
      cmp -s "$payload_archive" "$work/existing-maven-payload.tar" ||
        conflict "the content-addressed Maven payload differs."
    fi
    jq -n --arg repository "$repository" --arg tag "$tag" --arg commitSha "$commit" \
      --arg version "$version" --arg releaseDigest "$release_digest" \
      --arg archiveKey "$payload_archive_key" --arg archiveSha256 "$archive_sha" \
      --arg manifestSha256 "$manifest_sha" \
      '{repository:$repository,tag:$tag,commitSha:$commitSha,version:$version,
        releaseDigest:$releaseDigest,archiveKey:$archiveKey,
        archiveSha256:$archiveSha256,manifestSha256:$manifestSha256}' >"$payload_receipt"
    put_immutable_receipt "$payload_receipt_key" "$payload_receipt"
  fi
}

inspect_staging_payload() {
  remote_missing="$work/remote-missing.tsv"
  : >"$remote_missing"
  while IFS=$'\t' read -r relative sha size; do
    remote="$work/remote-$(printf '%s' "$relative" | sha256sum | awk '{print $1}')"
    status=$(curl --silent --show-error --location --output "$remote" --write-out '%{http_code}' \
      --user "$RH_USER:$RH_PASSWORD" \
      "https://ossrh-staging-api.central.sonatype.com/service/local/repositories/$repository_id/content/$relative") ||
      fail "Unable to inspect staged Maven file $relative."
    case "$status" in
      200)
        [[ $(sha256sum "$remote" | awk '{print $1}') == "$sha" &&
          $(wc -c <"$remote" | tr -d ' ') == "$size" ]] ||
          conflict "staged Maven file $relative has different bytes."
        ;;
      404) printf '%s\t%s\t%s\n' "$relative" "$sha" "$size" >>"$remote_missing" ;;
      *) fail "Sonatype returned HTTP $status for staged Maven file $relative." ;;
    esac
  done <"$payload_manifest"
}

verify_staging_file_set() {
  : >"$work/staging-file-set.txt"
  : >"$work/staging-directories.txt"
  printf '\n' >>"$work/staging-directories.txt"
  while IFS= read -r relative_directory; do
    directory_hash=$(printf '%s' "$relative_directory" | sha256sum | awk '{print $1}')
    listing="$work/staging-list-$directory_hash.json"
    suffix="${relative_directory:+$relative_directory/}"
    curl --silent --show-error --fail --user "$RH_USER:$RH_PASSWORD" \
      --header 'Accept: application/json' \
      "https://ossrh-staging-api.central.sonatype.com/service/local/repositories/$repository_id/content/$suffix" \
      >"$listing" || fail "Unable to enumerate the complete staged Maven repository."
    jq -e '(.data | type == "array") and
      (.data | all((.leaf | type == "boolean") and (.relativePath | type == "string")))' \
      "$listing" >/dev/null || fail "Sonatype returned an invalid staged content listing."
    jq -r --arg directory "$relative_directory" \
      '.data[] | select(.leaf == true) | .relativePath |
       ltrimstr("/") | if contains("/") then . else ($directory + "/" + .) end' \
      "$listing" | sed 's#^/##; s#^/##' >>"$work/staging-file-set.txt"
    while IFS= read -r child; do
      child=${child#/}
      [[ $child == */* || -z $relative_directory ]] || child="$relative_directory/$child"
      grep -Fxq "$child" "$work/staging-directories.txt" ||
        printf '%s\n' "$child" >>"$work/staging-directories.txt"
    done < <(jq -r '.data[] | select(.leaf == false) | .relativePath' "$listing")
  done <"$work/staging-directories.txt"
  cut -f1 "$payload_manifest" | sort >"$work/expected-staging-file-set.txt"
  sort -u "$work/staging-file-set.txt" -o "$work/staging-file-set.txt"
  cmp -s "$work/expected-staging-file-set.txt" "$work/staging-file-set.txt" ||
    conflict "the staged Maven repository contains missing or unexpected paths."
}

upload_missing_payload() {
  while IFS=$'\t' read -r relative _ _; do
    [[ -n $relative ]] || continue
    curl --silent --show-error --fail --user "$RH_USER:$RH_PASSWORD" \
      --upload-file "$payload_root/$relative" \
      "https://ossrh-staging-api.central.sonatype.com/service/local/staging/deployByRepositoryId/$repository_id/$relative" \
      >/dev/null || fail "Unable to upload staged Maven file $relative."
  done <"$remote_missing"
}

validate_central_payload() {
  while IFS=$'\t' read -r relative sha size; do
    central_payload="$work/central-$(printf '%s' "$relative" | sha256sum | awk '{print $1}')"
    central_payload_status=$(curl --silent --show-error --location --output "$central_payload" \
      --write-out '%{http_code}' "$central_base/$relative") ||
      fail "Unable to inspect Maven Central payload $relative."
    [[ $central_payload_status == 200 ]] ||
      fail "Maven Central payload $relative returned HTTP $central_payload_status."
    [[ $(sha256sum "$central_payload" | awk '{print $1}') == "$sha" &&
      $(wc -c <"$central_payload" | tr -d ' ') == "$size" ]] ||
      conflict "Maven Central payload $relative differs from the frozen signed payload."
  done <"$payload_manifest"
  verify_central_file_sets
}

verify_central_file_sets() {
  while IFS= read -r artifact; do
    directory="io/temporal/$artifact/$version"
    curl --silent --show-error --fail --location "$central_base/$directory/" \
      >"$work/central-index-$artifact.html" ||
      fail "Unable to enumerate Maven Central files for $artifact."
    awk -F'"' '/href="[^"]+"/ {for (i=2; i<=NF; i+=2) print $i}' \
      "$work/central-index-$artifact.html" |
      sed -E 's/[?#].*$//' | awk -F/ 'NF == 1 && $0 != "" && $0 != ".." {print}' |
      sort -u >"$work/central-files-$artifact.txt"
    awk -F'\t' -v prefix="$directory/" '$1 ~ ("^" prefix) {sub("^.*/", "", $1); print $1}' \
      "$payload_manifest" | sort >"$work/central-required-$artifact.txt"
    python3 - "$work/central-required-$artifact.txt" \
      "$work/central-files-$artifact.txt" <<'PY' || conflict "Maven Central has a conflicting file set for $artifact."
import pathlib, sys
required = set(pathlib.Path(sys.argv[1]).read_text().splitlines())
actual = set(pathlib.Path(sys.argv[2]).read_text().splitlines())
if not required <= actual:
    raise SystemExit("Maven Central directory is missing frozen payload paths")
allowed = set(required)
for name in required:
    allowed.update(name + suffix for suffix in (".md5", ".sha1", ".sha256", ".sha512"))
if not actual <= allowed:
    raise SystemExit("Maven Central directory contains unexpected release paths")
PY
  done <"$work/approved-maven-artifacts.txt"
}

verify_published_maven_payload() {
  submission_generation=$maven_submission_generation
  generation_prefix="sdk-java/$release_digest/state/maven/generations/$submission_generation"
  central_state
  [[ $missing -eq 0 && $present -eq ${#maven_artifacts[@]} ]] ||
    fail "The complete Maven release is not visible immediately before GitHub publication."
  s3_object_exists "sdk-java/$release_digest/state/maven/payload.json" ||
    conflict "the release-wide frozen Maven payload receipt is absent."
  prepare_maven_payload
  validate_central_payload
}

portal_status() {
  local deployment_id=$1
  curl --silent --show-error --fail --request POST \
    --header "Authorization: Bearer $portal_token" --header 'Accept: application/json' \
    "https://central.sonatype.com/api/v1/publisher/status?id=$deployment_id" \
    >"$work/portal-status.json" || fail "Unable to inspect exact Portal deployment."
  jq -er --arg id "$deployment_id" 'select(.deploymentId == $id) | .deploymentState' \
    "$work/portal-status.json"
}

reconcile_maven() {
  central_state
  submission_generation=$(jq -er '.mavenSubmissionGeneration' "$RELEASE_INPUT_FILE")
  [[ $submission_generation =~ ^[0-9]+$ ]] || conflict "invalid Maven submission generation."
  generation_prefix="sdk-java/$release_digest/state/maven/generations/$submission_generation"
  staging_description="sdk-java:$release_digest:$submission_generation"
  if [[ $present -eq ${#maven_artifacts[@]} ]]; then
    prepare_maven_payload
    validate_central_payload
    repository_id=""
    portal_deployment_id=""
    aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$generation_prefix/repository.json" \
      "$work/completed-repository.json" --no-progress >/dev/null ||
      conflict "exact Central coordinates have no immutable repository receipt."
    repository_id=$(jq -er --arg description "$staging_description" \
      '.description == $description and .repositoryId' "$work/completed-repository.json") ||
      conflict "the completed Maven repository receipt differs."
    aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$generation_prefix/portal.json" \
      "$work/completed-portal.json" --no-progress >/dev/null ||
      conflict "exact Central coordinates have no immutable Portal receipt."
    portal_deployment_id=$(jq -er --arg repository "$repository_id" \
      '.repositoryId == $repository and .portalDeploymentId' "$work/completed-portal.json") ||
      conflict "the completed Maven Portal receipt differs."
    sonatype_snapshot
    completed_portal_state=$(portal_status "$portal_deployment_id")
    case "$completed_portal_state" in
      PUBLISHED) ;;
      PENDING | VALIDATING | VALIDATED | PUBLISHING)
        fail "Exact Portal deployment is $completed_portal_state; Temporal will reconcile again."
        ;;
      FAILED) conflict "the receipted Portal deployment failed after Central publication." ;;
      *) conflict "Portal returned unsupported deployment state $completed_portal_state." ;;
    esac
    jq -n --arg mavenCentralUrl \
      "https://central.sonatype.com/artifact/io.temporal/temporal-sdk/$version" \
      --arg sonatypeRepositoryId "$repository_id" \
      --arg portalDeploymentId "$portal_deployment_id" \
      '{mavenCentralUrl:$mavenCentralUrl,sonatypeRepositoryId:$sonatypeRepositoryId,
        portalDeploymentId:$portalDeploymentId}' \
      >"$RELEASE_OUTPUT_FILE"
    return
  fi
  [[ $present -eq 0 ]] || fail "Maven publication is partially visible; Temporal will retry."

  # Isolated payload construction and authoritative remote reads happen before mutation intent.
  verify_source_maven_policy
  sonatype_snapshot
  validate_retry_authorization
  validate_prior_generations_inactive
  prepare_maven_payload

  intent="$work/maven-intent.json"
  jq -n --arg tag "$tag" --arg commitSha "$commit" --arg releaseDigest "$release_digest" \
    --arg description "$staging_description" --argjson generation "$submission_generation" \
    '{tag:$tag,commitSha:$commitSha,releaseDigest:$releaseDigest,
      description:$description,generation:$generation}' >"$intent"
  intent_key="$generation_prefix/intent.json"
  new_intent=false
  if ! s3_object_exists "$intent_key"; then
    put_immutable_receipt "$intent_key" "$intent"
    new_intent=true
  else
    aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$intent_key" "$work/existing-intent.json" \
      --no-progress >/dev/null || fail "Unable to read the Maven generation intent."
    cmp -s "$intent" "$work/existing-intent.json" || conflict "the Maven generation intent differs."
  fi

  repository_receipt="$work/maven-repository.json"
  repository_key="$generation_prefix/repository.json"
  repository_id=""
  if s3_object_exists "$repository_key"; then
    aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$repository_key" "$repository_receipt" \
      --no-progress >/dev/null || fail "Unable to read the repository receipt."
    repository_id=$(jq -er --arg description "$staging_description" \
      '.description == $description and .repositoryId' "$repository_receipt") ||
      conflict "the Maven repository receipt differs."
  else
    mapfile -t matches < <(jq -r --arg description "$staging_description" \
      '(.data // .profileRepositories // [])[] |
       select(.description == $description) | .repositoryId // .id' \
      "$work/profile-repositories.json")
    [[ ${#matches[@]} -le 1 ]] ||
      conflict "multiple Sonatype repositories match the immutable generation."
    if [[ ${#matches[@]} -eq 1 ]]; then
      repository_id=${matches[0]}
    elif [[ $new_intent == true ]]; then
      repository_id=$(SONATYPE_REPOSITORY_DESCRIPTION=$staging_description \
        "$TRUSTED_AUTOMATION_ROOT/.github/scripts/temporal-release/create-sonatype-repository.sh")
      [[ -n $repository_id ]] || fail "Unable to capture the created Sonatype repository ID."
    else
      maven_ambiguous "intent exists but its exact repository is absent."
    fi
    jq -n --arg description "$staging_description" --arg repositoryId "$repository_id" \
      --argjson generation "$submission_generation" \
      '{generation:$generation,description:$description,repositoryId:$repositoryId}' \
      >"$repository_receipt"
    put_immutable_receipt "$repository_key" "$repository_receipt"
  fi

  # A persisted ID is authoritative; descriptions may disappear after the compatibility close.
  profile_description=$(jq -r --arg id "$repository_id" \
    '[((.data // .profileRepositories // [])[]) |
      select((.repositoryId // .id) == $id) | .description] | first // ""' \
    "$work/profile-repositories.json")
  [[ -z $profile_description || $profile_description == "$staging_description" ]] ||
    conflict "the persisted Sonatype repository ID has another description."
  manual_repository_count=$(jq --arg id "$repository_id" \
    '[.repositories[] | select(.key == $id)] | length' "$work/manual-repositories.json")
  receipted_portal_deployment_id=""
  if s3_object_exists "$generation_prefix/portal.json"; then
    aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$generation_prefix/portal.json" \
      "$work/existing-portal.json" --no-progress >/dev/null ||
      fail "Unable to read the existing Portal receipt."
    receipted_portal_deployment_id=$(jq -er --arg repository "$repository_id" \
      --argjson generation "$submission_generation" \
      '.generation == $generation and .repositoryId == $repository and .portalDeploymentId' \
      "$work/existing-portal.json") || conflict "the existing Portal receipt differs."
  fi
  if [[ $manual_repository_count -eq 0 ]]; then
    [[ -n $profile_description || -n $receipted_portal_deployment_id ]] ||
      maven_ambiguous "the receipted repository is absent from both Sonatype state APIs."
    [[ -n $receipted_portal_deployment_id ]] && repository_state=closed || repository_state=open
  else
    repository_state=$(jq -r --arg id "$repository_id" \
      '[.repositories[] | select(.key == $id) | .state] | first' \
      "$work/manual-repositories.json")
  fi
  portal_deployment_id=$(jq -r --arg id "$repository_id" \
    '[.repositories[] | select(.key == $id) | .portal_deployment_id] | first // ""' \
    "$work/manual-repositories.json")
  if [[ -n $receipted_portal_deployment_id ]]; then
    [[ -z $portal_deployment_id ||
      $portal_deployment_id == "$receipted_portal_deployment_id" ]] ||
      conflict "the Portal API and immutable Portal receipt disagree."
    portal_deployment_id=$receipted_portal_deployment_id
  fi

  if [[ $repository_state == open ]]; then
    inspect_staging_payload
    upload_missing_payload
    inspect_staging_payload
    [[ ! -s $remote_missing ]] || fail "The exact staged Maven payload is not complete yet."
    verify_staging_file_set
    close_body="$work/close-repository.json"
    jq -n --arg id "$repository_id" --arg description "$staging_description" \
      '{data:{stagedRepositoryIds:[$id],description:$description}}' >"$close_body"
    close_status=$(curl --silent --show-error --output "$work/close-response" \
      --write-out '%{http_code}' --request POST --user "$RH_USER:$RH_PASSWORD" \
      --header 'Content-Type: application/json' --data-binary "@$close_body" \
      'https://ossrh-staging-api.central.sonatype.com/service/local/staging/bulk/close') ||
      fail "Unable to close the exact Sonatype repository."
    case "$close_status" in 200 | 201 | 202 | 204) ;; *)
      fail "Sonatype returned HTTP $close_status while closing the exact repository." ;;
    esac
    fail "Sonatype accepted the exact repository close; Temporal will observe its Portal deployment."
  fi
  [[ $repository_state == closed || $repository_state == released ]] ||
    conflict "Sonatype returned unsupported repository state $repository_state."
  [[ $portal_deployment_id =~ ^[0-9a-fA-F-]{16,64}$ ]] ||
    fail "The closed repository has no Portal deployment ID yet."
  portal_receipt="$work/maven-portal.json"
  jq -n --arg repositoryId "$repository_id" --arg portalDeploymentId "$portal_deployment_id" \
    --argjson generation "$submission_generation" \
    '{generation:$generation,repositoryId:$repositoryId,
      portalDeploymentId:$portalDeploymentId}' >"$portal_receipt"
  put_immutable_receipt "$generation_prefix/portal.json" "$portal_receipt"
  deployment_state=$(portal_status "$portal_deployment_id")
  case "$deployment_state" in
    VALIDATED)
      publish_status=$(curl --silent --show-error --output "$work/portal-publish-response" \
        --write-out '%{http_code}' --request POST \
        --header "Authorization: Bearer $portal_token" \
        "https://central.sonatype.com/api/v1/publisher/deployment/$portal_deployment_id") ||
        fail "Unable to publish the exact validated Portal deployment."
      [[ $publish_status == 204 ]] ||
        fail "Portal returned HTTP $publish_status while publishing the exact deployment."
      fail "Portal accepted the exact deployment publication; Temporal will poll its state."
      ;;
    PENDING | VALIDATING | PUBLISHING | PUBLISHED)
      fail "Exact Portal deployment is $deployment_state; Temporal will reconcile again."
      ;;
    FAILED)
      echo "reconcile-publication: exact Portal deployment failed validation." >&2
      exit 45
      ;;
    *) conflict "Portal returned unsupported deployment state $deployment_state." ;;
  esac
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

verify_github_preflight() {
  local existing_tag="$work/preflight-tag.json" existing_release draft asset_id asset_name
  local asset_state asset_size
  if github_optional_get "repos/temporalio/sdk-java/git/ref/tags/$tag" "$existing_tag"; then
    [[ $(jq -r '.object.type' "$existing_tag") == commit &&
      $(jq -r '.object.sha' "$existing_tag") == "$commit" ]] ||
      conflict "the existing Git tag points at another object."
  fi
  existing_release=$(release_json)
  [[ -n $existing_release ]] || return
  draft=$(jq -r .draft <<<"$existing_release")
  [[ $draft == true || $draft == false ]] || conflict "GitHub returned invalid draft state."
  verify_release_metadata "$existing_release" "$draft"
  mapfile -t preflight_expected_assets < <(
    find "$work/assets" -mindepth 1 -maxdepth 1 -type f -exec basename {} \; | sort
  )
  while IFS=$'\t' read -r asset_id asset_name asset_state asset_size; do
    [[ -n $asset_id ]] || continue
    printf '%s\n' "${preflight_expected_assets[@]}" | grep -Fxq "$asset_name" ||
      conflict "the existing GitHub release has unexpected asset $asset_name."
    if [[ $draft == true && $asset_state == starter && $asset_size == 0 ]]; then
      continue
    fi
    gh release download "$tag" --repo temporalio/sdk-java --pattern "$asset_name" \
      --dir "$work/existing" --clobber >/dev/null ||
      fail "Unable to inspect existing GitHub asset $asset_name."
    cmp -s "$work/existing/$asset_name" "$work/assets/$asset_name" ||
      conflict "the existing GitHub asset $asset_name differs from the approved manifest."
  done < <(jq -r '.assets[] | [.id,.name,.state,.size] | @tsv' <<<"$existing_release")
  if [[ $draft == false && $(jq '.assets | length' <<<"$existing_release") -ne 7 ]]; then
    conflict "the existing public GitHub release is incomplete."
  fi
}

verify_exact_github_assets() {
  local release=$1 name state size expected_size
  mapfile -t verified_expected_assets < <(
    find "$work/assets" -mindepth 1 -maxdepth 1 -type f -exec basename {} \; | sort
  )
  mapfile -t verified_remote_assets < <(jq -r '.assets[].name' <<<"$release" | sort)
  [[ ${verified_remote_assets[*]} == "${verified_expected_assets[*]}" ]] ||
    fail "The exact GitHub asset set is not fully visible yet."
  while IFS=$'\t' read -r name state size; do
    [[ $state == uploaded ]] || conflict "GitHub asset $name has unsupported state $state."
    expected_size=$(wc -c <"$work/assets/$name" | tr -d ' ')
    [[ $size == "$expected_size" ]] || conflict "GitHub asset $name has the wrong size."
    gh release download "$tag" --repo temporalio/sdk-java --pattern "$name" \
      --dir "$work/existing" --clobber >/dev/null ||
      fail "Unable to download GitHub asset $name for exact verification."
    cmp -s "$work/existing/$name" "$work/assets/$name" ||
      conflict "GitHub asset $name differs from the approved manifest."
  done < <(jq -r '.assets[] | [.name,.state,.size] | @tsv' <<<"$release")
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
  while IFS=$'\t' read -r asset_id asset_name asset_state asset_size; do
    [[ -n $asset_id ]] || continue
    if [[ $asset_state == starter && $asset_size == 0 && $draft == true ]]; then
      gh api --method DELETE "repos/temporalio/sdk-java/releases/assets/$asset_id" >/dev/null ||
        fail "Unable to remove interrupted starter asset $asset_name."
    elif [[ $asset_state != uploaded ]]; then
      conflict "GitHub asset $asset_name has unsupported state $asset_state."
    fi
  done < <(jq -r '.assets[] | [.id,.name,.state,.size] | @tsv' <<<"$release")
  release=$(release_json)
  mapfile -t remote_assets < <(jq -r '.assets[].name' <<<"$release" | sort)
  for name in "${expected_assets[@]}"; do
    if printf '%s\n' "${remote_assets[@]}" | grep -Fxq "$name"; then
      gh release download "$tag" --repo temporalio/sdk-java --pattern "$name" \
        --dir "$work/existing" --clobber >/dev/null
      expected_asset_sha=$(sha256sum "$work/assets/$name" | awk '{print $1}')
      [[ $(sha256sum "$work/existing/$name" | awk '{print $1}') == "$expected_asset_sha" ]] ||
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
  verify_exact_github_assets "$release"
  jq -n --arg value "$(jq -er '.html_url' <<<"$release")" '$value' >"$RELEASE_OUTPUT_FILE"
}

publish_github_release() {
  # The durable stage receipt is only routing evidence. Reconcile every Central byte again here.
  verify_published_maven_payload
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
  verify_exact_github_assets "$release"
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

  sonatype_state=not-submitted
  sonatype_repository_id=""
  portal_deployment_id=""
  portal_deployment_state=""
  current_generation=$(jq -er '.mavenSubmissionGeneration' "$RELEASE_INPUT_FILE")
  intent_generation=-1
  highest_generation=$current_generation
  : >"$work/maven-generations.jsonl"
  sonatype_snapshot
  aws s3api list-objects-v2 --bucket "$RELEASE_ARTIFACT_BUCKET" \
    --prefix "sdk-java/$release_digest/state/maven/generations/" --output json \
    >"$work/generation-list.json" || fail "Unable to list durable Maven generation receipts."
  mapfile -t intent_keys < <(jq -r '.Contents // [] | .[].Key | select(endswith("/intent.json"))' \
    "$work/generation-list.json" | sort)
  for intent_key in "${intent_keys[@]}"; do
    generation=$(sed -n -E 's#^.*/generations/([0-9]+)/intent.json$#\1#p' <<<"$intent_key")
    [[ $generation =~ ^[0-9]+$ ]] ||
      conflict "a durable Maven generation has an invalid number."
    (( generation > highest_generation )) && highest_generation=$generation
    generation_prefix="sdk-java/$release_digest/state/maven/generations/$generation"
    intent="$work/inspection-intent-$generation.json"
    aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$intent_key" "$intent" --no-progress >/dev/null ||
      fail "Unable to inspect Maven generation $generation."
    description="sdk-java:$release_digest:$generation"
    jq -e --arg tag "$tag" --arg commit "$commit" --arg digest "$release_digest" \
      --arg description "$description" --argjson generation "$generation" \
      '.tag == $tag and .commitSha == $commit and .releaseDigest == $digest and
       .generation == $generation and .description == $description' "$intent" >/dev/null ||
      conflict "Maven generation $generation has another immutable identity."
    repository_id=""
    repository_state=absent
    deployment_id=""
    deployment_state=""
    if s3_object_exists "$generation_prefix/repository.json"; then
      aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$generation_prefix/repository.json" \
        "$work/inspection-repository-$generation.json" --no-progress >/dev/null
      repository_id=$(jq -er --arg description "$description" \
        '.description == $description and .repositoryId' \
        "$work/inspection-repository-$generation.json") ||
        conflict "Maven generation $generation repository receipt differs."
      repository_state=$(jq -r --arg id "$repository_id" \
        '[.repositories[] | select(.key == $id) | .state] | first // empty' \
        "$work/manual-repositories.json")
      if [[ -z $repository_state ]]; then
        repository_state=$(jq -r --arg id "$repository_id" \
          '[((.data // .profileRepositories // [])[]) |
            select((.repositoryId // .id) == $id) | (.type // .state // "open")] |
           first // "unavailable"' "$work/profile-repositories.json")
      fi
    else
      mapfile -t inspection_matches < <(jq -r --arg description "$description" \
        '(.data // .profileRepositories // [])[] |
         select(.description == $description) | .repositoryId // .id' \
        "$work/profile-repositories.json")
      [[ ${#inspection_matches[@]} -le 1 ]] ||
        conflict "multiple Sonatype repositories match Maven generation $generation."
      if [[ ${#inspection_matches[@]} -eq 1 ]]; then
        repository_id=${inspection_matches[0]}
        repository_state=unreceipted
      fi
    fi
    if s3_object_exists "$generation_prefix/portal.json"; then
      aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$generation_prefix/portal.json" \
        "$work/inspection-portal-$generation.json" --no-progress >/dev/null
      deployment_id=$(jq -er --arg repository "$repository_id" \
        '.repositoryId == $repository and .portalDeploymentId' \
        "$work/inspection-portal-$generation.json") ||
        conflict "Maven generation $generation Portal receipt differs."
      deployment_state=$(portal_status "$deployment_id")
    fi
    jq -cn --argjson generation "$generation" --arg repositoryId "$repository_id" \
      --arg repositoryState "$repository_state" --arg portalDeploymentId "$deployment_id" \
      --arg portalDeploymentState "$deployment_state" \
      '{generation:$generation,repositoryId:$repositoryId,repositoryState:$repositoryState,
        portalDeploymentId:$portalDeploymentId,portalDeploymentState:$portalDeploymentState}' \
      >>"$work/maven-generations.jsonl"
    if (( generation == highest_generation )); then
      intent_generation=$generation
      sonatype_repository_id=$repository_id
      portal_deployment_id=$deployment_id
      portal_deployment_state=$deployment_state
      if [[ -n $repository_id ]]; then sonatype_state=exact-repository-found
      else sonatype_state=exact-repository-absent; fi
    fi
  done
  maven_generations=$(jq -sc '.' "$work/maven-generations.jsonl")

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
    release_state=$([[ $draft == true ]] && echo draft-empty || echo public-incomplete)
    mapfile -t expected_assets < <(find "$work/assets" -mindepth 1 -maxdepth 1 -type f -exec basename {} \; | sort)
    mapfile -t remote_assets < <(jq -r '.assets[].name' <<<"$release" | sort)
    while IFS=$'\t' read -r remote_name remote_state remote_size; do
      printf '%s\n' "${expected_assets[@]}" | grep -Fxq "$remote_name" ||
        conflict "the GitHub release has unexpected asset $remote_name."
      if [[ $draft == true && $remote_state == starter && $remote_size == 0 ]]; then
        continue
      fi
      gh release download "$tag" --repo temporalio/sdk-java --pattern "$remote_name" \
        --dir "$work/existing" --clobber >/dev/null
      expected_asset_sha=$(sha256sum "$work/assets/$remote_name" | awk '{print $1}')
      [[ $(sha256sum "$work/existing/$remote_name" | awk '{print $1}') == "$expected_asset_sha" ]] ||
        conflict "existing GitHub asset $remote_name has different bytes."
    done < <(jq -r '.assets[] | [.name,.state,.size] | @tsv' <<<"$release")
    all_assets_uploaded=$(jq -r '[.assets[] | .state == "uploaded"] | all' <<<"$release")
    if [[ ${remote_assets[*]} == "${expected_assets[*]}" && $all_assets_uploaded == true ]]; then
      release_state=$([[ $draft == true ]] && echo exact-draft || echo exact-public)
    elif [[ ${#remote_assets[@]} -gt 0 ]]; then
      release_state=$([[ $draft == true ]] && echo draft-partial || echo public-incomplete)
    fi
  fi

  jq -n --argjson mavenPresent "$present" --argjson mavenMissing "$missing" \
    --arg sonatype "$sonatype_state" --arg sonatypeRepositoryId "$sonatype_repository_id" \
    --arg portalDeploymentId "$portal_deployment_id" \
    --arg portalDeploymentState "$portal_deployment_state" \
    --argjson mavenGenerations "$maven_generations" \
    --argjson mavenSubmissionGeneration \
      "$highest_generation" \
    --argjson mavenIntentGeneration "${intent_generation:--1}" \
    --arg ownership "$ownership_state" --arg tag "$tag_state" --arg release "$release_state" \
    '{mavenPresent:$mavenPresent,mavenMissing:$mavenMissing,sonatype:$sonatype,
      sonatypeRepositoryId:$sonatypeRepositoryId,
      portalDeploymentId:$portalDeploymentId,
      portalDeploymentState:$portalDeploymentState,mavenGenerations:$mavenGenerations,
      mavenSubmissionGeneration:$mavenSubmissionGeneration,
      mavenIntentGeneration:$mavenIntentGeneration,ownership:$ownership,
      tag:$tag,release:$release}' \
    >"$RELEASE_OUTPUT_FILE"
}

verify_candidate_receipts
verify_approval
case "$RELEASE_STAGE" in
  inspect) inspect_external_state ;;
  handoff) claim_manual_ownership ;;
  preflight)
    ensure_ownership
    materialize_assets
    verify_github_preflight
    record_stage_receipt preflight
    ;;
  maven)
    ensure_ownership
    require_stage_receipt preflight >/dev/null
    reconcile_maven
    record_stage_receipt maven preflight
    ;;
  github-draft)
    ensure_ownership
    require_stage_receipt maven >/dev/null
    reconcile_github_draft
    record_stage_receipt github-draft maven
    ;;
  github-publish)
    ensure_ownership
    require_stage_receipt github-draft >/dev/null
    publish_github_release
    record_stage_receipt github-publish github-draft
    ;;
  *) fail "Temporal scheduled an unknown publication stage." ;;
esac
