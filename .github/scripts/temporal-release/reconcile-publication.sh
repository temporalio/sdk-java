#!/usr/bin/env bash

set -euo pipefail

fail() { echo "reconcile-publication: $*" >&2; exit 1; }
conflict() { echo "reconcile-publication: immutable release conflict: $*" >&2; exit 42; }
invalid_approval() { echo "reconcile-publication: invalid approval: $*" >&2; exit 43; }
maven_ambiguous() { echo "reconcile-publication: ambiguous Maven submission: $*" >&2; exit 44; }

required=(
  GH_TOKEN RELEASE_INPUT_FILE RELEASE_MAVEN_ARTIFACTS_FILE RELEASE_OUTPUT_FILE RELEASE_STAGE
  TRUSTED_AUTOMATION_ROOT TRUSTED_WORKER_COMMIT
)
if [[ $RELEASE_STAGE == maven-* || $RELEASE_STAGE == inspect ]]; then
  required+=(RH_PASSWORD RH_USER)
fi
for variable in "${required[@]}"; do
  [[ -n ${!variable:-} ]] || fail "Required value $variable is missing."
done

repository=temporalio/sdk-java
maven_group=io.temporal
central_base=https://repo1.maven.org/maven2
tag=$(jq -er .release.candidate.tag "$RELEASE_INPUT_FILE")
version=${tag#v}
commit=$(jq -er .release.candidate.commitSha "$RELEASE_INPUT_FILE")
notes_file=releases/$tag
notes_hash=$(jq -er .release.candidate.releaseNotesSha256 "$RELEASE_INPUT_FILE")
trusted_commit=$(jq -er .release.candidate.trustedAutomationCommit "$RELEASE_INPUT_FILE")
maven_policy=$(jq -er .release.candidate.mavenPolicy "$RELEASE_INPUT_FILE")
manifest_hash=$(jq -er .release.manifestSha256 "$RELEASE_INPUT_FILE")
release_digest=$(jq -er .approval.releaseDigest "$RELEASE_INPUT_FILE")
workflow_id=$(jq -er .workflowId "$RELEASE_INPUT_FILE")
run_id=$(jq -er .runId "$RELEASE_INPUT_FILE")
submission_generation=$(jq -er .mavenSubmissionGeneration "$RELEASE_INPUT_FILE")

[[ $tag =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-RC[0-9]+)?$ &&
  $commit =~ ^[0-9a-f]{40}$ && $notes_hash =~ ^[0-9a-f]{64}$ &&
  $manifest_hash =~ ^[0-9a-f]{64}$ && $release_digest =~ ^[0-9a-f]{64}$ &&
  $submission_generation =~ ^[0-9]+$ && $trusted_commit == "$TRUSTED_WORKER_COMMIT" ]] ||
  conflict "the Activity input violates sdk-java release policy."
[[ $(git rev-parse --verify HEAD^{commit}) == "$commit" ]] ||
  conflict "the source checkout is not the approved commit."
[[ -s $notes_file && ! -L $notes_file ]] || conflict "the release notes are unavailable."
[[ $(sha256sum "$notes_file" | awk '{print $1}') == "$notes_hash" ]] ||
  conflict "the release notes changed."

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/assets" "$work/existing"
mapfile -t maven_artifacts < <(jq -er '.[]' "$RELEASE_MAVEN_ARTIFACTS_FILE")

verify_source_maven_policy() {
  mapfile -t source_projects < <(sed -n -E "s/^include ['\"]([^'\"]+)['\"]$/\1/p" settings.gradle | sort)
  mapfile -t policy_projects < <(printf '%s\n' "${maven_artifacts[@]}" | sort)
  [[ ${source_projects[*]} == "${policy_projects[*]}" ]] ||
    conflict "the immutable source projects differ from the Maven policy."
  case "$maven_policy:${#policy_projects[@]}" in
    current:17 | classic:11 | classic-alpha:11 | classic-alpha-lite:9) ;;
    *) conflict "the Maven policy is not a reviewed sdk-java profile." ;;
  esac
}

