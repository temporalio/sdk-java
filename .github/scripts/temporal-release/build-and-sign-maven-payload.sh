#!/usr/bin/env bash

set -euo pipefail

fail() { echo "build-and-sign-maven-payload: $*" >&2; exit 1; }
conflict() { echo "build-and-sign-maven-payload: immutable conflict: $*" >&2; exit 42; }

for name in JAR_SIGNING_KEY JAR_SIGNING_KEY_ID JAR_SIGNING_KEY_PASSWORD \
  MAVEN_PAYLOAD_COMMIT MAVEN_PAYLOAD_OUTPUT MAVEN_PAYLOAD_VERSION; do
  [[ -n ${!name:-} ]] || fail "$name is required."
done
[[ $MAVEN_PAYLOAD_COMMIT =~ ^[0-9a-f]{40}$ ]] || conflict "the source SHA is invalid."
[[ $MAVEN_PAYLOAD_VERSION =~ ^[0-9]+\.[0-9]+\.[0-9]+(-RC[0-9]+)?$ ]] ||
  conflict "the release version is invalid."
[[ -x ./gradlew ]] || conflict "the immutable source has no Gradle wrapper."

# Candidate-controlled Gradle code runs in a separate PID and mount namespace. The pinned image is
# given only the public source, disposable output/cache directories, and the immutable release
# identity. It receives no host environment, cloud token, publication token, or signing material.
image='eclipse-temurin:17-jdk@sha256:91b6210cce02091f6f0798a83ec51aa223828242c5a21a85793bb8c28dc891c4'
sandbox=$(mktemp -d)
gnupg=$(mktemp -d)
key_file=$(mktemp)
trap 'rm -rf "$sandbox" "$gnupg" "$key_file"' EXIT
mkdir -p "$MAVEN_PAYLOAD_OUTPUT" "$sandbox/gradle" "$sandbox/home" "$sandbox/source"
chmod 0700 "$gnupg"
cp -a "$PWD/." "$sandbox/source/" || fail "the immutable source sandbox could not be created."

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

# Discard every candidate-produced signature/checksum before applying the trusted release key.
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
