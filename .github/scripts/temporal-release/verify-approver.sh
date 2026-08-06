#!/usr/bin/env bash

set -euo pipefail

[[ $# -eq 1 && $1 =~ ^[A-Za-z0-9-]{1,39}$ ]] || {
  echo "verify-approver: expected one GitHub login." >&2
  exit 43
}
[[ -n ${GH_TOKEN:-} ]] || {
  echo "verify-approver: GH_TOKEN is missing." >&2
  exit 43
}

response=$(mktemp)
set +e
status=$(curl --silent --show-error --location --output "$response" --write-out '%{http_code}' \
  --header "Authorization: Bearer $GH_TOKEN" --header 'Accept: application/vnd.github+json' \
  "https://api.github.com/orgs/temporalio/teams/sdk/memberships/$1")
curl_status=$?
set -e
[[ $curl_status -eq 0 ]] || {
  echo "verify-approver: GitHub membership is temporarily unavailable." >&2
  exit 1
}
[[ $status != 404 ]] || {
  echo "verify-approver: $1 is not a temporalio/sdk team member." >&2
  exit 43
}
[[ $status == 200 ]] || {
  echo "verify-approver: GitHub returned HTTP $status; retry later." >&2
  exit 1
}
state=$(jq -er .state "$response") || {
  echo "verify-approver: GitHub returned invalid membership state." >&2
  exit 1
}
[[ $state == active ]] || {
  echo "verify-approver: $1 does not have active temporalio/sdk team membership." >&2
  exit 43
}