verify_approval() {
  local approval_run approval_actor approval_run_id issue issue_number issue_creator body_hash status
  approval_actor=$(jq -er .approval.githubActor "$RELEASE_INPUT_FILE")
  approval_run_id=$(jq -er .approval.githubApprovalRunId "$RELEASE_INPUT_FILE")
  issue_number=$(jq -er .approval.githubIssueNumber "$RELEASE_INPUT_FILE")
  issue_creator=$(jq -er .approvalRequest.githubIssueCreator "$RELEASE_INPUT_FILE")
  approval_run=$(gh api "repos/temporalio/sdk-java/actions/runs/$approval_run_id") ||
    fail "The approval Actions run is temporarily unavailable."
  jq -e --arg actor "$approval_actor" '
    (.status == "in_progress" or .status == "completed") and
    .path == ".github/workflows/temporal-release-approve.yml" and
    ((.event == "issues" and .actor.login == $actor) or .event == "schedule")' \
    <<<"$approval_run" >/dev/null || invalid_approval "the GitHub approval run differs."
  issue=$(gh api "repos/temporalio/sdk-java/issues/$issue_number") ||
    fail "The exact approval issue is temporarily unavailable."
  body_hash=$(jq -j .body <<<"$issue" | sha256sum | awk '{print $1}')
  jq -e --arg actor "$approval_actor" --arg creator "$issue_creator" \
    --arg node "$(jq -er .approval.githubIssueNodeId "$RELEASE_INPUT_FILE")" \
    --argjson number "$issue_number" '
    .number == $number and .node_id == $node and .state == "closed" and .locked == true and
    .closed_by.login == $actor and .user.login == $creator' <<<"$issue" >/dev/null ||
    invalid_approval "the locked approval issue differs."
  [[ $body_hash == $(jq -er .approval.githubIssueBodySha256 "$RELEASE_INPUT_FILE") ]] ||
    invalid_approval "the approval issue body changed."
  set +e
  "$TRUSTED_AUTOMATION_ROOT/.github/scripts/temporal-release/verify-approver.sh" \
    "$approval_actor" >/dev/null
  status=$?
  set -e
  [[ $status -eq 0 ]] || {
    [[ $status -eq 43 ]] && invalid_approval "the approver is not an active sdk team member."
    fail "Release-manager membership is temporarily unavailable."
  }
}

download_receipt() {
  local receipt=$1 destination=$2
  GH_TOKEN=$GH_TOKEN GITHUB_ARTIFACT_RECEIPT_FILE=$receipt \
    GITHUB_ARTIFACT_DESTINATION=$destination \
    "$TRUSTED_AUTOMATION_ROOT/.github/scripts/temporal-release/download-github-artifact.sh"
}

verify_artifact_origin() {
  local receipt=$1 expected_path=$2 run_id run
  run_id=$(jq -er .workflowRunId "$receipt")
  run=$(gh api "repos/temporalio/sdk-java/actions/runs/$run_id") ||
    fail "The originating GitHub Actions run is temporarily unavailable."
  jq -e --argjson id "$run_id" --arg path "$expected_path" '
    .id == $id and .path == $path and .head_branch == "main" and
    .head_repository.full_name == "temporalio/sdk-java" and
    (.status == "in_progress" or .status == "completed") and
    (if $path == ".github/workflows/temporal-release-resume.yml"
     then (.event == "schedule" or .event == "workflow_dispatch")
     else (.event == "workflow_run" or .event == "schedule" or .event == "workflow_dispatch")
     end)' <<<"$run" >/dev/null || conflict "the artifact originated from another workflow run."
}

materialize_native_assets() {
  local count index receipt
  count=$(jq '.release.manifest.artifacts | length' "$RELEASE_INPUT_FILE")
  [[ $count -eq 6 ]] || conflict "the native artifact receipt set is incomplete."
  for ((index = 0; index < count; index++)); do
    receipt=$work/native-$index.json
    jq ".release.manifest.artifacts[$index]" "$RELEASE_INPUT_FILE" >"$receipt"
    verify_artifact_origin "$receipt" .github/workflows/temporal-release-resume.yml
    mkdir "$work/native-$index"
    download_receipt "$receipt" "$work/native-$index"
    find "$work/native-$index" -mindepth 1 -maxdepth 1 -type f -exec cp {} "$work/assets/" \;
  done
  [[ $(find "$work/assets" -mindepth 1 -maxdepth 1 -type f | wc -l | tr -d ' ') -eq 6 ]] ||
    conflict "the downloaded native asset set differs."
  (cd "$work/assets" && sha256sum *.tar.gz *.zip | sort -k2) >"$work/assets/SHA256SUMS"
}

