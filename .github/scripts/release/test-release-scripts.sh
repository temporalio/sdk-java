#!/usr/bin/env bash

set -euo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
resolve_script="$script_dir/resolve-release-metadata.sh"
wait_script="$script_dir/wait-for-maven-central.sh"
publish_script="$script_dir/publish-github-release.sh"
mock_curl="$script_dir/tests/mock-curl.sh"
mock_gh="$script_dir/tests/mock-gh.sh"

test_root=$(mktemp -d)
trap 'rm -rf -- "$test_root"' EXIT

tests_run=0

fail() {
  echo "test-release-scripts: $*" >&2
  exit 1
}

pass() {
  tests_run=$((tests_run + 1))
  echo "ok ${tests_run} - $1"
}

assert_file_line() {
  local file=$1
  local expected=$2

  grep -Fqx -- "$expected" "$file" ||
    fail "Expected ${file} to contain: ${expected}"
}

assert_equals() {
  local expected=$1
  local actual=$2
  local description=$3

  [[ "$actual" == "$expected" ]] ||
    fail "${description}: expected ${expected}, got ${actual}."
}

expect_failure() {
  local description=$1
  shift

  if "$@" >"$test_root/expected-failure.stdout" 2>"$test_root/expected-failure.stderr"; then
    fail "${description}: command unexpectedly succeeded."
  fi
}

expect_status() {
  local expected=$1
  local description=$2
  local actual
  shift 2

  if "$@" >"$test_root/expected-status.stdout" 2>"$test_root/expected-status.stderr"; then
    actual=0
  else
    actual=$?
  fi

  assert_equals "$expected" "$actual" "$description"
}

new_git_fixture() {
  fixture_counter=$((fixture_counter + 1))
  fixture_repo="$test_root/repository-${fixture_counter}"
  mkdir -p "$fixture_repo/releases"
  git -C "$fixture_repo" init --quiet
  git -C "$fixture_repo" config user.name "Release Script Test"
  git -C "$fixture_repo" config user.email "release-script@example.test"
  printf 'Fixture repository.\n' >"$fixture_repo/README.md"
  printf 'Previous release.\n' >"$fixture_repo/releases/v0.9.0"
  git -C "$fixture_repo" add README.md releases/v0.9.0
  git -C "$fixture_repo" commit --quiet -m "Create fixture"
  fixture_base=$(git -C "$fixture_repo" rev-parse HEAD)
}

run_resolver() {
  local repo=$1
  local base=$2
  local head=$3
  local output=$4

  (
    cd "$repo"
    "$resolve_script" "$base" "$head" "$output"
  )
}

fixture_counter=0

new_git_fixture
printf 'Stable release notes.\n' >"$fixture_repo/releases/v1.2.3"
git -C "$fixture_repo" add releases/v1.2.3
git -C "$fixture_repo" commit --quiet -m "Release v1.2.3"
fixture_head=$(git -C "$fixture_repo" rev-parse HEAD)
metadata_output="$test_root/stable-metadata.out"
run_resolver "$fixture_repo" "$fixture_base" "$fixture_head" "$metadata_output"
assert_file_line "$metadata_output" "tag=v1.2.3"
assert_file_line "$metadata_output" "version=1.2.3"
assert_file_line "$metadata_output" "notes_file=releases/v1.2.3"
assert_file_line "$metadata_output" "commit=${fixture_head}"
assert_file_line "$metadata_output" "prerelease=false"
pass "Stable release metadata is derived from one added notes file."

new_git_fixture
printf 'Release candidate notes.\n' >"$fixture_repo/releases/v2.0.0-RC2"
git -C "$fixture_repo" add releases/v2.0.0-RC2
git -C "$fixture_repo" commit --quiet -m "Release v2.0.0-RC2"
fixture_head=$(git -C "$fixture_repo" rev-parse HEAD)
metadata_output="$test_root/rc-metadata.out"
run_resolver "$fixture_repo" "$fixture_base" "$fixture_head" "$metadata_output"
assert_file_line "$metadata_output" "tag=v2.0.0-RC2"
assert_file_line "$metadata_output" "version=2.0.0-RC2"
assert_file_line "$metadata_output" "prerelease=true"
pass "Release candidate metadata is marked as a prerelease."

