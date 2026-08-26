#!/usr/bin/env bash
# Runs the SAA operator-command tests on gmt/operator-commands.
#
# No server setup: `-PtestServer=dev-server` makes gradle download the CLI release pinned in
# SdkJavaTestServerProfile.TEST_CLI_VERSION, start it, and set USE_EXTERNAL_SERVICE=true — the
# same path CI's "Unit test with CLI" job takes. Do not point this at a hand-built server; the
# whole point is that local runs and CI exercise the identical binary.
#
# NOTE: not added to git (per working conventions).
set -euo pipefail

WT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$WT"

./gradlew prepareDevServerTests
./gradlew --offline :temporal-sdk:test -PtestServer=dev-server \
  --tests '*StandaloneActivityOperatorCommandsTest' \
  --tests '*StandaloneActivityTest' \
  --tests '*ActivityExecutionDescriptionTest' \
  --tests '*ActivityHandleOperatorCommandsTest' \
  --tests '*ActivityClientCallsInterceptorBaseTest'
