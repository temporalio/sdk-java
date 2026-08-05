#!/usr/bin/env bash

set -euo pipefail

fail() { echo "read-emergency-state: $*" >&2; exit 1; }
[[ ${RELEASE_ARTIFACT_BUCKET:-} ]] || fail "RELEASE_ARTIFACT_BUCKET is required."
[[ ${RELEASE_TAG:-} =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-RC[0-9]+)?$ ]] ||
  fail "RELEASE_TAG is invalid."
key="sdk-java/emergency/$RELEASE_TAG.json"
set +e
head_error=$(aws s3api head-object --bucket "$RELEASE_ARTIFACT_BUCKET" --key "$key" 2>&1)
status=$?
set -e
if [[ $status -ne 0 ]]; then
  if [[ $status -eq 254 ]] &&
    grep -Eq '^An error occurred \((404|NoSuchKey|NotFound)\) when calling the HeadObject operation:' \
      <<<"$head_error"; then
    echo ABSENT
    exit 0
  fi
  fail "durable emergency state is temporarily unavailable."
fi
aws s3 cp "s3://$RELEASE_ARTIFACT_BUCKET/$key" - --no-progress ||
  fail "durable emergency state is temporarily unavailable."