new_git_fixture
: >"$fixture_repo/releases/v1.2.4"
git -C "$fixture_repo" add releases/v1.2.4
git -C "$fixture_repo" commit --quiet -m "Add empty release notes"
fixture_head=$(git -C "$fixture_repo" rev-parse HEAD)
expect_failure \
  "Empty release notes" \
  run_resolver "$fixture_repo" "$fixture_base" "$fixture_head" "$test_root/empty.out"
pass "Empty release notes are rejected."

new_git_fixture
ln -s ../README.md "$fixture_repo/releases/v1.2.4"
git -C "$fixture_repo" add releases/v1.2.4
git -C "$fixture_repo" commit --quiet -m "Add symlinked release notes"
fixture_head=$(git -C "$fixture_repo" rev-parse HEAD)
expect_failure \
  "Symlinked release notes" \
  run_resolver "$fixture_repo" "$fixture_base" "$fixture_head" "$test_root/symlink.out"
pass "Symlinked release notes are rejected."

new_git_fixture
printf 'New release notes.\n' >"$fixture_repo/releases/v1.2.5"
printf 'Changed previous notes.\n' >>"$fixture_repo/releases/v0.9.0"
git -C "$fixture_repo" add releases/
git -C "$fixture_repo" commit --quiet -m "Change two release files"
fixture_head=$(git -C "$fixture_repo" rev-parse HEAD)
expect_failure \
  "Multiple release-file changes" \
  run_resolver "$fixture_repo" "$fixture_base" "$fixture_head" "$test_root/multiple.out"
pass "Any additional release-file change is rejected."

new_git_fixture
printf 'Invalidly named release notes.\n' >"$fixture_repo/releases/1.2.6"
git -C "$fixture_repo" add releases/1.2.6
git -C "$fixture_repo" commit --quiet -m "Add invalid release notes"
fixture_head=$(git -C "$fixture_repo" rev-parse HEAD)
expect_failure \
  "Invalid release filename" \
  run_resolver "$fixture_repo" "$fixture_base" "$fixture_head" "$test_root/invalid.out"
pass "Invalid release filenames are rejected."

new_git_fixture
printf 'Already tagged release notes.\n' >"$fixture_repo/releases/v1.2.7"
git -C "$fixture_repo" add releases/v1.2.7
git -C "$fixture_repo" commit --quiet -m "Release v1.2.7"
fixture_head=$(git -C "$fixture_repo" rev-parse HEAD)
git -C "$fixture_repo" tag v1.2.7
expect_failure \
  "Existing release tag" \
  run_resolver "$fixture_repo" "$fixture_base" "$fixture_head" "$test_root/tagged.out"
pass "An existing release tag is rejected."

responses="$test_root/maven-retry-responses"
state="$test_root/maven-retry-state"
curl_arguments="$test_root/maven-curl-arguments"
printf '404\n408\n429\n500\nnetwork\n200\n' >"$responses"
MOCK_CURL_RESPONSES_FILE="$responses" \
  MOCK_CURL_STATE_FILE="$state" \
  MOCK_CURL_ARGUMENTS_FILE="$curl_arguments" \
  CURL_BIN="$mock_curl" \
  "$wait_script" 1.2.3 6 0 >/dev/null
assert_equals "6" "$(<"$state")" "Maven retry count"
grep -Fq \
  'https://repo1.maven.org/maven2/io/temporal/temporal-sdk/1.2.3/temporal-sdk-1.2.3.pom' \
  "$curl_arguments" ||
  fail "Maven polling used the wrong artifact URL."
pass "Maven polling retries 404, 408, 429, 5xx, and network failures."