materialize_maven_payload() {
  local receipt=$work/maven-receipt.json archive=$work/maven-download/maven-payload.tar
  local bundle=$work/maven-bundle
  jq .mavenPayload "$RELEASE_INPUT_FILE" >"$receipt"
  verify_artifact_origin "$receipt" .github/workflows/temporal-release-publish.yml
  mkdir "$work/maven-download"
  download_receipt "$receipt" "$work/maven-download"
  [[ -s $archive ]] || conflict "the exact Maven payload archive is absent."
  mkdir "$bundle"
  python3 - "$archive" "$bundle" <<'PY' || conflict "the Maven archive is unsafe."
import pathlib, sys, tarfile

archive_path, output_path = sys.argv[1:]
output = pathlib.Path(output_path).resolve()
seen = set()
with tarfile.open(archive_path, "r:") as archive:
    for member in archive:
        name = member.name.rstrip("/")
        path = pathlib.PurePosixPath(name)
        if (not name or path.is_absolute() or ".." in path.parts or name in seen or
                not (name == "manifest.tsv" or name == "repository" or
                     name == "repository/io" or name == "repository/io/temporal" or
                     name.startswith("repository/io/temporal/"))):
            raise SystemExit("unexpected archive path")
        seen.add(name)
        target = output.joinpath(*path.parts)
        if member.isdir():
            target.mkdir(parents=True, exist_ok=True)
        elif member.isfile():
            target.parent.mkdir(parents=True, exist_ok=True)
            source = archive.extractfile(member)
            if source is None:
                raise SystemExit("missing archive file data")
            with target.open("xb") as destination:
                destination.write(source.read())
        else:
            raise SystemExit("archive links and special files are forbidden")
PY
  payload_root=$bundle/repository
  payload_manifest=$bundle/manifest.tsv
  [[ -s $payload_manifest && -d $payload_root/io/temporal ]] ||
    conflict "the Maven bundle is incomplete."
  printf '%s\n' "${maven_artifacts[@]}" >"$work/approved-maven-artifacts.txt"
  python3 - "$payload_root" "$payload_manifest" "$work/approved-maven-artifacts.txt" \
    "$version" "$commit" <<'PY' || conflict "the Maven bundle violates sdk-java policy."
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
        raise SystemExit("path outside Maven policy")
    artifact, found_version, filename = parts[2:]
    pattern = re.escape(f"{artifact}-{version}") + r"(?:-(?:sources|javadoc))?\.(?:jar|pom|module)(?:\.(?:asc|md5|sha1))?"
    if artifact not in approved or found_version != version or not re.fullmatch(pattern, filename):
        raise SystemExit("coordinate outside Maven policy")
    path = (root / relative).resolve()
    if root not in path.parents or not path.is_file() or path.is_symlink():
        raise SystemExit("invalid Maven file")
    data = path.read_bytes()
    if hashlib.sha256(data).hexdigest() != sha or len(data) != int(size):
        raise SystemExit("Maven checksum differs")
    records.append(relative)
if records != sorted(set(records)):
    raise SystemExit("Maven manifest is unsorted or duplicated")
actual = sorted(str(path.relative_to(root)).replace("\\", "/") for path in root.rglob("*") if path.is_file())
if actual != records:
    raise SystemExit("Maven file set differs")
for artifact in approved:
    pom = root / "io" / "temporal" / artifact / version / f"{artifact}-{version}.pom"
    document = ET.parse(pom).getroot()
    ns = document.tag.partition("}")[0] + "}" if document.tag.startswith("{") else ""
    identity = (document.findtext(f"{ns}groupId", "").strip(),
                document.findtext(f"{ns}artifactId", "").strip(),
                document.findtext(f"{ns}version", "").strip(),
                document.findtext(f"{ns}scm/{ns}tag", "").strip().lower())
    if identity != ("io.temporal", artifact, version, commit):
        raise SystemExit("Maven POM identity differs")
PY
}

