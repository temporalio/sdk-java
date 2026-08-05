#!/usr/bin/env bash

set -euo pipefail

fail() { echo "validate-publication-model: $*" >&2; exit 1; }

[[ ${RELEASE_CANDIDATE_FILE:-} && -s $RELEASE_CANDIDATE_FILE ]] ||
  fail "RELEASE_CANDIDATE_FILE is missing."
[[ ${TRUSTED_AUTOMATION_ROOT:-} && -d $TRUSTED_AUTOMATION_ROOT ]] ||
  fail "TRUSTED_AUTOMATION_ROOT is missing."

version=$(jq -er '.tag | ltrimstr("v")' "$RELEASE_CANDIDATE_FILE")
commit=$(jq -er '.commitSha' "$RELEASE_CANDIDATE_FILE")
maven_policy=$(jq -er '.mavenPolicy' "$RELEASE_CANDIDATE_FILE")
policy_output=$(mktemp)
GITHUB_OUTPUT=$policy_output "$TRUSTED_AUTOMATION_ROOT/gradlew" \
  -p "$TRUSTED_AUTOMATION_ROOT/.github/release-automation" --no-daemon run \
  --args="maven-policy $PWD/settings.gradle" >/dev/null
[[ $(awk -F= '$1 == "maven_policy" {print $2}' "$policy_output" | tail -1) == "$maven_policy" ]] ||
  fail "The candidate Maven policy differs from the immutable source."
mapfile -t expected < <(
  awk -F= '$1 == "maven_artifacts_json" {sub(/^[^=]*=/, ""); print}' "$policy_output" |
    jq -er '.[]'
)

versioning_backup=$(mktemp)
publishing_backup=$(mktemp)
build_backup=$(mktemp)
cp gradle/versioning.gradle "$versioning_backup"
cp gradle/publishing.gradle "$publishing_backup"
cp build.gradle "$build_backup"
restore_hooks() {
  cp "$versioning_backup" gradle/versioning.gradle
  cp "$publishing_backup" gradle/publishing.gradle
  cp "$build_backup" build.gradle
}
trap restore_hooks EXIT
if [[ $TRUSTED_AUTOMATION_ROOT/gradle/versioning.gradle != "$PWD/gradle/versioning.gradle" ]]; then
  cp "$TRUSTED_AUTOMATION_ROOT/gradle/versioning.gradle" gradle/versioning.gradle
fi
if [[ $TRUSTED_AUTOMATION_ROOT/gradle/publishing.gradle != "$PWD/gradle/publishing.gradle" ]]; then
  cp "$TRUSTED_AUTOMATION_ROOT/gradle/publishing.gradle" gradle/publishing.gradle
fi
python3 - build.gradle <<'PY'
import pathlib, re, sys
path = pathlib.Path(sys.argv[1])
source = path.read_text()
matches = list(re.finditer(r"id ['\"]io\.github\.gradle-nexus\.publish-plugin['\"] version ['\"][^'\"]+['\"]", source))
if len(matches) != 1:
    raise SystemExit("Expected exactly one Gradle Nexus publish plugin declaration")
source = source[:matches[0].start()] + "id 'io.github.gradle-nexus.publish-plugin' version '1.3.0'" + source[matches[0].end():]
path.write_text(source)
PY

./gradlew --no-daemon "-PreleaseVersion=$version" "-PreleaseCommit=$commit" \
  -PreleaseDigest=0000000000000000000000000000000000000000000000000000000000000000 \
  -PmavenSubmissionGeneration=0 tasks --all >"${RUNNER_TEMP:-/tmp}/sdk-java-release-tasks.txt"
for task in initializeSonatypeStagingRepository findSonatypeStagingRepository \
  closeSonatypeStagingRepository; do
  grep -Eq "^$task([[:space:]]|$)" "${RUNNER_TEMP:-/tmp}/sdk-java-release-tasks.txt" ||
    fail "Required trusted publication task $task is unavailable."
done
for artifact in "${expected[@]}"; do
  grep -Eq "^$artifact:publishToSonatype([[:space:]]|$)" \
    "${RUNNER_TEMP:-/tmp}/sdk-java-release-tasks.txt" ||
    fail "Required trusted publication task $artifact:publishToSonatype is unavailable."
done

./gradlew --no-daemon "-PreleaseVersion=$version" "-PreleaseCommit=$commit" \
  -PreleaseDigest=0000000000000000000000000000000000000000000000000000000000000000 \
  -PmavenSubmissionGeneration=0 \
  generatePomFileForMavenJavaPublication >/dev/null

mapfile -t pom_files < <(find . -path '*/build/publications/mavenJava/pom-default.xml' -type f)
[[ ${#pom_files[@]} -eq ${#expected[@]} ]] ||
  fail "Generated POM count does not match the reviewed Maven policy."
pom_report="${RUNNER_TEMP:-/tmp}/sdk-java-generated-poms.tsv"
python3 - "$commit" "$version" "${pom_files[@]}" >"$pom_report" <<'PY'
import sys
import xml.etree.ElementTree as ET

commit = sys.argv[1]
version = sys.argv[2]
for path in sys.argv[3:]:
    root = ET.parse(path).getroot()
    ns = root.tag.partition("}")[0] + "}" if root.tag.startswith("{") else ""
    artifact = root.findtext(f"{ns}artifactId", "").strip()
    group = root.findtext(f"{ns}groupId", "").strip()
    actual_version = root.findtext(f"{ns}version", "").strip()
    tag = root.findtext(f"{ns}scm/{ns}tag", "").strip().lower()
    if group != "io.temporal" or actual_version != version or tag != commit:
        raise SystemExit(f"{path} does not contain exact group, version, and source SHA")
    print(f"{artifact}\t{path}")
PY
mapfile -t actual < <(cut -f1 "$pom_report" | sort)
mapfile -t expected_sorted < <(printf '%s\n' "${expected[@]}" | sort)
[[ ${actual[*]} == "${expected_sorted[*]}" ]] ||
  fail "Generated POM coordinates do not match the reviewed Maven policy."

restore_hooks
trap - EXIT
cmp -s "$versioning_backup" gradle/versioning.gradle &&
  cmp -s "$publishing_backup" gradle/publishing.gradle &&
  cmp -s "$build_backup" build.gradle ||
  fail "Publication-model validation did not restore the trusted publication hooks."