responses="$test_root/maven-permanent-responses"
state="$test_root/maven-permanent-state"
printf '401\n200\n' >"$responses"
expect_status \
  2 \
  "Permanent Maven response status" \
  env \
  MOCK_CURL_RESPONSES_FILE="$responses" \
  MOCK_CURL_STATE_FILE="$state" \
  CURL_BIN="$mock_curl" \
  "$wait_script" 1.2.3 5 0
assert_equals "1" "$(<"$state")" "Permanent Maven response count"
pass "Maven polling fails immediately on permanent 4xx responses."

responses="$test_root/maven-curl-config-responses"
state="$test_root/maven-curl-config-state"
printf 'curl-3\n200\n' >"$responses"
expect_status \
  2 \
  "Permanent curl failure status" \
  env \
  MOCK_CURL_RESPONSES_FILE="$responses" \
  MOCK_CURL_STATE_FILE="$state" \
  CURL_BIN="$mock_curl" \
  "$wait_script" 1.2.3 5 0
assert_equals "1" "$(<"$state")" "Permanent curl failure count"
pass "Maven polling does not retry permanent curl failures."

responses="$test_root/maven-exhausted-responses"
state="$test_root/maven-exhausted-state"
printf '404\n404\n404\n' >"$responses"
expect_status \
  1 \
  "Definitively absent Maven response status" \
  env \
  MOCK_CURL_RESPONSES_FILE="$responses" \
  MOCK_CURL_STATE_FILE="$state" \
  CURL_BIN="$mock_curl" \
  "$wait_script" 1.2.3 3 0
assert_equals "3" "$(<"$state")" "Exhausted Maven retry count"
pass "Maven polling stops at the configured attempt bound."

responses="$test_root/maven-ambiguous-responses"
state="$test_root/maven-ambiguous-state"
printf '500\nnetwork\n' >"$responses"
expect_status \
  2 \
  "Ambiguous Maven response status" \
  env \
  MOCK_CURL_RESPONSES_FILE="$responses" \
  MOCK_CURL_STATE_FILE="$state" \
  CURL_BIN="$mock_curl" \
  "$wait_script" 1.2.3 2 0
assert_equals "2" "$(<"$state")" "Ambiguous Maven retry count"
pass "Maven polling never reports transient exhaustion as definitive absence."

provenance_sha=4444444444444444444444444444444444444444
other_sha=5555555555555555555555555555555555555555
matching_pom="$test_root/maven-matching.pom"
mismatched_pom="$test_root/maven-mismatched.pom"
missing_tag_pom="$test_root/maven-missing-tag.pom"
malformed_pom="$test_root/maven-malformed.pom"
printf \
  '<project xmlns="http://maven.apache.org/POM/4.0.0"><scm><tag>%s</tag></scm></project>\n' \
  "$provenance_sha" >"$matching_pom"
printf \
  '<project xmlns="http://maven.apache.org/POM/4.0.0"><scm><tag>%s</tag></scm></project>\n' \
  "$other_sha" >"$mismatched_pom"
printf \
  '<project xmlns="http://maven.apache.org/POM/4.0.0"><scm /></project>\n' \
  >"$missing_tag_pom"
printf '<project><scm><tag>\n' >"$malformed_pom"

responses="$test_root/maven-provenance-responses"
state="$test_root/maven-provenance-state"
printf '404\n200\n' >"$responses"
MOCK_CURL_RESPONSES_FILE="$responses" \
  MOCK_CURL_STATE_FILE="$state" \
  MOCK_CURL_BODY_FILE="$matching_pom" \
  CURL_BIN="$mock_curl" \
  "$wait_script" 1.2.3 2 0 "$provenance_sha" >/dev/null
assert_equals "2" "$(<"$state")" "Maven provenance propagation count"
pass "Maven polling verifies exact commit provenance after propagation."

responses="$test_root/maven-mismatched-provenance-responses"
state="$test_root/maven-mismatched-provenance-state"
printf '200\n' >"$responses"
expect_status \
  2 \
  "Mismatched Maven provenance status" \
  env \
  MOCK_CURL_RESPONSES_FILE="$responses" \
  MOCK_CURL_STATE_FILE="$state" \
  MOCK_CURL_BODY_FILE="$mismatched_pom" \
  CURL_BIN="$mock_curl" \
  "$wait_script" 1.2.3 1 0 "$provenance_sha"