central_state() {
  present=0
  missing=0
  verify_source_maven_policy
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
        [[ $identity == "$maven_group"$'\t'"$artifact"$'\t'"$version"$'\t'"$commit" ]] ||
          conflict "$artifact exists with another immutable identity."
        present=$((present + 1))
        ;;
      404) missing=$((missing + 1)) ;;
      *) fail "Maven Central returned HTTP $status for $artifact." ;;
    esac
  done
}

validate_central_payload() {
  while IFS=$'\t' read -r relative sha size; do
    file=$work/central-$(printf '%s' "$relative" | sha256sum | awk '{print $1}')
    status=$(curl --silent --show-error --location --output "$file" --write-out '%{http_code}' \
      "$central_base/$relative") || fail "Maven Central payload is temporarily unavailable."
    [[ $status == 200 ]] || fail "Maven Central returned HTTP $status for $relative."
    [[ $(sha256sum "$file" | awk '{print $1}') == "$sha" &&
      $(wc -c <"$file" | tr -d ' ') == "$size" ]] ||
      conflict "Maven Central payload $relative differs."
  done <"$payload_manifest"
}

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

generation_state() {
  jq -cer --argjson generation "$1" \
    '[.mavenGenerations[] | select(.generation == $generation)] |
     if length == 1 then .[0] else error("missing Maven generation") end' \
    "$RELEASE_INPUT_FILE"
}

