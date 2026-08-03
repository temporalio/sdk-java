#!/usr/bin/env bash

# Independent dispatch-only fallback. This intentionally duplicates the small fixed sdk-java
# release policy instead of importing the Temporal Java application, its scripts, or its S3 state.
set -euo pipefail

fail() { echo "manual-release: $*" >&2; exit 1; }
conflict() { echo "manual-release: immutable conflict: $*" >&2; exit 42; }

run_signing_gradle() {
  env -u GH_TOKEN -u AWS_ACCESS_KEY_ID -u AWS_SECRET_ACCESS_KEY -u AWS_SESSION_TOKEN \
    -u AWS_REGION -u AWS_DEFAULT_REGION -u ACTIONS_ID_TOKEN_REQUEST_URL \
    -u ACTIONS_ID_TOKEN_REQUEST_TOKEN -u RH_USER -u RH_PASSWORD \
    GRADLE_USER_HOME="$signing_gradle_home" ./gradlew "$@"
}

run_sonatype_gradle() {
  env -u GH_TOKEN -u AWS_ACCESS_KEY_ID -u AWS_SECRET_ACCESS_KEY -u AWS_SESSION_TOKEN \
    -u AWS_REGION -u AWS_DEFAULT_REGION -u ACTIONS_ID_TOKEN_REQUEST_URL \
    -u ACTIONS_ID_TOKEN_REQUEST_TOKEN -u JAR_SIGNING_KEY -u JAR_SIGNING_KEY_ID \
    -u JAR_SIGNING_KEY_PASSWORD GRADLE_USER_HOME="$sonatype_gradle_home" ./gradlew "$@"
}

for name in GH_TOKEN MANUAL_RELEASE_ACTION RELEASE_COMMIT RELEASE_TAG; do
  [[ -n ${!name:-} ]] || fail "$name is required."
done
[[ $MANUAL_RELEASE_ACTION == inspect || $MANUAL_RELEASE_ACTION == resume ]] ||
  fail "Unknown fixed manual release action."
[[ $RELEASE_COMMIT =~ ^[0-9a-f]{40}$ ]] || fail "A full source SHA is required."
[[ $RELEASE_TAG =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-RC[0-9]+)?$ ]] ||
  fail "The release tag is invalid."
[[ $(git rev-parse HEAD) == "$RELEASE_COMMIT" ]] || fail "The source checkout differs."
version=${RELEASE_TAG#v}
notes="releases/$RELEASE_TAG"
[[ -s $notes && ! -L $notes ]] || fail "The exact release notes are unavailable."
notes_sha256=$(sha256sum "$notes" | awk '{print $1}')
[[ -z ${EXPECTED_NOTES_SHA256:-} || $notes_sha256 == "$EXPECTED_NOTES_SHA256" ]] ||
  conflict "the release-note checksum differs."
if [[ $MANUAL_RELEASE_ACTION == resume ]]; then
  for name in MANUAL_CONTROLLER_SHA MANUAL_ISSUE_CREATOR MANUAL_OWNER_ACTOR \
    MANUAL_OWNER_RUN_ID MANUAL_OWNERSHIP_ISSUE RELEASE_ARTIFACT_BUCKET; do
    [[ -n ${!name:-} ]] || fail "$name is required for fallback publication."
  done
  ownership_issue=$(gh api "repos/temporalio/sdk-java/issues/$MANUAL_OWNERSHIP_ISSUE") ||
    fail "Unable to inspect the exact independent ownership issue."
  ownership_body=$(jq -r .body <<<"$ownership_issue")
  expected_ownership_body=$(printf '%s\n' \
    '## Independent manual release ownership' '' \
    "- Tag: \`$RELEASE_TAG\`" "- Full SHA: \`$RELEASE_COMMIT\`" \
    "- Controller SHA: \`$MANUAL_CONTROLLER_SHA\`" \
    "- Actor: \`$MANUAL_OWNER_ACTOR\`" "- Actions run: \`$MANUAL_OWNER_RUN_ID\`" '' \
    'Temporal automation must not mutate this tag while this locked issue remains open.')
  [[ $ownership_body == "$expected_ownership_body" ]] ||
    conflict "the independent ownership body is not the fixed exact receipt."
  jq -e --arg creator "$MANUAL_ISSUE_CREATOR" \
    '.state == "open" and .locked == true and .user.login == $creator and
     (has("pull_request") | not)' <<<"$ownership_issue" >/dev/null ||
    conflict "the independent ownership issue is not the trusted locked record."
fi

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
central_base=https://repo1.maven.org/maven2
staging_base=https://ossrh-staging-api.central.sonatype.com
manual_description="sdk-java-manual:$RELEASE_TAG:$RELEASE_COMMIT"

mapfile -t projects < <(sed -n -E "s/^include ['\"]([^'\"]+)['\"]$/\1/p" settings.gradle | sort)
current=(temporal-aws-lambda temporal-bom temporal-envconfig temporal-kotlin temporal-opentelemetry
  temporal-opentracing temporal-remote-data-encoder temporal-sdk temporal-serviceclient
  temporal-shaded temporal-spring-ai temporal-spring-boot-autoconfigure
  temporal-spring-boot-starter temporal-test-server temporal-testing temporal-workflowcheck
  temporal-workflowstreams)
classic=(temporal-bom temporal-kotlin temporal-opentracing temporal-remote-data-encoder temporal-sdk
  temporal-serviceclient temporal-shaded temporal-spring-boot-autoconfigure
  temporal-spring-boot-starter temporal-test-server temporal-testing)
classic_alpha=(temporal-bom temporal-kotlin temporal-opentracing temporal-remote-data-encoder
  temporal-sdk temporal-serviceclient temporal-shaded temporal-spring-boot-autoconfigure-alpha
  temporal-spring-boot-starter-alpha temporal-test-server temporal-testing)
classic_alpha_lite=(temporal-kotlin temporal-opentracing temporal-remote-data-encoder temporal-sdk
  temporal-serviceclient temporal-spring-boot-autoconfigure-alpha
  temporal-spring-boot-starter-alpha temporal-test-server temporal-testing)
for profile in current classic classic_alpha classic_alpha_lite; do
  case "$profile" in
    current) candidate=("${current[@]}") ;;
    classic) candidate=("${classic[@]}") ;;
    classic_alpha) candidate=("${classic_alpha[@]}") ;;
    classic_alpha_lite) candidate=("${classic_alpha_lite[@]}") ;;
  esac
  mapfile -t candidate < <(printf '%s\n' "${candidate[@]}" | sort)
  if [[ ${projects[*]} == "${candidate[*]}" ]]; then
    maven_artifacts=("${candidate[@]}")
    policy=$profile
    break
  fi
