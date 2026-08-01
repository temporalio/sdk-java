#!/usr/bin/env bash

# Independent dispatch-only fallback. This intentionally duplicates the small fixed sdk-java
# release policy instead of importing the Temporal Java application, its scripts, or its S3 state.
set -euo pipefail

fail() { echo "manual-release: $*" >&2; exit 1; }
conflict() { echo "manual-release: immutable conflict: $*" >&2; exit 42; }

run_source_gradle() {
  env -u GH_TOKEN -u AWS_ACCESS_KEY_ID -u AWS_SECRET_ACCESS_KEY -u AWS_SESSION_TOKEN \
    -u AWS_REGION -u AWS_DEFAULT_REGION GRADLE_USER_HOME="$gradle_home" ./gradlew "$@"
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
    MANUAL_OWNER_RUN_ID MANUAL_OWNERSHIP_ISSUE; do
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
        if [[ $description == "$manual_description" ]]; then
          printf '%s\n' "$candidate_repository" >>"$work/exact-repository-ids.txt"
        elif [[ $description == sdk-java:* ]]; then
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

if (( missing > 0 )); then
  if [[ -z $portal_deployment_id && -n $exact_repository_id ]]; then
    [[ $exact_repository_state == closed || $exact_repository_state == released ]] ||
      fail "The exact fallback repository is still open; reconcile its frozen payload before continuing."
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
    gradle_home="$work/gradle-home"
    mkdir -p "$gradle_home" "$work/gnupg"
    signing_key="$work/gnupg/secring.gpg"
    printf '%s' "$JAR_SIGNING_KEY" | base64 --decode >"$signing_key"
    {
      printf 'signing.keyId = %s\n' "$JAR_SIGNING_KEY_ID"
      printf 'signing.password = %s\n' "$JAR_SIGNING_KEY_PASSWORD"
      printf 'signing.secretKeyRingFile = %s\n' "$signing_key"
      printf 'ossrhUsername = %s\n' "$RH_USER"
      printf 'ossrhPassword = %s\n' "$RH_PASSWORD"
    } >"$gradle_home/gradle.properties"
    generated_payload_root="$work/manual-generated-maven-local"
    payload_root="$work/manual-maven-payload"
    mkdir -p "$generated_payload_root" "$payload_root/io/temporal"
    run_source_gradle --no-daemon "-Dmaven.repo.local=$generated_payload_root" \
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
    publish_log="$work/manual-initialize.log"
    run_source_gradle --no-daemon \
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
    exact_repository_id=$created_repository
    restore_source
    trap 'rm -rf "$work"' EXIT
  fi
  if [[ -z $portal_deployment_id ]]; then
    for _ in {1..90}; do
      sonatype_state
      [[ -n $portal_deployment_id ]] && break
      sleep 20
    done
    [[ -n $portal_deployment_id ]] ||
      fail "The exact fallback repository has no Portal deployment yet; resume without resubmitting."
  fi
  deployment_state=$(curl --silent --show-error --fail --request POST \
    --header "Authorization: Bearer $portal_token" \
    "https://central.sonatype.com/api/v1/publisher/status?id=$portal_deployment_id" |
    jq -er .deploymentState)
  case "$deployment_state" in
    VALIDATED)
      curl --silent --show-error --fail --request POST \
        --header "Authorization: Bearer $portal_token" \
        "https://central.sonatype.com/api/v1/publisher/deployment/$portal_deployment_id" \
        >/dev/null
      ;;
    PENDING | VALIDATING | PUBLISHING | PUBLISHED) ;;
    FAILED) conflict "the exact fallback Portal deployment failed validation." ;;
    *) conflict "Portal returned unsupported state $deployment_state." ;;
  esac
  for _ in {1..90}; do
    central_state
    (( missing == 0 )) && break
    sleep 20
  done
  (( missing == 0 )) || fail "Maven Central is not fully visible; resume later without resubmitting."
fi

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
gh release edit "$RELEASE_TAG" --repo temporalio/sdk-java --draft=false >/dev/null
