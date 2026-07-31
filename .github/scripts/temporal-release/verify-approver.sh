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

state=$(gh api "orgs/temporalio/teams/sdk-java-release-managers/memberships/$1" --jq .state 2>/dev/null) || {
  echo "verify-approver: $1 is not an sdk-java release manager." >&2
  exit 43
}
[[ $state == active ]] || {
  echo "verify-approver: $1 does not have active release-manager membership." >&2
  exit 43
}
