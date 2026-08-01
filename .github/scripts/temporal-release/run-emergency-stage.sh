#!/usr/bin/env bash

set -euo pipefail

stage=${1:-}
case "$stage" in
  inspect | handoff | preflight | maven | github-draft | github-publish) ;;
  *) echo "run-emergency-stage: unknown fixed sdk-java release stage." >&2; exit 1 ;;
esac

export RELEASE_STAGE=$stage
attempt=0
while true; do
  attempt=$((attempt + 1))
  set +e
  bash "$TRUSTED_AUTOMATION_ROOT/.github/scripts/temporal-release/reconcile-publication.sh"
  status=$?
  set -e
  [[ $status -eq 0 ]] && exit 0
  case "$status" in
    42 | 43 | 44)
      if [[ -n ${RELEASE_FAILURE_FILE:-} ]]; then
        printf '{"stage":"%s","status":%s}\n' "$stage" "$status" >"$RELEASE_FAILURE_FILE"
      fi
      exit "$status"
      ;;
  esac
  echo "run-emergency-stage: $stage attempt $attempt will reconcile again after fixed backoff." >&2
  sleep 120
done
