#!/usr/bin/env bash

set -euo pipefail

# Exit status 0 means the artifact is visible. Exit status 1 is reserved for a
# bounded poll whose final response was a definitive 404. Exit status 2 means
# the result is ambiguous or the release identity is invalid.

usage() {
  echo "Usage: $0 check-absent VERSION" >&2
  echo "       $0 wait-visible VERSION EXPECTED_COMMIT" >&2
}

fail() {
  echo "wait-for-maven-central: $*" >&2
  exit 2
}

read_pom_commit() {
  local pom_file=$1

  python3 - "$pom_file" <<'PY'
import sys
import xml.etree.ElementTree as ET

try:
    root = ET.parse(sys.argv[1]).getroot()
except (ET.ParseError, OSError):
    raise SystemExit(1)

namespace = ""
if root.tag.startswith("{"):
    namespace = root.tag.split("}", 1)[0] + "}"

scm = root.find(f"{namespace}scm")
tag = None if scm is None else scm.find(f"{namespace}tag")
if tag is None or tag.text is None or not tag.text.strip():
    raise SystemExit(1)

print(tag.text.strip())
PY
}

if [[ $# -lt 2 || $# -gt 3 ]]; then
  usage
  exit 2
fi

mode=$1
version=$2
expected_commit=

readonly central_base_url=https://repo1.maven.org/maven2
readonly connect_timeout=10
readonly request_timeout=30

case "$mode" in
  check-absent)
    [[ $# -eq 2 ]] || fail "check-absent requires VERSION."
    # A new release only needs a short absence check before publication.
    readonly attempts=5
    readonly delay_seconds=10
    ;;
  wait-visible)
    [[ $# -eq 3 ]] || fail "wait-visible requires VERSION and EXPECTED_COMMIT."
    expected_commit=$3
    # Publication and propagation may have completed after a runner lost the
    # response. Give Central up to 30 minutes to expose the exact POM before a
    # caller considers another publication attempt.
    readonly attempts=90
    readonly delay_seconds=20
    ;;
  *)
    fail "Unknown operation: ${mode}."
    ;;
esac

[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-RC[0-9]+)?$ ]] ||
  fail "The version is invalid: ${version}."
if [[ -n "$expected_commit" ]]; then
  expected_commit=$(printf '%s' "$expected_commit" | tr '[:upper:]' '[:lower:]')
  [[ "$expected_commit" =~ ^[0-9a-f]{40}$ ]] ||
    fail "EXPECTED_COMMIT must be a full 40-character commit SHA."
  command -v python3 >/dev/null 2>&1 ||
    fail "python3 is required to verify Maven POM provenance."
fi

artifact_url="${central_base_url%/}/io/temporal/temporal-sdk/${version}/temporal-sdk-${version}.pom"
response_file=$(mktemp)
trap 'rm -f -- "$response_file"' EXIT

for ((attempt = 1; attempt <= attempts; attempt++)); do
  http_status=
  curl_exit=0

  if http_status=$(
    curl \
      --silent \
      --show-error \
      --location \
      --output "$response_file" \
      --write-out '%{http_code}' \
      --connect-timeout "$connect_timeout" \
      --max-time "$request_timeout" \
      "$artifact_url"
  ); then
    curl_exit=0
  else
    curl_exit=$?
  fi

  if [[ "$curl_exit" -ne 0 ]]; then
    case "$curl_exit" in
      5 | 6 | 7 | 18 | 28 | 35 | 52 | 55 | 56 | 92)
        reason="network error (curl exit ${curl_exit})"
        exhausted_status=2
        retryable=true
        ;;
      *)
        fail "curl failed permanently with exit ${curl_exit} for ${artifact_url}."
        ;;
    esac
  elif [[ "$http_status" == "200" ]]; then
    if [[ -n "$expected_commit" ]]; then
      if ! published_commit=$(read_pom_commit "$response_file"); then
        fail "Maven Central returned a malformed POM or one without scm.tag for io.temporal:temporal-sdk:${version}."
      fi
      published_commit=$(printf '%s' "$published_commit" | tr '[:upper:]' '[:lower:]')
      [[ "$published_commit" == "$expected_commit" ]] ||
        fail \
          "Maven Central contains io.temporal:temporal-sdk:${version} from commit ${published_commit}, expected ${expected_commit}."
    fi
    echo "Maven Central contains io.temporal:temporal-sdk:${version}${expected_commit:+ from commit ${expected_commit}}."
    exit 0
  elif [[ "$http_status" == "404" ]]; then
    reason="HTTP ${http_status}"
    exhausted_status=1
    retryable=true
  elif [[ "$http_status" == "408" || "$http_status" == "425" || "$http_status" == "429" || "$http_status" =~ ^5[0-9][0-9]$ ]]; then
    reason="HTTP ${http_status}"
    exhausted_status=2
    retryable=true
  elif [[ "$http_status" =~ ^4[0-9][0-9]$ ]]; then
    fail "Maven Central returned permanent HTTP ${http_status} for ${artifact_url}."
  else
    fail "Maven Central returned unexpected HTTP status ${http_status:-<empty>} for ${artifact_url}."
  fi

  if [[ "$retryable" == "true" && "$attempt" -lt "$attempts" ]]; then
    echo \
      "Maven Central is not ready for io.temporal:temporal-sdk:${version} (${reason}); retrying in ${delay_seconds}s (${attempt}/${attempts})." \
      >&2
    if [[ "$delay_seconds" -gt 0 ]]; then
      sleep "$delay_seconds"
    fi
  fi
done

echo \
  "wait-for-maven-central: Maven Central did not expose io.temporal:temporal-sdk:${version} after ${attempts} attempts; last result: ${reason}." \
  >&2
exit "$exhausted_status"
