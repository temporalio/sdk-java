#!/usr/bin/env bash

set -euo pipefail

fail() { echo "validate-publication-model: $*" >&2; exit 1; }

[[ ${RELEASE_CANDIDATE_FILE:-} && -s $RELEASE_CANDIDATE_FILE ]] ||
  fail "RELEASE_CANDIDATE_FILE is missing."
[[ ${TRUSTED_AUTOMATION_ROOT:-} && -d $TRUSTED_AUTOMATION_ROOT ]] ||
  fail "TRUSTED_AUTOMATION_ROOT is missing."

version=$(jq -er '.version' "$RELEASE_CANDIDATE_FILE")
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
cp gradle/versioning.gradle "$versioning_backup"
cp gradle/publishing.gradle "$publishing_backup"
restore_hooks() {
  cp "$versioning_backup" gradle/versioning.gradle
  cp "$publishing_backup" gradle/publishing.gradle
}
trap restore_hooks EXIT
cp "$TRUSTED_AUTOMATION_ROOT/gradle/versioning.gradle" gradle/versioning.gradle
cp "$TRUSTED_AUTOMATION_ROOT/gradle/publishing.gradle" gradle/publishing.gradle

./gradlew --no-daemon "-PreleaseVersion=$version" "-PreleaseCommit=$commit" \
  -PreleaseDigest=0000000000000000000000000000000000000000000000000000000000000000 \
  -PmavenSubmissionGeneration=0 \
  generatePomFileForMavenJavaPublication >/dev/null

mapfile -t pom_files < <(find . -path '*/build/publications/mavenJava/pom-default.xml' -type f)
[[ ${#pom_files[@]} -eq ${#expected[@]} ]] ||
  fail "Generated POM count does not match the reviewed Maven policy."
pom_report="${RUNNER_TEMP:-/tmp}/sdk-java-generated-poms.tsv"
python3 - "$commit" "${pom_files[@]}" >"$pom_report" <<'PY'
import sys
import xml.etree.ElementTree as ET

commit = sys.argv[1]
for path in sys.argv[2:]:
    root = ET.parse(path).getroot()
    ns = root.tag.partition("}")[0] + "}" if root.tag.startswith("{") else ""
    artifact = root.findtext(f"{ns}artifactId", "").strip()
    tag = root.findtext(f"{ns}scm/{ns}tag", "").strip().lower()
    if tag != commit:
        raise SystemExit(f"{path} does not contain the immutable source SHA")
    print(f"{artifact}\t{path}")
PY
mapfile -t actual < <(cut -f1 "$pom_report" | sort)
mapfile -t expected_sorted < <(printf '%s\n' "${expected[@]}" | sort)
[[ ${actual[*]} == "${expected_sorted[*]}" ]] ||
  fail "Generated POM coordinates do not match the reviewed Maven policy."

restore_hooks
trap - EXIT
git diff --exit-code >/dev/null || fail "Publication-model validation modified tracked files."
