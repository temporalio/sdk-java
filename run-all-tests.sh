#!/usr/bin/env bash
# Runs the full Java build (compile, spotless, tests) the way CI does, against the CLI release
# pinned in SdkJavaTestServerProfile.TEST_CLI_VERSION. No local server involved.
#
# NOTE: not added to git (per working conventions).
set -euo pipefail

WT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$WT"

./gradlew prepareDevServerTests
./gradlew --offline build -PtestServer=dev-server
