#!/usr/bin/env bash

set -euo pipefail

# Report a transient or operational payload-build failure.
fail() { echo "prepare-maven-payload: $*" >&2; exit 1; }
# Report a mismatch in immutable source, policy, or generated payload identity.
conflict() { echo "prepare-maven-payload: immutable payload conflict: $*" >&2; exit 42; }

required=(
  JAR_SIGNING_KEY JAR_SIGNING_KEY_ID JAR_SIGNING_KEY_PASSWORD MAVEN_ARTIFACTS_JSON
  MAVEN_PAYLOAD_COMMIT MAVEN_PAYLOAD_OUTPUT MAVEN_PAYLOAD_VERSION
  TRUSTED_AUTOMATION_COMMIT TRUSTED_AUTOMATION_ROOT
)
for variable in "${required[@]}"; do
  [[ -n ${!variable:-} ]] || fail "Required value $variable is missing."
done
[[ $MAVEN_PAYLOAD_COMMIT =~ ^[0-9a-f]{40}$ &&
  $MAVEN_PAYLOAD_VERSION =~ ^[0-9]+\.[0-9]+\.[0-9]+(-RC[0-9]+)?$ ]] ||
  conflict "the immutable Maven identity is invalid."
[[ $(git rev-parse --verify HEAD^{commit}) == "$MAVEN_PAYLOAD_COMMIT" ]] ||
  conflict "the source checkout changed."
[[ $(git -C "$TRUSTED_AUTOMATION_ROOT" rev-parse --verify HEAD^{commit}) == \
  "$TRUSTED_AUTOMATION_COMMIT" ]] || conflict "the trusted automation checkout changed."
[[ -z $(find "$MAVEN_PAYLOAD_OUTPUT" -mindepth 1 -print -quit 2>/dev/null) ]] ||
  fail "The Maven payload output directory is not empty."

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# Build in an isolated container, then sign only the generated Maven repository on the host.
# The untrusted candidate build receives no signing key. Signing happens afterward in the
# protected job, and the complete signed tree is validated before it becomes an artifact.
build_and_sign() {
  local image sandbox=$work/sandbox gnupg=$work/gnupg key_file=$work/key
  image='eclipse-temurin:17-jdk@sha256:91b6210cce02091f6f0798a83ec51aa223828242c5a21a85793bb8c28dc891c4'
  mkdir -p "$sandbox/gradle" "$sandbox/home" "$sandbox/source" "$MAVEN_PAYLOAD_OUTPUT"
  mkdir "$gnupg"
  chmod 0700 "$gnupg"
  cp -a "$PWD/." "$sandbox/source/" || fail "the immutable source sandbox could not be created."
  cp "$TRUSTED_AUTOMATION_ROOT/gradle/versioning.gradle" "$sandbox/source/gradle/versioning.gradle"
  cp "$TRUSTED_AUTOMATION_ROOT/gradle/publishing.gradle" "$sandbox/source/gradle/publishing.gradle"
  python3 - "$sandbox/source/build.gradle" <<'PY' || conflict "the trusted Gradle hooks do not match sdk-java."
import pathlib, re, sys
path = pathlib.Path(sys.argv[1])
source = path.read_text()
matches = list(re.finditer(r"id ['\"]io\.github\.gradle-nexus\.publish-plugin['\"] version ['\"][^'\"]+['\"]", source))
if len(matches) != 1:
    raise SystemExit("Expected exactly one Gradle Nexus publish plugin declaration")
source = source[:matches[0].start()] + "id 'io.github.gradle-nexus.publish-plugin' version '1.3.0'" + source[matches[0].end():]
path.write_text(source)
PY
  docker run --rm --pull=missing --network bridge --cap-drop ALL \
    --security-opt no-new-privileges --pids-limit 2048 \
    --user "$(id -u):$(id -g)" --workdir /workspace \
    --env HOME=/candidate-home --env GRADLE_USER_HOME=/gradle-home \
    --mount "type=bind,src=$sandbox/source,dst=/workspace" \
    --mount "type=bind,src=$MAVEN_PAYLOAD_OUTPUT,dst=/payload" \
    --mount "type=bind,src=$sandbox/gradle,dst=/gradle-home" \
    --mount "type=bind,src=$sandbox/home,dst=/candidate-home" \
    "$image" ./gradlew --no-daemon -Dmaven.repo.local=/payload \
    "-PreleaseVersion=$MAVEN_PAYLOAD_VERSION" "-PreleaseCommit=$MAVEN_PAYLOAD_COMMIT" \
    publishToMavenLocal >&2 || fail "the isolated Gradle payload build failed."

  find "$MAVEN_PAYLOAD_OUTPUT/io/temporal" -type f \
    \( -name '*.asc' -o -name '*.md5' -o -name '*.sha1' \) -delete
  printf '%s' "$JAR_SIGNING_KEY" | base64 --decode >"$key_file" ||
    fail "the protected signing key is not valid base64."
  gpg --batch --homedir "$gnupg" --import "$key_file" >/dev/null 2>&1 ||
    fail "the protected signing key could not be imported."
  while IFS= read -r -d '' payload; do
    gpg --batch --yes --homedir "$gnupg" --pinentry-mode loopback \
      --passphrase "$JAR_SIGNING_KEY_PASSWORD" --local-user "$JAR_SIGNING_KEY_ID" \
      --armor --detach-sign --output "$payload.asc" "$payload" ||
      fail "trusted signing failed for ${payload#"$MAVEN_PAYLOAD_OUTPUT/"}."
    md5sum "$payload" | awk '{print $1}' >"$payload.md5"
    sha1sum "$payload" | awk '{print $1}' >"$payload.sha1"
  done < <(find "$MAVEN_PAYLOAD_OUTPUT/io/temporal" -type f \
    \( -name '*.jar' -o -name '*.pom' -o -name '*.module' \) -print0 | sort -z)
}

generated=$work/generated
bundle=$work/bundle
repository=$bundle/repository
manifest=$bundle/manifest.tsv
mkdir -p "$generated" "$repository/io/temporal" "$MAVEN_PAYLOAD_OUTPUT"
MAVEN_PAYLOAD_OUTPUT=$generated build_and_sign
mapfile -t artifacts < <(jq -er '.[]' <<<"$MAVEN_ARTIFACTS_JSON")
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
python3 "$TRUSTED_AUTOMATION_ROOT/.github/release-automation/release_automation/maven_payload.py" \
  validate "$manifest" "$repository" "$work/approved-artifacts.txt" \
  "$MAVEN_PAYLOAD_VERSION" "$MAVEN_PAYLOAD_COMMIT" ||
  conflict "the frozen Maven payload violates sdk-java policy."
tar --sort=name --mtime='UTC 1970-01-01' --owner=0 --group=0 --numeric-owner \
  -cf "$MAVEN_PAYLOAD_OUTPUT/maven-payload.tar" -C "$bundle" manifest.tsv repository
[[ -s $MAVEN_PAYLOAD_OUTPUT/maven-payload.tar ]] || fail "The Maven payload archive is empty."