pass "Maven polling rejects coordinates published from another commit."

responses="$test_root/maven-missing-provenance-responses"
state="$test_root/maven-missing-provenance-state"
printf '200\n' >"$responses"
expect_status \
  2 \
  "Missing Maven provenance status" \
  env \
  MOCK_CURL_RESPONSES_FILE="$responses" \
  MOCK_CURL_STATE_FILE="$state" \
  MOCK_CURL_BODY_FILE="$missing_tag_pom" \
  CURL_BIN="$mock_curl" \
  "$wait_script" 1.2.3 1 0 "$provenance_sha"
pass "Maven polling rejects a POM without commit provenance."

responses="$test_root/maven-malformed-provenance-responses"
state="$test_root/maven-malformed-provenance-state"
printf '200\n' >"$responses"
expect_status \
  2 \
  "Malformed Maven POM status" \
  env \
  MOCK_CURL_RESPONSES_FILE="$responses" \
  MOCK_CURL_STATE_FILE="$state" \
  MOCK_CURL_BODY_FILE="$malformed_pom" \
  CURL_BIN="$mock_curl" \
  "$wait_script" 1.2.3 1 0 "$provenance_sha"
pass "Maven polling rejects malformed POM provenance."

new_release_fixture() {
  release_fixture_counter=$((release_fixture_counter + 1))
  release_fixture="$test_root/release-${release_fixture_counter}"
  release_state="$release_fixture/state"
  release_assets="$release_fixture/assets"
  release_notes="$release_fixture/notes.md"
  release_output="$release_fixture/github.out"
  release_failures="$release_fixture/upload-failures"
  release_publish_failures="$release_fixture/publish-failures"
  mkdir -p "$release_state" "$release_assets"
  printf 'Release notes.\n' >"$release_notes"
  printf 'linux archive\n' >"$release_assets/temporal-test-server_linux_amd64.tar.gz"
  printf 'windows archive\n' >"$release_assets/temporal-test-server_windows_amd64.zip"
  (
    cd "$release_assets"
    if command -v sha256sum >/dev/null 2>&1; then
      sha256sum ./*.tar.gz ./*.zip
    else
      shasum -a 256 ./*.tar.gz ./*.zip
    fi >SHA256SUMS
  )
  : >"$release_output"
  printf '0\n' >"$release_failures"
  printf '0\n' >"$release_publish_failures"
}

run_publisher() {
  local tag=$1
  local sha=$2
  local notes=$3
  local assets=$4
  local output=$5
  local state_dir=$6
  local failure_file=$7

  GITHUB_REPOSITORY=temporalio/sdk-java \
    GH_BIN="$mock_gh" \
    RELEASE_MAX_ATTEMPTS=4 \
    RELEASE_RETRY_DELAY_SECONDS=0 \
    MOCK_GH_STATE_DIR="$state_dir" \
    MOCK_GH_UPLOAD_FAILURES_FILE="$failure_file" \
    MOCK_GH_PUBLISH_FAILURES_FILE="${state_dir%/state}/publish-failures" \
    "$publish_script" "$tag" "$sha" "$notes" "$assets" "$output"
}

release_fixture_counter=0
stable_sha=1111111111111111111111111111111111111111

new_release_fixture
printf 'corruption after checksums\n' >>"$release_assets/temporal-test-server_linux_amd64.tar.gz"
expect_failure \
  "Stale checksum manifest" \
  run_publisher \
  v1.2.3 \
  "$stable_sha" \
  "$release_notes" \
  "$release_assets" \
  "$release_output" \
  "$release_state" \
  "$release_failures"
[[ ! -e "$release_state/calls.log" ]] ||
  fail "Checksum validation invoked GitHub before rejecting stale checksums."
pass "A stale checksum manifest is rejected before any GitHub mutation."

new_release_fixture
printf '1\n' >"$release_failures"
printf '1\n' >"$release_publish_failures"
run_publisher \
  v1.2.3 \
  "$stable_sha" \
  "$release_notes" \
  "$release_assets" \
  "$release_output" \
  "$release_state" \
  "$release_failures" >/dev/null
assert_equals "false" "$(jq -r '.isDraft' "$release_state/release.json")" "Published draft state"
assert_equals "false" "$(jq -r '.isPrerelease' "$release_state/release.json")" "Stable prerelease state"
assert_equals "$stable_sha" "$(jq -r '.targetCommitish' "$release_state/release.json")" "Release target"
assert_file_line \
  "$release_output" \
  "url=https://github.example.test/releases/tag/v1.2.3"
assert_equals \
  "3" \
  "$(find "$release_state/assets" -type f | wc -l | tr -d ' ')" \
  "Published asset count"
[[ "$(grep -c '^release upload ' "$release_state/calls.log")" -gt 2 ]] ||
  fail "A transient upload failure was not retried."
last_upload_line=$(grep -n '^release upload ' "$release_state/calls.log" | tail -n 1 | cut -d: -f1)
publish_line=$(grep -n -- '--draft=false' "$release_state/calls.log" | head -n 1 | cut -d: -f1)
[[ "$last_upload_line" -lt "$publish_line" ]] ||
  fail "The draft was published before all upload attempts completed."
tag_create_line=$(
  grep -n '^api --method POST repos/temporalio/sdk-java/git/refs ' \
    "$release_state/calls.log" |
    head -n 1 |
    cut -d: -f1
)
[[ "$tag_create_line" -lt "$publish_line" ]] ||
  fail "The release was published before its exact tag was created."
grep -Fq -- '--verify-tag' "$release_state/calls.log" ||
  fail "Draft creation did not require the pre-existing verified tag."
while IFS=: read -r edit_line _; do
  previous_call=$(sed -n "$((edit_line - 1))p" "$release_state/calls.log")
  [[ "$previous_call" == "api repos/temporalio/sdk-java/git/ref/tags/v1.2.3 "* ]] ||
    fail "A publish attempt was not immediately preceded by exact tag-ref verification."
done < <(grep -n -- '^release edit .*--draft=false' "$release_state/calls.log")
[[ "$(grep -c -- '--draft=false' "$release_state/calls.log")" -eq 2 ]] ||
  fail "The simulated publication failure was not retried."
if grep -Fq '/commits/' "$release_state/calls.log"; then
  fail "Tag verification used an ambiguous commit endpoint."
fi
pass "A draft release retries uploads, verifies assets, and publishes last."

create_calls=$(grep -c '^release create ' "$release_state/calls.log")
upload_calls=$(grep -c '^release upload ' "$release_state/calls.log")
publish_calls=$(grep -c -- '--draft=false' "$release_state/calls.log")
second_output="$release_fixture/github-second.out"
: >"$second_output"
run_publisher \
  v1.2.3 \
  "$stable_sha" \
  "$release_notes" \
  "$release_assets" \
  "$second_output" \
  "$release_state" \
  "$release_failures" >/dev/null
assert_equals "$create_calls" "$(grep -c '^release create ' "$release_state/calls.log")" "Idempotent create count"
assert_equals "$upload_calls" "$(grep -c '^release upload ' "$release_state/calls.log")" "Idempotent upload count"
assert_equals "$publish_calls" "$(grep -c -- '--draft=false' "$release_state/calls.log")" "Idempotent publish count"
assert_file_line \
  "$second_output" \
  "url=https://github.example.test/releases/tag/v1.2.3"
pass "A completed release rerun is read-only and succeeds idempotently."

jq '.body = "Release notes.\r\n"' \
  "$release_state/release.json" >"$release_state/release.json.tmp"
mv "$release_state/release.json.tmp" "$release_state/release.json"
mutation_calls_before=$(grep -Ec '^release (create|edit|upload) ' "$release_state/calls.log")
crlf_output="$release_fixture/github-crlf.out"
: >"$crlf_output"
run_publisher \
  v1.2.3 \
  "$stable_sha" \
  "$release_notes" \
  "$release_assets" \
  "$crlf_output" \
  "$release_state" \
  "$release_failures" >/dev/null
assert_equals \
  "$mutation_calls_before" \
  "$(grep -Ec '^release (create|edit|upload) ' "$release_state/calls.log")" \
  "CRLF-normalized mutation count"
pass "GitHub CRLF normalization does not make unchanged release notes stale."

new_release_fixture
wrong_notes="$release_fixture/wrong-notes.md"
printf 'Stale release notes.\n' >"$wrong_notes"
MOCK_GH_STATE_DIR="$release_state" \
  "$mock_gh" release create v1.2.3 \
  --repo temporalio/sdk-java \
  --draft \
  --title v1.2.3 \
  --target "$stable_sha" \
  --notes-file "$wrong_notes"
run_publisher \
  v1.2.3 \
  "$stable_sha" \
  "$release_notes" \
  "$release_assets" \
  "$release_output" \
  "$release_state" \
  "$release_failures" >/dev/null
assert_equals \
  "Release notes." \
  "$(jq -r '.body' "$release_state/release.json")" \
  "Refreshed release notes"
pass "A resumed draft refreshes stale release notes before publication."

new_release_fixture
run_publisher \
  v1.2.3 \
  "$stable_sha" \
  "$release_notes" \
  "$release_assets" \
  "$release_output" \
  "$release_state" \
  "$release_failures" >/dev/null
jq '.body = "Wrong published notes."' \
  "$release_state/release.json" >"$release_state/release.json.tmp"
mv "$release_state/release.json.tmp" "$release_state/release.json"
mutation_calls_before=$(grep -Ec '^release (create|edit|upload) ' "$release_state/calls.log")
expect_failure \
  "Wrong published release notes" \
  run_publisher \
  v1.2.3 \
  "$stable_sha" \
  "$release_notes" \
  "$release_assets" \
  "$release_output" \
  "$release_state" \
  "$release_failures"
assert_equals \
  "$mutation_calls_before" \
  "$(grep -Ec '^release (create|edit|upload) ' "$release_state/calls.log")" \
  "Wrong-body mutation count"
pass "A published release with stale notes is rejected without mutation."

new_release_fixture
rc_sha=2222222222222222222222222222222222222222
run_publisher \
  v2.0.0-RC1 \
  "$rc_sha" \
  "$release_notes" \
  "$release_assets" \
  "$release_output" \
  "$release_state" \
  "$release_failures" >/dev/null
assert_equals "true" "$(jq -r '.isPrerelease' "$release_state/release.json")" "RC prerelease state"
assert_equals "false" "$(jq -r '.isDraft' "$release_state/release.json")" "RC published state"
pass "Release candidates are published as GitHub prereleases."

new_release_fixture
wrong_sha=3333333333333333333333333333333333333333
MOCK_GH_STATE_DIR="$release_state" \
  "$mock_gh" release create v1.2.3 \
  --repo temporalio/sdk-java \
  --draft \
  --title v1.2.3 \
  --target "$wrong_sha" \
  --notes-file "$release_notes"
expect_failure \
  "Wrong draft target" \
  run_publisher \
  v1.2.3 \
  "$stable_sha" \
  "$release_notes" \
  "$release_assets" \
  "$release_output" \
  "$release_state" \
  "$release_failures"
assert_equals "0" "$(grep -c '^release upload ' "$release_state/calls.log" || true)" "Wrong-target upload count"
pass "A draft targeting another commit is rejected before upload."

new_release_fixture
MOCK_GH_STATE_DIR="$release_state" \
  "$mock_gh" release create v1.2.3 \
  --repo temporalio/sdk-java \
  --draft \
  --title v1.2.3 \
  --target "$stable_sha" \
  --notes-file "$release_notes"
printf '%s\n' 3333333333333333333333333333333333333333 >"$release_state/tag-sha"
expect_failure \
  "Wrong existing tag target" \
  run_publisher \
  v1.2.3 \
  "$stable_sha" \
  "$release_notes" \
  "$release_assets" \
  "$release_output" \
  "$release_state" \
  "$release_failures"
assert_equals "true" "$(jq -r '.isDraft' "$release_state/release.json")" "Wrong-tag draft state"
assert_equals \
  "0" \
  "$(grep -c -- '--draft=false' "$release_state/calls.log" || true)" \
  "Wrong-tag publish count"
pass "An existing tag at another commit blocks publication."

new_release_fixture
annotated_tag_object=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
printf '%s\n' "$annotated_tag_object" >"$release_state/tag-sha"
printf 'tag\n' >"$release_state/tag-type"
printf '%s\n' "$stable_sha" >"$release_state/annotated-target-sha"
run_publisher \
  v1.2.3 \
  "$stable_sha" \
  "$release_notes" \
  "$release_assets" \
  "$release_output" \
  "$release_state" \
  "$release_failures" >/dev/null
grep -Fq \
  "api repos/temporalio/sdk-java/git/tags/${annotated_tag_object} " \
  "$release_state/calls.log" ||
  fail "Annotated tag objects were not peeled."
assert_equals \
  "0" \
  "$(grep -c '^api --method POST ' "$release_state/calls.log" || true)" \
  "Annotated-tag creation count"
pass "An existing annotated tag is peeled to and verified at the release commit."

new_release_fixture
MOCK_GH_STATE_DIR="$release_state" \
  "$mock_gh" release create v1.2.3 \
  --repo temporalio/sdk-java \
  --draft \
  --title v1.2.3 \
  --target "$stable_sha" \
  --notes-file "$release_notes"
printf 'unexpected asset\n' >"$release_state/assets/unexpected.zip"
expect_failure \
  "Unexpected draft asset" \
  run_publisher \
  v1.2.3 \
  "$stable_sha" \
  "$release_notes" \
  "$release_assets" \
  "$release_output" \
  "$release_state" \
  "$release_failures"
assert_equals "0" "$(grep -c -- '--draft=false' "$release_state/calls.log" || true)" "Unexpected-asset publish count"
pass "Unexpected draft assets prevent publication."

new_release_fixture
MOCK_GH_STATE_DIR="$release_state" \
  "$mock_gh" release create v1.2.3 \
  --repo temporalio/sdk-java \
  --draft \
  --title v1.2.3 \
  --target "$stable_sha" \
  --notes-file "$release_notes"
cp \
  "$release_assets/temporal-test-server_linux_amd64.tar.gz" \
  "$release_state/assets/temporal-test-server_linux_amd64.tar.gz"
cp "$release_assets/SHA256SUMS" "$release_state/assets/SHA256SUMS"
run_publisher \
  v1.2.3 \
  "$stable_sha" \
  "$release_notes" \
  "$release_assets" \
  "$release_output" \
  "$release_state" \
  "$release_failures" >/dev/null
assert_equals "1" "$(grep -c '^release upload ' "$release_state/calls.log")" "Partial-resume upload count"
pass "A partial draft resumes by uploading only missing assets."

new_release_fixture
MOCK_GH_STATE_DIR="$release_state" \
  "$mock_gh" release create v1.2.3 \
  --repo temporalio/sdk-java \
  --draft \
  --title v1.2.3 \
  --target "$stable_sha" \
  --notes-file "$release_notes"
cp "$release_assets/"* "$release_state/assets/"
touch \
  "$release_state/omit-digest/temporal-test-server_linux_amd64.tar.gz"
run_publisher \
  v1.2.3 \
  "$stable_sha" \
  "$release_notes" \
  "$release_assets" \
  "$release_output" \
  "$release_state" \
  "$release_failures" >/dev/null
assert_equals "1" "$(grep -c '^release upload ' "$release_state/calls.log")" "Missing-digest upload count"
pass "An asset without a verifiable SHA-256 digest is replaced before publication."

echo "1..${tests_run}"
