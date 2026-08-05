#!/usr/bin/env bash

set -euo pipefail

root=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir "$work/bin"

cat >"$work/bin/aws" <<'FAKE_AWS'
#!/usr/bin/env bash
printf '%s\n' "$FAKE_AWS_ERROR" >&2
exit "$FAKE_AWS_STATUS"
FAKE_AWS
chmod +x "$work/bin/aws"

run_read() {
  local status=$1 error=$2 output=$3
  set +e
  PATH="$work/bin:$PATH" FAKE_AWS_STATUS=$status FAKE_AWS_ERROR=$error \
    RELEASE_ARTIFACT_BUCKET=test-bucket RELEASE_TAG=v1.2.3 \
    "$root/read-emergency-state.sh" >"$output" 2>"$output.error"
  local actual=$?
  set -e
  printf '%s\n' "$actual"
}

for code in 404 NoSuchKey NotFound; do
  error="An error occurred ($code) when calling the HeadObject operation: missing"
  [[ $(run_read 254 "$error" "$work/$code") == 0 ]]
  [[ $(cat "$work/$code") == ABSENT ]]
done

exact_error='An error occurred (404) when calling the HeadObject operation: Not Found'
[[ $(run_read 1 "$exact_error" "$work/wrong-status") == 1 ]]
grep -q 'durable emergency state is temporarily unavailable' "$work/wrong-status.error"

wrong_operation='An error occurred (404) when calling the GetObject operation: Not Found'
[[ $(run_read 254 "$wrong_operation" "$work/wrong-operation") == 1 ]]
grep -q 'durable emergency state is temporarily unavailable' "$work/wrong-operation.error"

access_denied='An error occurred (AccessDenied) when calling the HeadObject operation: denied'
[[ $(run_read 254 "$access_denied" "$work/access-denied") == 1 ]]
grep -q 'durable emergency state is temporarily unavailable' "$work/access-denied.error"
