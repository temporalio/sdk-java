#!/usr/bin/env bash
set -euo pipefail

# Packages each native test server binary into a reproducible release archive.
: "${RELEASE_VERSION:?RELEASE_VERSION is required.}"

native_directory=${1:?Native artifact directory is required.}
asset_directory=${2:?Release asset directory is required.}
platforms=(
  linux_amd64_musl
  linux_amd64
  macOS_amd64
  macOS_arm64
  linux_arm64
  windows_amd64
)

native_directory=$(cd "$native_directory" && pwd)
mkdir -p "$asset_directory"
asset_directory=$(cd "$asset_directory" && pwd)
staging_directory=$(mktemp -d)
trap 'rm -rf "$staging_directory"' EXIT

for platform in "${platforms[@]}"; do
  source_directory="$native_directory/release-native-$platform"
  mapfile -t binaries < <(
    find "$source_directory" -type f -name 'temporal-test-server*'
  )
  if [[ "${#binaries[@]}" -ne 1 ]]; then
    echo "::error::Expected one native executable in $source_directory."
    exit 1
  fi

  archive_root="temporal-test-server_${RELEASE_VERSION}_${platform}"
  package_directory="$staging_directory/$archive_root"
  mkdir "$package_directory"
  if [[ "$platform" == windows_* ]]; then
    executable="$package_directory/temporal-test-server.exe"
    install -m 0755 "${binaries[0]}" "$executable"
    touch -t 198001010000 "$package_directory" "$executable"
    (
      cd "$staging_directory"
      zip -X -qr "$asset_directory/$archive_root.zip" "$archive_root"
    )
  else
    executable="$package_directory/temporal-test-server"
    install -m 0755 "${binaries[0]}" "$executable"
    touch -t 198001010000 "$package_directory" "$executable"
    COPYFILE_DISABLE=1 tar -cf - -C "$staging_directory" "$archive_root" \
      | gzip -n > "$asset_directory/$archive_root.tar.gz"
  fi
  rm -rf "$package_directory"
done

(
  cd "$asset_directory"
  sha256sum ./*.tar.gz ./*.zip | sort -k2 > SHA256SUMS
)