find_repository_by_description() {
  local description=$1
  jq -rn --arg description "$description" --slurpfile profiles "$work/profile-repositories.json" \
    --slurpfile manual "$work/manual-repositories.json" '
    [($profiles[0].data // $profiles[0].profileRepositories // [])[] |
       select(.description == $description) | .repositoryId // .id] +
    [($manual[0].repositories // [])[] | select(.description == $description) | .key] |
    unique | if length <= 1 then .[0] // "" else error("multiple exact repositories") end'
}

portal_status() {
  local deployment_id=$1
  curl --silent --show-error --fail --request POST \
    --header "Authorization: Bearer $portal_token" --header 'Accept: application/json' \
    "https://central.sonatype.com/api/v1/publisher/status?id=$deployment_id" \
    >"$work/portal-status.json" || fail "Publisher Portal deployment is unavailable."
  jq -er --arg id "$deployment_id" 'select(.deploymentId == $id) | .deploymentState' \
    "$work/portal-status.json"
}

validate_retry_authorization() {
  (( submission_generation > 0 )) || return
  local actor run run_json status
  actor=$(jq -er .mavenRetryAuthorization.githubActor "$RELEASE_INPUT_FILE")
  run=$(jq -er .mavenRetryAuthorization.githubRunId "$RELEASE_INPUT_FILE")
  jq -e --argjson generation "$submission_generation" '
    .mavenRetryAuthorization.action == "retry-maven-submission" and
    .mavenRetryAuthorization.mavenSubmissionGeneration == $generation and
    (.mavenRetryAuthorization.authorizationSha256 | test("^[0-9a-f]{64}$")) and
    .mavenRetryAuthorization.mavenInspection != null' "$RELEASE_INPUT_FILE" >/dev/null ||
    conflict "the Maven retry authorization differs."
  run_json=$(gh api "repos/temporalio/sdk-java/actions/runs/$run") ||
    fail "The Maven retry authorization run is unavailable."
  jq -e --arg actor "$actor" '
    .event == "workflow_dispatch" and .path == ".github/workflows/temporal-release-control.yml" and
    .actor.login == $actor and (.status == "in_progress" or .status == "completed")' \
    <<<"$run_json" >/dev/null || invalid_approval "the Maven retry run differs."
  set +e
  "$TRUSTED_AUTOMATION_ROOT/.github/scripts/temporal-release/verify-approver.sh" "$actor" >/dev/null
  status=$?
  set -e
  [[ $status -eq 0 ]] || {
    [[ $status -eq 43 ]] && invalid_approval "the Maven retry authorizer is not active."
    fail "Maven retry authorizer membership is unavailable."
  }
}

validate_prior_generations_inactive() {
  (( submission_generation > 0 )) || return
  local row generation description repository_id discovered_id portal_id state profile_count manual_count
  while IFS= read -r row; do
    generation=$(jq -er .generation <<<"$row")
    (( generation < submission_generation )) || continue
    description=$(jq -er .description <<<"$row")
    repository_id=$(jq -r '.sonatypeRepositoryId // ""' <<<"$row")
    discovered_id=$(find_repository_by_description "$description") ||
      conflict "multiple repositories match earlier Maven generation $generation."
    [[ -z $repository_id || -z $discovered_id || $repository_id == "$discovered_id" ]] ||
      conflict "earlier Maven generation $generation has another repository identity."
    [[ -n $repository_id ]] || repository_id=$discovered_id
    portal_id=$(jq -r '.portalDeploymentId // ""' <<<"$row")
    if [[ -n $repository_id && -z $portal_id ]]; then
      portal_id=$(jq -r --arg id "$repository_id" \
        '[.repositories[] | select(.key == $id) | .portal_deployment_id] | first // ""' \
        "$work/manual-repositories.json")
    fi
    state=
    [[ -z $portal_id ]] || state=$(portal_status "$portal_id")
    [[ -z $state || $state == FAILED ]] ||
      maven_ambiguous "earlier Maven generation $generation has Portal state $state."
    if [[ -n $repository_id ]]; then
      profile_count=$(jq --arg id "$repository_id" '
        [((.data // .profileRepositories // [])[]) | select((.repositoryId // .id) == $id)] |
        length' "$work/profile-repositories.json")
      manual_count=$(jq --arg id "$repository_id" \
        '[.repositories[] | select(.key == $id)] | length' "$work/manual-repositories.json")
      (( profile_count == 0 )) ||
        maven_ambiguous "earlier Maven generation $generation is still staged."
      if [[ $state == FAILED ]]; then
        (( manual_count == 1 )) ||
          maven_ambiguous "failed Maven generation $generation has ambiguous repository state."
        jq -e --arg id "$repository_id" --arg portal "$portal_id" '
          [.repositories[] | select(.key == $id)] | length == 1 and
          .[0].state == "released" and .[0].portal_deployment_id == $portal' \
          "$work/manual-repositories.json" >/dev/null ||
          maven_ambiguous "failed Maven generation $generation is not inactive."
      else
        (( manual_count == 0 )) ||
          maven_ambiguous "earlier Maven generation $generation is still live."
      fi
    fi
  done < <(jq -c '.mavenGenerations[] | select(.submissionStarted == true)' "$RELEASE_INPUT_FILE")
}

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

upload_missing_payload() {
  while IFS=$'\t' read -r relative _ _; do
    [[ -n $relative ]] || continue
    curl --silent --show-error --fail --user "$RH_USER:$RH_PASSWORD" \
      --upload-file "$payload_root/$relative" \
      "https://ossrh-staging-api.central.sonatype.com/service/local/staging/deployByRepositoryId/$repository_id/$relative" \
      >/dev/null || fail "Unable to upload staged Maven file $relative."
  done <"$work/remote-missing.tsv"
}

verify_staging_file_set() {
  : >"$work/staging-files.txt"
  printf '\n' >"$work/staging-directories.txt"
  while IFS= read -r directory; do
    suffix=${directory:+$directory/}
    listing=$work/listing-$(printf '%s' "$directory" | sha256sum | awk '{print $1}').json
    curl --silent --show-error --fail --user "$RH_USER:$RH_PASSWORD" \
      --header 'Accept: application/json' \
      "https://ossrh-staging-api.central.sonatype.com/service/local/repositories/$repository_id/content/$suffix" \
      >"$listing" || fail "Unable to enumerate the staged repository."
    jq -r --arg directory "$directory" '
      .data[] | select(.leaf == true) | .relativePath | ltrimstr("/") |
      if contains("/") then . else ($directory + "/" + .) end' "$listing" |
      sed 's#^/##' >>"$work/staging-files.txt"
    while IFS= read -r child; do
      child=${child#/}
      [[ $child == */* || -z $directory ]] || child=$directory/$child
      grep -Fxq "$child" "$work/staging-directories.txt" ||
        printf '%s\n' "$child" >>"$work/staging-directories.txt"
    done < <(jq -r '.data[] | select(.leaf == false) | .relativePath' "$listing")
  done <"$work/staging-directories.txt"
  cut -f1 "$payload_manifest" | sort >"$work/expected-staging-files.txt"
  sort -u "$work/staging-files.txt" -o "$work/staging-files.txt"
  cmp "$work/expected-staging-files.txt" "$work/staging-files.txt" >/dev/null ||
    conflict "the staged Maven repository file set differs."
}

reconcile_maven_repository() {
  local state description stored_id external_id allow
  central_state
  [[ $present -eq 0 || $present -eq ${#maven_artifacts[@]} ]] ||
    fail "Maven publication is partially visible."
  state=$(generation_state "$submission_generation")
  description=sdk-java:$release_digest:$submission_generation
  [[ $(jq -er .description <<<"$state") == "$description" &&
    $(jq -r .submissionStarted <<<"$state") == true ]] ||
    conflict "the durable Maven generation intent differs."
  stored_id=$(jq -r '.sonatypeRepositoryId // ""' <<<"$state")
  sonatype_snapshot
  if [[ -n $stored_id ]]; then
    jq -n --arg value "$stored_id" '$value' >"$RELEASE_OUTPUT_FILE"
    return
  fi
  external_id=$(find_repository_by_description "$description") ||
    conflict "multiple Sonatype repositories match the generation."
  if [[ -n $external_id ]]; then
    jq -n --arg value "$external_id" '$value' >"$RELEASE_OUTPUT_FILE"
    return
  fi
  [[ $present -eq 0 ]] || maven_ambiguous "Central is complete without a repository identity."
  validate_prior_generations_inactive
  allow=${RELEASE_ALLOW_MAVEN_REPOSITORY_CREATION:-false}
  [[ $allow == true ]] || maven_ambiguous "the durable intent has no discoverable repository."
  repository_id=$(SONATYPE_REPOSITORY_DESCRIPTION=$description \
    "$TRUSTED_AUTOMATION_ROOT/.github/scripts/temporal-release/create-sonatype-repository.sh")
  [[ $repository_id =~ ^[A-Za-z0-9._-]+$ ]] || fail "Sonatype did not return a repository ID."
  jq -n --arg value "$repository_id" '$value' >"$RELEASE_OUTPUT_FILE"
}

reconcile_maven_portal() {
  local state description repository_state portal_id close_status
  materialize_maven_payload
  state=$(generation_state "$submission_generation")
  description=sdk-java:$release_digest:$submission_generation
  repository_id=$(jq -er .sonatypeRepositoryId <<<"$state")
  sonatype_snapshot
  profile_description=$(jq -r --arg id "$repository_id" '
    [((.data // .profileRepositories // [])[]) |
      select((.repositoryId // .id) == $id) | .description] | first // ""' \
    "$work/profile-repositories.json")
  [[ -z $profile_description || $profile_description == "$description" ]] ||
    conflict "the Sonatype repository ID has another description."
  repository_state=$(jq -r --arg id "$repository_id" \
    '[.repositories[] | select(.key == $id) | .state] | first // ""' \
    "$work/manual-repositories.json")
  portal_id=$(jq -r --arg id "$repository_id" \
    '[.repositories[] | select(.key == $id) | .portal_deployment_id] | first // ""' \
    "$work/manual-repositories.json")
  [[ -n $repository_state ]] || {
    [[ -n $profile_description ]] || maven_ambiguous "the repository disappeared from Sonatype."
    repository_state=open
  }
  if [[ $repository_state == open ]]; then
    inspect_staging_payload
    upload_missing_payload
    inspect_staging_payload
    [[ ! -s $work/remote-missing.tsv ]] || fail "The staged Maven payload is incomplete."
    verify_staging_file_set
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
  jq -n --arg value "$portal_id" '$value' >"$RELEASE_OUTPUT_FILE"
}

publish_maven() {
  local state deployment_state publish_status
  materialize_maven_payload
  state=$(generation_state "$submission_generation")
  repository_id=$(jq -er .sonatypeRepositoryId <<<"$state")
  portal_id=$(jq -er .portalDeploymentId <<<"$state")
  sonatype_snapshot
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
  central_state
  [[ $missing -eq 0 && $present -eq ${#maven_artifacts[@]} ]] ||
    fail "The complete Maven release is not visible yet."
  validate_central_payload
  jq -n --arg mavenCentralUrl \
    "https://central.sonatype.com/artifact/io.temporal/temporal-sdk/$version" \
    --arg sonatypeRepositoryId "$repository_id" --arg portalDeploymentId "$portal_id" \
    '{mavenCentralUrl:$mavenCentralUrl,sonatypeRepositoryId:$sonatypeRepositoryId,
      portalDeploymentId:$portalDeploymentId}' >"$RELEASE_OUTPUT_FILE"
}

release_json() {
  local releases
  releases=$(gh api --paginate --slurp 'repos/temporalio/sdk-java/releases?per_page=100') ||
    fail "GitHub releases are temporarily unavailable."
  jq -c --arg tag "$tag" '[.[][]] | map(select(.tag_name == $tag)) | first // empty' <<<"$releases"
}

github_optional_get() {
  local path=$1 output=$2 status
  status=$(curl --silent --show-error --location --output "$output" --write-out '%{http_code}' \
    --header "Authorization: Bearer $GH_TOKEN" --header 'Accept: application/vnd.github+json' \
    --header 'X-GitHub-Api-Version: 2022-11-28' "https://api.github.com/$path") ||
    fail "GitHub is temporarily unavailable."
  case $status in 200) return 0 ;; 404) : >"$output"; return 1 ;; *)
    fail "GitHub returned HTTP $status while reading $path." ;; esac
}

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

verify_release_metadata() {
  local release=$1 draft=$2 prerelease=false
  [[ $tag == *-RC* ]] && prerelease=true
  jq -e --arg tag "$tag" --arg commit "$commit" --rawfile notes "$notes_file" \
    --argjson prerelease "$prerelease" --argjson draft "$draft" '
    .tag_name == $tag and .name == $tag and .body == $notes and .draft == $draft and
    .prerelease == $prerelease and .target_commitish == $commit' <<<"$release" >/dev/null ||
    conflict "GitHub release metadata differs."
}

verify_exact_github_assets() {
  local release=$1 name state size expected
  mapfile -t expected < <(find "$work/assets" -mindepth 1 -maxdepth 1 -type f -exec basename {} \; | sort)
  mapfile -t actual < <(jq -r '.assets[].name' <<<"$release" | sort)
  [[ ${actual[*]} == "${expected[*]}" ]] || fail "The GitHub asset set is not complete."
  while IFS=$'\t' read -r name state size; do
    [[ $state == uploaded ]] || conflict "GitHub asset $name has unsupported state $state."
    [[ $size == $(wc -c <"$work/assets/$name" | tr -d ' ') ]] ||
      conflict "GitHub asset $name has the wrong size."
    gh release download "$tag" --repo temporalio/sdk-java --pattern "$name" \
      --dir "$work/existing" --clobber >/dev/null || fail "Unable to download asset $name."
    cmp "$work/assets/$name" "$work/existing/$name" >/dev/null ||
      conflict "GitHub asset $name differs."
  done < <(jq -r '.assets[] | [.name,.state,.size] | @tsv' <<<"$release")
}

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

reconcile_github_draft() {
  local release draft name state size id
  materialize_native_assets
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
  while IFS=$'\t' read -r id name state size; do
    [[ -f $work/assets/$name ]] || conflict "the release has unexpected asset $name."
    if [[ $draft == true && $state == starter && $size == 0 ]]; then
      gh api --method DELETE "repos/temporalio/sdk-java/releases/assets/$id" >/dev/null
    elif [[ $state != uploaded ]]; then
      conflict "GitHub asset $name has unsupported state $state."
    fi
  done < <(jq -r '.assets[] | [.id,.name,.state,.size] | @tsv' <<<"$release")
  release=$(release_json)
  for asset in "$work/assets"/*; do
    name=$(basename "$asset")
    if jq -e --arg name "$name" '.assets[] | select(.name == $name)' <<<"$release" >/dev/null; then
      gh release download "$tag" --repo temporalio/sdk-java --pattern "$name" \
        --dir "$work/existing" --clobber >/dev/null
      cmp "$asset" "$work/existing/$name" >/dev/null || conflict "GitHub asset $name differs."
    else
      [[ $draft == true ]] || conflict "the public release is missing asset $name."
      gh release upload "$tag" "$asset" --repo temporalio/sdk-java >/dev/null
    fi
  done
  release=$(release_json)
  verify_exact_github_assets "$release"
  jq -n --arg value "$(jq -er .html_url <<<"$release")" '$value' >"$RELEASE_OUTPUT_FILE"
}

publish_github() {
  local release
  materialize_maven_payload
  central_state
  [[ $missing -eq 0 && $present -eq ${#maven_artifacts[@]} ]] ||
    fail "Maven Central is incomplete immediately before GitHub publication."
  validate_central_payload
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

inspect_maven() {
  local row generation description repository_id repository_state portal_id portal_state profile
  central_state
  sonatype_snapshot
  : >"$work/inspections.jsonl"
  while IFS= read -r row; do
    generation=$(jq -er .generation <<<"$row")
    description=$(jq -er .description <<<"$row")
    repository_id=$(jq -r '.sonatypeRepositoryId // ""' <<<"$row")
    if [[ -z $repository_id ]]; then
      repository_id=$(find_repository_by_description "$description") ||
        conflict "multiple repositories match Maven generation $generation."
    fi
    repository_state=absent
    portal_id=$(jq -r '.portalDeploymentId // ""' <<<"$row")
    portal_state=""
    if [[ -n $repository_id ]]; then
      repository_state=$(jq -r --arg id "$repository_id" \
        '[.repositories[] | select(.key == $id) | .state] | first // ""' \
        "$work/manual-repositories.json")
      profile=$(jq -r --arg id "$repository_id" '
        [((.data // .profileRepositories // [])[]) |
          select((.repositoryId // .id) == $id)] | first // empty' \
        "$work/profile-repositories.json")
      [[ -n $repository_state ]] || repository_state=$([[ -n $profile ]] && echo open || echo absent)
      [[ -n $portal_id ]] || portal_id=$(jq -r --arg id "$repository_id" \
        '[.repositories[] | select(.key == $id) | .portal_deployment_id] | first // ""' \
        "$work/manual-repositories.json")
    fi
    [[ -z $portal_id ]] || portal_state=$(portal_status "$portal_id")
    jq -cn --argjson generation "$generation" --arg description "$description" \
      --arg repositoryId "$repository_id" --arg repositoryState "$repository_state" \
      --arg portalDeploymentId "$portal_id" --arg portalDeploymentState "$portal_state" \
      '{generation:$generation,description:$description,repositoryId:$repositoryId,
        repositoryState:$repositoryState,portalDeploymentId:$portalDeploymentId,
        portalDeploymentState:$portalDeploymentState}' >>"$work/inspections.jsonl"
  done < <(jq -c '.mavenGenerations[]' "$RELEASE_INPUT_FILE")
  jq -s --argjson centralPresent "$present" --argjson centralMissing "$missing" \
    '{centralPresent:$centralPresent,centralMissing:$centralMissing,generations:.}' \
    "$work/inspections.jsonl" >"$RELEASE_OUTPUT_FILE"
}

if [[ $RELEASE_STAGE != inspect ]]; then
  verify_approval
  validate_retry_authorization
fi
case $RELEASE_STAGE in
  inspect) inspect_maven ;;
  preflight)
    materialize_native_assets
    materialize_maven_payload
    verify_github_preflight
    ;;
  maven-repository) reconcile_maven_repository ;;
  maven-portal) reconcile_maven_portal ;;
  maven-publish) publish_maven ;;
  github-draft) reconcile_github_draft ;;
  github-publish) publish_github ;;
  *) fail "Temporal scheduled an unknown publication stage." ;;
esac
