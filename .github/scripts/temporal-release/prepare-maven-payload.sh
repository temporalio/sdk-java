#!/usr/bin/env bash

set -euo pipefail

fail() { echo "prepare-maven-payload: $*" >&2; exit 1; }
conflict() { echo "prepare-maven-payload: immutable payload conflict: $*" >&2; exit 42; }

required=(
  JAR_SIGNING_KEY JAR_SIGNING_KEY_ID JAR_SIGNING_KEY_PASSWORD MAVEN_ARTIFACTS_FILE
  MAVEN_PAYLOAD_COMMIT MAVEN_PAYLOAD_OUTPUT MAVEN_PAYLOAD_RELEASE_DIGEST MAVEN_PAYLOAD_VERSION
  TRUSTED_AUTOMATION_COMMIT TRUSTED_AUTOMATION_ROOT
)
for variable in "${required[@]}"; do
  [[ -n ${!variable:-} ]] || fail "Required value $variable is missing."
done
[[ $MAVEN_PAYLOAD_COMMIT =~ ^[0-9a-f]{40}$ &&
  $MAVEN_PAYLOAD_RELEASE_DIGEST =~ ^[0-9a-f]{64}$ &&
  $MAVEN_PAYLOAD_VERSION =~ ^[0-9]+\.[0-9]+\.[0-9]+(-RC[0-9]+)?$ ]] ||
  conflict "the immutable Maven identity is invalid."
[[ $(git rev-parse --verify HEAD^{commit}) == "$MAVEN_PAYLOAD_COMMIT" ]] ||
  conflict "the source checkout changed."
[[ $(git -C "$TRUSTED_AUTOMATION_ROOT" rev-parse --verify HEAD^{commit}) == \
  "$TRUSTED_AUTOMATION_COMMIT" ]] || conflict "the trusted automation checkout changed."
[[ -z $(find "$MAVEN_PAYLOAD_OUTPUT" -mindepth 1 -print -quit 2>/dev/null) ]] ||
  fail "The Maven payload output directory is not empty."

work=$(mktemp -d)
versioning_backup=$work/versioning.gradle
publishing_backup=$work/publishing.gradle
build_backup=$work/build.gradle
cp gradle/versioning.gradle "$versioning_backup"
cp gradle/publishing.gradle "$publishing_backup"
cp build.gradle "$build_backup"
restore() {
  cp "$versioning_backup" gradle/versioning.gradle
  cp "$publishing_backup" gradle/publishing.gradle
  cp "$build_backup" build.gradle
  rm -rf "$work"
}
trap restore EXIT
cp "$TRUSTED_AUTOMATION_ROOT/gradle/versioning.gradle" gradle/versioning.gradle
cp "$TRUSTED_AUTOMATION_ROOT/gradle/publishing.gradle" gradle/publishing.gradle
python3 - build.gradle <<'PY' || conflict "the trusted Gradle hooks do not match sdk-java."
import pathlib, re, sys
path = pathlib.Path(sys.argv[1])
source = path.read_text()
matches = list(re.finditer(r"id ['\"]io\.github\.gradle-nexus\.publish-plugin['\"] version ['\"][^'\"]+['\"]", source))
if len(matches) != 1:
    raise SystemExit("Expected exactly one Gradle Nexus publish plugin declaration")
source = source[:matches[0].start()] + "id 'io.github.gradle-nexus.publish-plugin' version '1.3.0'" + source[matches[0].end():]
path.write_text(source)
PY

generated=$work/generated
bundle=$work/bundle
repository=$bundle/repository
manifest=$bundle/manifest.tsv
mkdir -p "$generated" "$repository/io/temporal" "$MAVEN_PAYLOAD_OUTPUT"
MAVEN_PAYLOAD_OUTPUT=$generated \
  "$TRUSTED_AUTOMATION_ROOT/.github/scripts/temporal-release/build-and-sign-maven-payload.sh"
mapfile -t artifacts < <(jq -er '.[]' "$MAVEN_ARTIFACTS_FILE")
[[ ${#artifacts[@]} -gt 0 ]] || conflict "the Maven policy is empty."
for artifact in "${artifacts[@]}"; do
  source_dir=$generated/io/temporal/$artifact/$MAVEN_PAYLOAD_VERSION
  [[ -d $source_dir ]] || conflict "Gradle did not generate $artifact."
  mkdir -p "$repository/io/temporal/$artifact"
  cp -R "$source_dir" "$repository/io/temporal/$artifact/$MAVEN_PAYLOAD_VERSION"
done
: >"$manifest"
while IFS= read -r -d '' payload; do
  relative=${payload#"$repository/"}
  printf '%s\t%s\t%s\n' "$relative" "$(sha256sum "$payload" | awk '{print $1}')" \
    "$(wc -c <"$payload" | tr -d ' ')" >>"$manifest"
done < <(find "$repository/io/temporal" -type f -print0 | sort -z)
printf '%s\n' "${artifacts[@]}" >"$work/approved-artifacts.txt"
python3 - "$repository" "$manifest" "$work/approved-artifacts.txt" \
  "$MAVEN_PAYLOAD_VERSION" "$MAVEN_PAYLOAD_COMMIT" <<'PY' ||
  conflict "the frozen Maven payload violates sdk-java policy."
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
    pattern = escaped + r"(?:-(?:sources|javadoc))?\.(?:jar|pom|module)(?:\.(?:asc|md5|sha1))?"
    if not re.fullmatch(pattern, filename):
        raise SystemExit("payload contains an unapproved Maven filename")
    path = (root / relative).resolve()
    data = path.read_bytes()
    if root not in path.parents or not path.is_file() or path.is_symlink():
        raise SystemExit("payload path is not a regular file")
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
    bases = {f"{artifact}-{version}.pom", f"{artifact}-{version}.module"}
    if artifact != "temporal-bom":
        bases.update({f"{artifact}-{version}.jar", f"{artifact}-{version}-sources.jar",
                      f"{artifact}-{version}-javadoc.jar"})
    expected = set(bases)
    for base in bases:
        expected.update({base + ".asc", base + ".md5", base + ".sha1"})
    if {path.name for path in directory.iterdir() if path.is_file()} != expected:
        raise SystemExit(f"Maven payload file set differs for {artifact}")
    document = ET.parse(pom).getroot()
    ns = document.tag.partition("}")[0] + "}" if document.tag.startswith("{") else ""
    identity = (document.findtext(f"{ns}groupId", "").strip(),
                document.findtext(f"{ns}artifactId", "").strip(),
                document.findtext(f"{ns}version", "").strip(),
                document.findtext(f"{ns}scm/{ns}tag", "").strip().lower())
    if identity != ("io.temporal", artifact, version, commit):
        raise SystemExit(f"generated POM identity differs for {artifact}")
PY
tar --sort=name --mtime='UTC 1970-01-01' --owner=0 --group=0 --numeric-owner \
  -cf "$MAVEN_PAYLOAD_OUTPUT/maven-payload.tar" -C "$bundle" manifest.tsv repository
[[ -s $MAVEN_PAYLOAD_OUTPUT/maven-payload.tar ]] || fail "The Maven payload archive is empty."
restore
trap - EXIT