done
[[ -n ${policy:-} ]] || conflict "source modules do not match a fixed fallback profile."

central_state() {
  present=0
  missing=0
  for artifact in "${maven_artifacts[@]}"; do
    pom="$work/$artifact.pom"
    status=$(curl --silent --show-error --location --output "$pom" --write-out '%{http_code}' \
      "$central_base/io/temporal/$artifact/$version/$artifact-$version.pom") ||
      fail "Maven Central is temporarily unavailable."
    case "$status" in
      200)
        python3 - "$pom" "$artifact" "$version" "$RELEASE_COMMIT" <<'PY'
import sys, xml.etree.ElementTree as ET
path, artifact, version, commit = sys.argv[1:]
root = ET.parse(path).getroot()
ns = root.tag.partition("}")[0] + "}" if root.tag.startswith("{") else ""
values = (root.findtext(f"{ns}groupId", "").strip(),
          root.findtext(f"{ns}artifactId", "").strip(),
          root.findtext(f"{ns}version", "").strip(),
          root.findtext(f"{ns}scm/{ns}tag", "").strip().lower())
if values != ("io.temporal", artifact, version, commit):
    raise SystemExit("published POM has another immutable identity")
PY
        present=$((present + 1))
        ;;
      404) missing=$((missing + 1)) ;;
      *) fail "Maven Central returned HTTP $status for $artifact." ;;
    esac
  done
  (( present == 0 || missing == 0 )) || conflict "Maven Central is only partially exact."
}

