#!/usr/bin/env bash
set -euo pipefail

# Exercises changelog validation and deterministic packaging without external services.
script_directory=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
release_script="$script_directory/release.sh"
package_script="$script_directory/package-native-release.sh"
temporary_directory=$(mktemp -d)
trap 'rm -rf "$temporary_directory"' EXIT

repository="$temporary_directory/repository"
mkdir "$repository"
git -C "$repository" init -q
git -C "$repository" config user.email test@example.com
git -C "$repository" config user.name "Release Test"
printf '# Changelog\n\n## [Unreleased]\n\n### Fixed\n- Fixed it.\n' \
  > "$repository/CHANGELOG.md"
git -C "$repository" add CHANGELOG.md
git -C "$repository" commit -qm base
base=$(git -C "$repository" rev-parse HEAD)
(
  cd "$repository"
  BASE_SHA="$base" HEAD_SHA="$base" EVENT_NAME=pull_request \
    GITHUB_OUTPUT="$temporary_directory/no-release-output" \
    "$release_script" candidate "$temporary_directory/no-release-notes"
)
grep -Fxq 'release=false' "$temporary_directory/no-release-output"

printf '# Changelog\n\n## [Unreleased]\n\n## [1.41.0] - 2026-10-03\n\n### Fixed\n- Fixed it.\n' \
  > "$repository/CHANGELOG.md"
git -C "$repository" commit -qam release
head=$(git -C "$repository" rev-parse HEAD)
(
  cd "$repository"
  BASE_SHA="$base" HEAD_SHA="$head" EVENT_NAME=pull_request \
    GITHUB_OUTPUT="$temporary_directory/output" \
    "$release_script" candidate "$temporary_directory/notes"
)
grep -Fxq 'release=true' "$temporary_directory/output"
grep -Fxq 'version=1.41.0' "$temporary_directory/output"
grep -Fxq '### Fixed' "$temporary_directory/notes"
printf '# Changelog\n\n## [Unreleased]\n\n## [1.41.0] - 2026-10-04\n\n### Fixed\n- Fixed it.\n' \
  > "$repository/CHANGELOG.md"
git -C "$repository" commit -qam rewrite
rewrite=$(git -C "$repository" rev-parse HEAD)
if (cd "$repository" && BASE_SHA="$head" HEAD_SHA="$rewrite" EVENT_NAME=push \
  "$release_script" candidate "$temporary_directory/rewrite-notes" 2>/dev/null); then
  echo "Published changelog edits must be rejected." >&2
  exit 1
fi

native="$temporary_directory/native"
for platform in linux_amd64_musl linux_amd64 macOS_amd64 macOS_arm64 linux_arm64 windows_amd64; do
  mkdir -p "$native/release-native-$platform"
  printf 'binary for %s' "$platform" > "$native/release-native-$platform/temporal-test-server"
done
RELEASE_VERSION=1.41.0 "$package_script" "$native" "$temporary_directory/first"
RELEASE_VERSION=1.41.0 "$package_script" "$native" "$temporary_directory/second"
diff -qr "$temporary_directory/first" "$temporary_directory/second"
[[ $(find "$temporary_directory/first" -type f | wc -l) -eq 7 ]]
(cd "$temporary_directory/first" && sha256sum -c SHA256SUMS)