sonatype_state() {
  exact_repository_id=""
  exact_repository_state=""
  portal_deployment_id=""
  [[ -n ${RH_USER:-} && -n ${RH_PASSWORD:-} ]] || return
  curl --silent --show-error --fail --user "$RH_USER:$RH_PASSWORD" \
    --header 'Accept: application/json' \
    "$staging_base/service/local/staging/profile_repositories" \
    >"$work/profile-repositories.json" || fail "Sonatype is temporarily unavailable."
  jq -e '((.data // .profileRepositories) | type == "array") and
    ((.data // .profileRepositories) | all(
      ((.repositoryId // .id) | type == "string" and length > 0) and
      (.description | type == "string")))' "$work/profile-repositories.json" >/dev/null ||
    fail "Sonatype returned an invalid profile-repository schema."
  portal_token=$(printf '%s:%s' "$RH_USER" "$RH_PASSWORD" | base64 | tr -d '\n')
  curl --silent --show-error --fail --header "Authorization: Bearer $portal_token" \
    "$staging_base/manual/search/repositories?ip=any&profile_id=io.temporal" \
    >"$work/manual-repositories.json" || fail "Portal compatibility state is unavailable."
  jq -e '(.repositories | type == "array") and
    (.repositories | all(.key | type == "string" and length > 0))' \
    "$work/manual-repositories.json" >/dev/null ||
    fail "Portal returned an invalid repository schema."
  {
    jq -r '(.data // .profileRepositories)[] | .repositoryId // .id' \
      "$work/profile-repositories.json"
    jq -r '.repositories[].key' "$work/manual-repositories.json"
  } | sort -u >"$work/sonatype-repository-ids.txt"
  : >"$work/exact-repository-ids.txt"
  unknown_automated_repository=false
  while IFS= read -r candidate_repository; do
    [[ -n $candidate_repository ]] || continue
    description=$(jq -r --arg id "$candidate_repository" \
      '[(.data // .profileRepositories)[] |
        select((.repositoryId // .id) == $id) | .description] | first // ""' \
      "$work/profile-repositories.json")
    candidate_pom="$work/repository-$candidate_repository.pom"
    candidate_status=$(curl --silent --show-error --location --output "$candidate_pom" \
      --write-out '%{http_code}' --user "$RH_USER:$RH_PASSWORD" \
      "$staging_base/service/local/repositories/$candidate_repository/content/io/temporal/temporal-sdk/$version/temporal-sdk-$version.pom") ||
      fail "Unable to inspect Sonatype repository $candidate_repository."
    case "$candidate_status" in
      200)
        candidate_identity=$(python3 - "$candidate_pom" <<'PY'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
ns = root.tag.partition("}")[0] + "}" if root.tag.startswith("{") else ""
print("\t".join((root.findtext(f"{ns}groupId", "").strip(),
                 root.findtext(f"{ns}artifactId", "").strip(),
                 root.findtext(f"{ns}version", "").strip(),
                 root.findtext(f"{ns}scm/{ns}tag", "").strip().lower())))
PY
)
        IFS=$'\t' read -r candidate_group candidate_artifact candidate_version \
          candidate_commit <<<"$candidate_identity"
        if [[ $candidate_group == io.temporal && $candidate_artifact == temporal-sdk &&
          $candidate_version == "$version" ]]; then
          [[ $candidate_commit == "$RELEASE_COMMIT" ]] ||
            conflict "an active Sonatype repository contains this version for another SHA."
          printf '%s\n' "$candidate_repository" >>"$work/exact-repository-ids.txt"
        fi
        ;;
      404)
        manual_record_count=$(jq --arg id "$candidate_repository" \
          '[.repositories[] | select(.key == $id)] | length' "$work/manual-repositories.json")
        if [[ $description == "$manual_description" ]]; then
          printf '%s\n' "$candidate_repository" >>"$work/exact-repository-ids.txt"
        elif [[ $description == sdk-java:* || $manual_record_count -gt 0 ]]; then
          unknown_automated_repository=true
        fi
        ;;
      *) fail "Sonatype returned HTTP $candidate_status for repository inspection." ;;
    esac
  done <"$work/sonatype-repository-ids.txt"
  mapfile -t matches < <(sort -u "$work/exact-repository-ids.txt")
  [[ ${#matches[@]} -le 1 ]] ||
    conflict "multiple Sonatype repositories contain the exact release coordinates."
  [[ ${#matches[@]} -eq 0 ]] || exact_repository_id=${matches[0]}
  if [[ -z $exact_repository_id && $unknown_automated_repository == true && $missing -gt 0 ]]; then
    fail "An automated sdk-java repository is still active but not yet identifiable; fallback submission is blocked."
  fi
  if [[ -n $exact_repository_id ]]; then
    exact_repository_state=$(jq -r --arg id "$exact_repository_id" \
      '[.repositories[] | select(.key == $id) | .state] | first // "open"' \
      "$work/manual-repositories.json")
    portal_deployment_id=$(jq -r --arg id "$exact_repository_id" \
      '[.repositories[] | select(.key == $id) | .portal_deployment_id] | first // ""' \
      "$work/manual-repositories.json")
  fi
}

github_state() {
  tag_state=absent
  tag_json="$work/tag.json"
  status=$(curl --silent --show-error --location --output "$tag_json" --write-out '%{http_code}' \
    --header "Authorization: Bearer $GH_TOKEN" --header 'Accept: application/vnd.github+json' \
    "https://api.github.com/repos/temporalio/sdk-java/git/ref/tags/$RELEASE_TAG") ||
    fail "GitHub is temporarily unavailable."
  if [[ $status == 200 ]]; then
    jq -e --arg sha "$RELEASE_COMMIT" '.object.type == "commit" and .object.sha == $sha' \
      "$tag_json" >/dev/null || conflict "the release tag points at another object."
    tag_state=exact
  elif [[ $status != 404 ]]; then
    fail "GitHub returned HTTP $status while reading the tag."
  fi
  releases=$(gh api --paginate --slurp 'repos/temporalio/sdk-java/releases?per_page=100') ||
    fail "GitHub releases are temporarily unavailable."
  release=$(jq -c --arg tag "$RELEASE_TAG" \
    '[.[][] | select(.tag_name == $tag)] | first // empty' <<<"$releases")
  release_state=absent
  if [[ -n $release ]]; then
    prerelease=false; [[ $RELEASE_TAG == *-RC* ]] && prerelease=true
    draft=$(jq -r .draft <<<"$release")
    jq -e --arg tag "$RELEASE_TAG" --arg sha "$RELEASE_COMMIT" --rawfile notes "$notes" \
      --argjson prerelease "$prerelease" \
      '.tag_name == $tag and .name == $tag and .target_commitish == $sha and
       .body == $notes and .prerelease == $prerelease' <<<"$release" >/dev/null ||
      conflict "the GitHub release has another immutable identity."
    asset_count=$(jq '.assets | length' <<<"$release")
    release_state=$([[ $draft == true ]] && echo "draft-$asset_count-assets" ||
      echo "public-$asset_count-assets")
  fi
}

central_state
sonatype_state
github_state
jq -n --arg action "$MANUAL_RELEASE_ACTION" --arg tag "$RELEASE_TAG" \
  --arg commitSha "$RELEASE_COMMIT" --arg notesSha256 "$notes_sha256" --arg policy "$policy" \
  --argjson mavenPresent "$present" --argjson mavenMissing "$missing" \
  --arg repositoryId "$exact_repository_id" --arg repositoryState "$exact_repository_state" \
  --arg portalDeploymentId "$portal_deployment_id" --arg tagState "$tag_state" \
  --arg releaseState "$release_state" \
  '{action:$action,tag:$tag,commitSha:$commitSha,notesSha256:$notesSha256,
    mavenPolicy:$policy,mavenPresent:$mavenPresent,mavenMissing:$mavenMissing,
    sonatypeRepositoryId:$repositoryId,sonatypeRepositoryState:$repositoryState,
    portalDeploymentId:$portalDeploymentId,tagState:$tagState,releaseState:$releaseState}' \
  >"${MANUAL_INSPECTION_FILE:-$work/inspection.json}"
[[ $MANUAL_RELEASE_ACTION == resume ]] || exit 0

for name in JAR_SIGNING_KEY JAR_SIGNING_KEY_ID JAR_SIGNING_KEY_PASSWORD RH_USER RH_PASSWORD; do
  [[ -n ${!name:-} ]] || fail "$name is required for fallback publication."
done

[[ -n ${MANUAL_ASSET_DIR:-} && -d $MANUAL_ASSET_DIR ]] ||
  fail "The independent seven-asset directory is missing."
mapfile -t expected_assets < <(find "$MANUAL_ASSET_DIR" -mindepth 1 -maxdepth 1 -type f \
  -exec basename {} \; | sort)
[[ ${#expected_assets[@]} -eq 7 && "${expected_assets[*]}" == *"SHA256SUMS"* ]] ||
  conflict "the fallback did not produce the fixed seven-asset set."
asset_manifest="$work/manual-assets.tsv"
: >"$asset_manifest"
for asset in "${expected_assets[@]}"; do
  printf '%s\t%s\t%s\n' "$(sha256sum "$MANUAL_ASSET_DIR/$asset" | awk '{print $1}')" \
    "$(wc -c <"$MANUAL_ASSET_DIR/$asset" | tr -d ' ')" "$asset" >>"$asset_manifest"
done
asset_manifest_sha256=$(sha256sum "$asset_manifest" | awk '{print $1}')
manual_payload_receipt_key="sdk-java/manual/$RELEASE_TAG/$RELEASE_COMMIT/maven-payload.json"
manual_payload_receipt="$work/manual-maven-payload-receipt.json"
manual_payload_archive="$work/manual-maven-payload.tar"
payload_root="$work/manual-maven-payload"
payload_manifest="$work/manual-maven-payload.tsv"
payload_frozen=false
if aws s3api head-object --bucket "$RELEASE_ARTIFACT_BUCKET" \
  --key "$manual_payload_receipt_key" >/dev/null 2>&1; then
  aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$manual_payload_receipt_key" \
    "$manual_payload_receipt" --no-progress >/dev/null
  manual_payload_archive_key=$(jq -er --arg tag "$RELEASE_TAG" --arg commit "$RELEASE_COMMIT" \
    '.tag == $tag and .commitSha == $commit and
     (.archiveSha256 | test("^[0-9a-f]{64}$")) and
     (.manifestSha256 | test("^[0-9a-f]{64}$")) and .archiveKey' \
    "$manual_payload_receipt") || conflict "the frozen fallback Maven receipt differs."
  aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$manual_payload_archive_key" \
    "$manual_payload_archive" --no-progress >/dev/null
  actual_archive_sha=$(sha256sum "$manual_payload_archive" | awk '{print $1}')
  expected_archive_sha=$(jq -er .archiveSha256 "$manual_payload_receipt")
  [[ $actual_archive_sha == "$expected_archive_sha" ]] ||
    conflict "the frozen fallback Maven archive checksum differs."
  mkdir -p "$payload_root"
  tar -xf "$manual_payload_archive" -C "$work"
  actual_manifest_sha=$(sha256sum "$payload_manifest" | awk '{print $1}')
  expected_manifest_sha=$(jq -er .manifestSha256 "$manual_payload_receipt")
  [[ $actual_manifest_sha == "$expected_manifest_sha" ]] ||
    conflict "the frozen fallback Maven manifest checksum differs."
  payload_frozen=true
fi
if [[ -n $release ]]; then
  while IFS=$'\t' read -r remote_name remote_state remote_size; do
    printf '%s\n' "${expected_assets[@]}" | grep -Fxq "$remote_name" ||
      conflict "the existing release contains unexpected asset $remote_name."
    if [[ $draft == true && $remote_state == starter && $remote_size == 0 ]]; then
      continue
    fi
    gh release download "$RELEASE_TAG" --repo temporalio/sdk-java --pattern "$remote_name" \
      --dir "$work" --clobber >/dev/null ||
      fail "Unable to inspect existing fallback asset $remote_name."
    cmp -s "$work/$remote_name" "$MANUAL_ASSET_DIR/$remote_name" ||
      conflict "the existing release asset $remote_name differs from the frozen set."
  done < <(jq -r '.assets[] | [.name,.state,.size] | @tsv' <<<"$release")
  if [[ $draft == false && $(jq '.assets | length' <<<"$release") -ne 7 ]]; then
    conflict "the public fallback release is incomplete."
  fi
fi

verify_frozen_payload_files() {
  [[ $payload_frozen == true && -s $payload_manifest ]] ||
    conflict "the release-wide fallback Maven payload is not durably frozen."
  while IFS=$'\t' read -r relative sha size; do
    [[ $relative =~ ^io/temporal/[A-Za-z0-9._-]+/[^/]+/[A-Za-z0-9._-]+$ &&
      $sha =~ ^[0-9a-f]{64}$ && $size =~ ^[1-9][0-9]*$ ]] ||
      conflict "the frozen fallback Maven manifest contains an invalid record."
    [[ -f $payload_root/$relative && ! -L $payload_root/$relative &&
      $(sha256sum "$payload_root/$relative" | awk '{print $1}') == "$sha" &&
      $(wc -c <"$payload_root/$relative" | tr -d ' ') == "$size" ]] ||
      conflict "the frozen fallback Maven payload differs at $relative."
  done <"$payload_manifest"
}

verify_central_payload_bytes() {
  verify_frozen_payload_files
  while IFS=$'\t' read -r relative sha size; do
    remote="$work/central-$(printf '%s' "$relative" | sha256sum | awk '{print $1}')"
    status=$(curl --silent --show-error --location --output "$remote" --write-out '%{http_code}' \
      "$central_base/$relative") || fail "Maven Central is temporarily unavailable."
    [[ $status == 200 && $(sha256sum "$remote" | awk '{print $1}') == "$sha" &&
      $(wc -c <"$remote" | tr -d ' ') == "$size" ]] ||
      conflict "Maven Central differs from the frozen fallback payload at $relative."
  done <"$payload_manifest"
  for artifact in "${maven_artifacts[@]}"; do
    directory="io/temporal/$artifact/$version"
    curl --silent --show-error --fail --location "$central_base/$directory/" \
      >"$work/manual-central-index-$artifact.html" ||
      fail "Unable to enumerate fallback Maven Central files for $artifact."
    awk -F'"' '/href="[^"]+"/ {for (i=2; i<=NF; i+=2) print $i}' \
      "$work/manual-central-index-$artifact.html" |
      sed -E 's/[?#].*$//' | awk -F/ 'NF == 1 && $0 != "" && $0 != ".." {print}' |
      sort -u >"$work/manual-central-files-$artifact.txt"
    awk -F'\t' -v prefix="$directory/" '$1 ~ ("^" prefix) {sub("^.*/", "", $1); print $1}' \
      "$payload_manifest" | sort >"$work/manual-central-required-$artifact.txt"
    python3 - "$work/manual-central-required-$artifact.txt" \
      "$work/manual-central-files-$artifact.txt" <<'PY'
import pathlib, sys
required = set(pathlib.Path(sys.argv[1]).read_text().splitlines())
actual = set(pathlib.Path(sys.argv[2]).read_text().splitlines())
allowed = set(required)
for name in required:
    allowed.update(name + suffix for suffix in (".md5", ".sha1", ".sha256", ".sha512"))
if not required <= actual or not actual <= allowed:
    raise SystemExit("fallback Maven Central file set differs")
PY
  done
}

verify_staged_fallback_repository() {
  : >"$work/manual-staging-files.txt"
  while IFS=$'\t' read -r relative sha size; do
    remote="$work/manual-staged-$(printf '%s' "$relative" | sha256sum | awk '{print $1}')"
    status=$(curl --silent --show-error --location --output "$remote" --write-out '%{http_code}' \
      --user "$RH_USER:$RH_PASSWORD" \
      "$staging_base/service/local/repositories/$exact_repository_id/content/$relative") ||
      fail "Unable to verify staged fallback file $relative."
    [[ $status == 200 && $(sha256sum "$remote" | awk '{print $1}') == "$sha" &&
      $(wc -c <"$remote" | tr -d ' ') == "$size" ]] ||
      conflict "the staged fallback Maven file $relative differs."
  done <"$payload_manifest"
  for artifact in "${maven_artifacts[@]}"; do
    directory="io/temporal/$artifact/$version"
    listing="$work/manual-staging-list-$artifact.json"
    curl --silent --show-error --fail --user "$RH_USER:$RH_PASSWORD" \
      --header 'Accept: application/json' \
      "$staging_base/service/local/repositories/$exact_repository_id/content/$directory/" \
      >"$listing" || fail "Unable to enumerate staged fallback files for $artifact."
    jq -e '(.data | type == "array")' "$listing" >/dev/null ||
      fail "Sonatype returned an invalid staged fallback listing."
    jq -r --arg directory "$directory" \
      '.data[] | select(.leaf == true) | .relativePath | ltrimstr("/") |
       if contains("/") then . else ($directory + "/" + .) end' \
      "$listing" >>"$work/manual-staging-files.txt"
  done
  cut -f1 "$payload_manifest" | sort >"$work/manual-staging-required.txt"
  sort -u "$work/manual-staging-files.txt" -o "$work/manual-staging-files.txt"
  cmp -s "$work/manual-staging-required.txt" "$work/manual-staging-files.txt" ||
    conflict "the fallback staging repository contains missing or unexpected paths."
}

reconcile_open_fallback_repository() {
  verify_frozen_payload_files
  while IFS=$'\t' read -r relative sha size; do
    remote="$work/staged-$(printf '%s' "$relative" | sha256sum | awk '{print $1}')"
    status=$(curl --silent --show-error --location --output "$remote" --write-out '%{http_code}' \
      --user "$RH_USER:$RH_PASSWORD" \
      "$staging_base/service/local/repositories/$exact_repository_id/content/$relative") ||
      fail "Unable to inspect staged fallback file $relative."
    if [[ $status == 404 ]]; then
      curl --silent --show-error --fail --user "$RH_USER:$RH_PASSWORD" \
        --upload-file "$payload_root/$relative" \
        "$staging_base/service/local/staging/deployByRepositoryId/$exact_repository_id/$relative" \
        >/dev/null || fail "Unable to upload fallback Maven file $relative."
    elif [[ $status != 200 || $(sha256sum "$remote" | awk '{print $1}') != "$sha" ||
      $(wc -c <"$remote" | tr -d ' ') != "$size" ]]; then
      conflict "the staged fallback Maven file $relative differs."
    fi
  done <"$payload_manifest"
  verify_staged_fallback_repository
  close_body="$work/manual-close.json"
  jq -n --arg id "$exact_repository_id" --arg description "$manual_description" \
    '{data:{stagedRepositoryIds:[$id],description:$description}}' >"$close_body"
  close_status=$(curl --silent --show-error --output "$work/manual-close-response" \
    --write-out '%{http_code}' --request POST --user "$RH_USER:$RH_PASSWORD" \
    --header 'Content-Type: application/json' --data-binary "@$close_body" \
    "$staging_base/service/local/staging/bulk/close") ||
    fail "Unable to close the exact fallback repository."
  case "$close_status" in 200 | 201 | 202 | 204) ;; *)
    fail "Sonatype returned HTTP $close_status while closing the fallback repository." ;;
  esac
}

if (( missing > 0 )); then
  if [[ -z $portal_deployment_id && -n $exact_repository_id ]]; then
    if [[ $exact_repository_state == open ]]; then
      reconcile_open_fallback_repository
    else
      [[ $exact_repository_state == closed || $exact_repository_state == released ]] ||
        conflict "the exact fallback repository has an unsupported state."
      verify_frozen_payload_files
    fi
  elif [[ -z $portal_deployment_id ]]; then
    build_backup="$work/build.gradle"
    publishing_backup="$work/publishing.gradle"
    cp build.gradle "$build_backup"
    cp gradle/publishing.gradle "$publishing_backup"
    restore_source() {
      cp "$build_backup" build.gradle
      cp "$publishing_backup" gradle/publishing.gradle
    }
    trap 'restore_source; rm -rf "$work"' EXIT
    if git rev-parse --verify "refs/tags/$RELEASE_TAG" >/dev/null 2>&1; then
      [[ $(git rev-list -n1 "$RELEASE_TAG") == "$RELEASE_COMMIT" ]] ||
        conflict "the local release tag points at another commit."
    else
      git tag "$RELEASE_TAG" "$RELEASE_COMMIT"
    fi
    python3 - build.gradle gradle/publishing.gradle "$manual_description" "$RELEASE_COMMIT" <<'PY'
import pathlib, re, sys
build = pathlib.Path(sys.argv[1])
publishing = pathlib.Path(sys.argv[2])
description = sys.argv[3]
commit = sys.argv[4]
source = build.read_text()
matches = list(re.finditer(r"id ['\"]io\.github\.gradle-nexus\.publish-plugin['\"] version ['\"][^'\"]+['\"]", source))
if len(matches) != 1:
    raise SystemExit("Expected one Nexus publish plugin declaration")
source = source[:matches[0].start()] + "id 'io.github.gradle-nexus.publish-plugin' version '1.3.0'" + source[matches[0].end():]
build.write_text(source)
source = publishing.read_text()
source = source.replace("nexusPublishing {", f"nexusPublishing {{\n    repositoryDescription = '{description}'", 1)
needle = "url = 'https://github.com/temporalio/sdk-java.git'"
if source.count(needle) != 1:
    raise SystemExit("Expected one sdk-java SCM URL")
source = source.replace(needle, needle + f"\n                    tag = '{commit}'", 1)
password = "password = project.hasProperty('ossrhPassword') ? project.property('ossrhPassword') : ''"
if source.count(password) != 1:
    raise SystemExit("Expected one Sonatype password declaration")
source = source.replace(password, password + "\n            nexusUrl.set(uri('https://ossrh-staging-api.central.sonatype.com/service/local/'))", 1)
publishing.write_text(source)
PY
    signing_gradle_home="$work/signing-gradle-home"
    sonatype_gradle_home="$work/sonatype-gradle-home"
    mkdir -p "$signing_gradle_home" "$sonatype_gradle_home" "$work/gnupg"
    {
      printf 'ossrhUsername = %s\n' "$RH_USER"
      printf 'ossrhPassword = %s\n' "$RH_PASSWORD"
    } >"$sonatype_gradle_home/gradle.properties"
    if [[ $payload_frozen == false ]]; then
      signing_key="$work/gnupg/secring.gpg"
      printf '%s' "$JAR_SIGNING_KEY" | base64 --decode >"$signing_key"
      {
        printf 'signing.keyId = %s\n' "$JAR_SIGNING_KEY_ID"
        printf 'signing.password = %s\n' "$JAR_SIGNING_KEY_PASSWORD"
        printf 'signing.secretKeyRingFile = %s\n' "$signing_key"
      } >"$signing_gradle_home/gradle.properties"
      generated_payload_root="$work/manual-generated-maven-local"
      mkdir -p "$generated_payload_root" "$payload_root/io/temporal"
      run_signing_gradle --no-daemon "-Dmaven.repo.local=$generated_payload_root" \
        "-PreleaseVersion=$version" "-PreleaseCommit=$RELEASE_COMMIT" publishToMavenLocal >&2
      for artifact in "${maven_artifacts[@]}"; do
        generated_artifact="$generated_payload_root/io/temporal/$artifact/$version"
        [[ -d $generated_artifact ]] ||
          conflict "Gradle did not generate fallback payload for $artifact."
        mkdir -p "$payload_root/io/temporal/$artifact"
        cp -R "$generated_artifact" "$payload_root/io/temporal/$artifact/$version"
      done
      printf '%s\n' "${maven_artifacts[@]}" >"$work/approved-manual-artifacts.txt"
      python3 - "$payload_root" "$work/approved-manual-artifacts.txt" "$version" \
        "$RELEASE_COMMIT" <<'PY'
import pathlib, re, sys, xml.etree.ElementTree as ET
root = pathlib.Path(sys.argv[1])
approved = set(pathlib.Path(sys.argv[2]).read_text().splitlines())
version, commit = sys.argv[3:]
seen = set()
for path in root.rglob("*"):
    if not path.is_file():
        continue
    relative = path.relative_to(root).parts
    if len(relative) != 5 or relative[:2] != ("io", "temporal"):
        raise SystemExit("manual Maven payload escaped fixed coordinates")
    artifact, found_version, filename = relative[2:]
    if artifact not in approved or found_version != version:
        raise SystemExit("manual Maven payload contains an unapproved coordinate")
    escaped = re.escape(f"{artifact}-{version}")
    if not re.fullmatch(escaped + r"(?:-(?:sources|javadoc))?\.(?:jar|pom|module)(?:\.asc)?", filename):
        raise SystemExit("manual Maven payload contains an unapproved filename")
    seen.add(artifact)
if seen != approved:
    raise SystemExit("manual Maven payload does not contain the fixed module set")
for artifact in approved:
    directory = root / "io" / "temporal" / artifact / version
    pom = directory / f"{artifact}-{version}.pom"
    signature = directory / f"{artifact}-{version}.pom.asc"
    expected = {f"{artifact}-{version}.pom", f"{artifact}-{version}.pom.asc",
                f"{artifact}-{version}.module", f"{artifact}-{version}.module.asc"}
    if artifact != "temporal-bom":
        expected.update({f"{artifact}-{version}.jar", f"{artifact}-{version}.jar.asc",
                         f"{artifact}-{version}-sources.jar",
                         f"{artifact}-{version}-sources.jar.asc",
                         f"{artifact}-{version}-javadoc.jar",
                         f"{artifact}-{version}-javadoc.jar.asc"})
    if {path.name for path in directory.iterdir() if path.is_file()} != expected:
        raise SystemExit(f"manual Maven payload file set differs for {artifact}")
    if not pom.is_file() or not signature.is_file():
        raise SystemExit(f"manual Maven payload lacks signed POM for {artifact}")
    document = ET.parse(pom).getroot()
    ns = document.tag.partition("}")[0] + "}" if document.tag.startswith("{") else ""
    values = (document.findtext(f"{ns}groupId", "").strip(),
              document.findtext(f"{ns}artifactId", "").strip(),
              document.findtext(f"{ns}version", "").strip(),
              document.findtext(f"{ns}scm/{ns}tag", "").strip().lower())
    if values != ("io.temporal", artifact, version, commit):
        raise SystemExit(f"manual generated POM identity differs for {artifact}")
PY
      : >"$payload_manifest"
      while IFS= read -r -d '' payload; do
        relative=${payload#"$payload_root/"}
        printf '%s\t%s\t%s\n' "$relative" "$(sha256sum "$payload" | awk '{print $1}')" \
          "$(wc -c <"$payload" | tr -d ' ')" >>"$payload_manifest"
      done < <(find "$payload_root/io/temporal" -type f -print0 | sort -z)
      tar --sort=name --mtime='UTC 1970-01-01' --owner=0 --group=0 --numeric-owner \
        -cf "$manual_payload_archive" -C "$work" manual-maven-payload manual-maven-payload.tsv
      archive_sha=$(sha256sum "$manual_payload_archive" | awk '{print $1}')
      manifest_sha=$(sha256sum "$payload_manifest" | awk '{print $1}')
      manual_payload_archive_key="sdk-java/manual/$RELEASE_TAG/$RELEASE_COMMIT/payloads/$archive_sha.tar"
      if ! aws s3api put-object --bucket "$RELEASE_ARTIFACT_BUCKET" \
        --key "$manual_payload_archive_key" --body "$manual_payload_archive" \
        --if-none-match '*' >/dev/null 2>&1; then
        aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$manual_payload_archive_key" \
          "$work/existing-manual-payload.tar" --no-progress >/dev/null
        cmp -s "$manual_payload_archive" "$work/existing-manual-payload.tar" ||
          conflict "the content-addressed fallback Maven payload differs."
      fi
      jq -n --arg tag "$RELEASE_TAG" --arg commitSha "$RELEASE_COMMIT" \
        --arg archiveKey "$manual_payload_archive_key" --arg archiveSha256 "$archive_sha" \
        --arg manifestSha256 "$manifest_sha" \
        '{tag:$tag,commitSha:$commitSha,archiveKey:$archiveKey,
          archiveSha256:$archiveSha256,manifestSha256:$manifestSha256}' \
        >"$manual_payload_receipt"
      if ! aws s3api put-object --bucket "$RELEASE_ARTIFACT_BUCKET" \
        --key "$manual_payload_receipt_key" --body "$manual_payload_receipt" \
        --if-none-match '*' >/dev/null 2>&1; then
        aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$manual_payload_receipt_key" \
          "$work/existing-manual-payload-receipt.json" --no-progress >/dev/null
        cmp -s "$manual_payload_receipt" "$work/existing-manual-payload-receipt.json" ||
          conflict "the fallback Maven payload was already frozen with different bytes."
      fi
      payload_frozen=true
    fi
    publish_log="$work/manual-initialize.log"
    run_sonatype_gradle --no-daemon \
      "-PreleaseVersion=$version" "-PreleaseCommit=$RELEASE_COMMIT" \
      initializeSonatypeStagingRepository 2>&1 | tee "$publish_log" >&2
    created_repository=$(sed -n -E "s/.*Created staging repository '([^']+)'.*/\1/p" \
      "$publish_log" | tail -1)
    [[ -n $created_repository ]] ||
      fail "Unable to capture the independent Sonatype repository ID."
    while IFS= read -r -d '' payload; do
      relative=${payload#"$payload_root/"}
      curl --silent --show-error --fail --user "$RH_USER:$RH_PASSWORD" \
        --upload-file "$payload" \
        "$staging_base/service/local/staging/deployByRepositoryId/$created_repository/$relative" \
        >/dev/null || fail "Unable to upload fixed fallback payload $relative."
    done < <(find "$payload_root/io/temporal" -type f -print0 | sort -z)
    exact_repository_id=$created_repository
    verify_staged_fallback_repository
    close_body="$work/manual-close.json"
    jq -n --arg id "$created_repository" --arg description "$manual_description" \
      '{data:{stagedRepositoryIds:[$id],description:$description}}' >"$close_body"
    close_status=$(curl --silent --show-error --output "$work/manual-close-response" \
      --write-out '%{http_code}' --request POST --user "$RH_USER:$RH_PASSWORD" \
      --header 'Content-Type: application/json' --data-binary "@$close_body" \
      "$staging_base/service/local/staging/bulk/close") ||
      fail "Unable to close the exact fallback repository."
    case "$close_status" in 200 | 201 | 202 | 204) ;; *)
      fail "Sonatype returned HTTP $close_status while closing the fallback repository." ;;
    esac
    restore_source
    trap 'rm -rf "$work"' EXIT
  fi
  deployment_state=""
  for _ in {1..90}; do
    if [[ -z $portal_deployment_id ]]; then
      sonatype_state
      if [[ -z $portal_deployment_id ]]; then
        sleep 20
        continue
      fi
    fi
    deployment_state=$(curl --silent --show-error --fail --request POST \
      --header "Authorization: Bearer $portal_token" \
      "https://central.sonatype.com/api/v1/publisher/status?id=$portal_deployment_id" |
      jq -er --arg id "$portal_deployment_id" 'select(.deploymentId == $id) | .deploymentState')
    case "$deployment_state" in
      VALIDATED)
        publish_status=$(curl --silent --show-error --output "$work/manual-publish-response" \
          --write-out '%{http_code}' --request POST \
          --header "Authorization: Bearer $portal_token" \
          "https://central.sonatype.com/api/v1/publisher/deployment/$portal_deployment_id")
        [[ $publish_status == 204 ]] ||
          fail "Portal returned HTTP $publish_status while publishing the fallback deployment."
        ;;
      PENDING | VALIDATING | PUBLISHING) ;;
      PUBLISHED) break ;;
      FAILED) conflict "the exact fallback Portal deployment failed validation." ;;
      *) conflict "Portal returned unsupported state $deployment_state." ;;
    esac
    sleep 20
  done
  [[ $deployment_state == PUBLISHED ]] ||
    fail "The exact fallback Portal deployment has not completed; automatic recovery will continue."
  for _ in {1..90}; do
    central_state
    (( missing == 0 )) && break
    sleep 20
  done
  (( missing == 0 )) || fail "Maven Central is not fully visible; resume later without resubmitting."
fi
verify_central_payload_bytes

verify_remote_assets() {
  local download="$work/existing-assets"
  rm -rf "$download"
  mkdir "$download"
  gh release download "$RELEASE_TAG" --repo temporalio/sdk-java --dir "$download" >/dev/null ||
    fail "Unable to download the existing release assets."
  mapfile -t downloaded < <(find "$download" -mindepth 1 -maxdepth 1 -type f \
    -exec basename {} \; | sort)
  [[ ${downloaded[*]} == "${expected_assets[*]}" ]] ||
    conflict "the existing release asset names differ from the frozen manifest."
  for asset in "${expected_assets[@]}"; do
    cmp -s "$download/$asset" "$MANUAL_ASSET_DIR/$asset" ||
      conflict "the existing release asset $asset differs from the frozen manifest."
  done
  (cd "$download" && sha256sum --check SHA256SUMS) ||
    conflict "the existing release checksums are not self-consistent."
}

if [[ $tag_state == absent ]]; then
  gh api --method POST repos/temporalio/sdk-java/git/refs \
    --raw-field ref="refs/tags/$RELEASE_TAG" --raw-field sha="$RELEASE_COMMIT" >/dev/null
fi
github_state
if [[ -z $release ]]; then
  args=(release create "$RELEASE_TAG" --repo temporalio/sdk-java --draft --target "$RELEASE_COMMIT"
    --title "$RELEASE_TAG" --notes-file "$notes")
  [[ $RELEASE_TAG == *-RC* ]] && args+=(--prerelease)
  gh "${args[@]}" >/dev/null
  github_state
fi
release_is_draft=$(jq -r .draft <<<"$release")
mapfile -t remote_assets < <(jq -r '.assets[].name' <<<"$release" | sort)
for remote in "${remote_assets[@]}"; do
  printf '%s\n' "${expected_assets[@]}" | grep -Fxq "$remote" ||
    conflict "the draft contains unexpected asset $remote."
done
if [[ $release_is_draft == false ]]; then
  [[ ${remote_assets[*]} == "${expected_assets[*]}" ]] ||
    conflict "the public release does not have the exact frozen asset set."
  verify_remote_assets
  exit 0
fi
while IFS=$'\t' read -r id state size; do
  if [[ $state == starter && $size == 0 ]]; then
    gh api --method DELETE "repos/temporalio/sdk-java/releases/assets/$id" >/dev/null
  fi
done < <(jq -r '.assets[] | [.id,.state,.size] | @tsv' <<<"$release")
github_state
mapfile -t remote_assets < <(jq -r '.assets[].name' <<<"$release" | sort)
for asset in "${expected_assets[@]}"; do
  if ! printf '%s\n' "${remote_assets[@]}" | grep -Fxq "$asset"; then
    gh release upload "$RELEASE_TAG" "$MANUAL_ASSET_DIR/$asset" \
      --repo temporalio/sdk-java >/dev/null
  fi
done
github_state
[[ $(jq '.assets | length' <<<"$release") == 7 ]] ||
  fail "The exact seven assets are not visible yet."
verify_remote_assets
central_state
(( missing == 0 )) || fail "Maven Central became incomplete before GitHub publication."
verify_central_payload_bytes
github_state
verify_remote_assets
gh release edit "$RELEASE_TAG" --repo temporalio/sdk-java --draft=false >/dev/null
github_state
[[ $(jq -r .draft <<<"$release") == false ]] ||
  fail "GitHub has not made the exact fallback release public."
verify_remote_assets
