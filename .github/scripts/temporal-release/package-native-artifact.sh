#!/usr/bin/env bash

set -euo pipefail

fail() { echo "package-native-artifact: $*" >&2; exit 1; }
conflict() { echo "package-native-artifact: immutable artifact conflict: $*" >&2; exit 42; }

required=(
  RELEASE_ASSET_PLATFORM RELEASE_ARCHIVE_EXTENSION RELEASE_BINARY_NAME
  RELEASE_OUTPUT_DIR RELEASE_PLATFORM RELEASE_PREBUILT_NATIVE_DIR RELEASE_TAG
  TRUSTED_AUTOMATION_COMMIT TRUSTED_AUTOMATION_ROOT
)
for variable in "${required[@]}"; do
  [[ -n ${!variable:-} ]] || fail "Required value $variable is missing."
done

[[ $(git -C "$TRUSTED_AUTOMATION_ROOT" rev-parse --verify HEAD^{commit}) == \
  "$TRUSTED_AUTOMATION_COMMIT" ]] || conflict "the trusted automation checkout changed."
prebuilt=$RELEASE_PREBUILT_NATIVE_DIR
binary=$prebuilt/$RELEASE_BINARY_NAME
[[ -f $binary && ! -L $binary && -s $binary ]] || conflict "the native executable is invalid."
entries=$(find "$prebuilt" -mindepth 1 -maxdepth 1 -exec basename {} \; | sort | tr '\n' ' ')
[[ $entries == "$RELEASE_BINARY_NAME " ]] ||
  conflict "the native output contains unexpected files."

version=${RELEASE_TAG#v}
archive_root="temporal-test-server_${version}_${RELEASE_ASSET_PLATFORM}"
artifact_name=${archive_root}${RELEASE_ARCHIVE_EXTENSION}
mkdir -p "$RELEASE_OUTPUT_DIR"
[[ -z $(find "$RELEASE_OUTPUT_DIR" -mindepth 1 -maxdepth 1 -print -quit) ]] ||
  fail "The native artifact output directory is not empty."
python3 - "$binary" "$RELEASE_OUTPUT_DIR/$artifact_name" "$archive_root" \
  "$RELEASE_BINARY_NAME" "$RELEASE_PLATFORM" <<'PY'
import gzip
import io
import pathlib
import stat
import sys
import tarfile
import zipfile

source, output, root, binary_name, platform = sys.argv[1:]
data = pathlib.Path(source).read_bytes()
if platform == "windows-amd64":
    info = zipfile.ZipInfo(f"{root}/{binary_name}", (1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = (stat.S_IFREG | 0o755) << 16
    with zipfile.ZipFile(output, "w") as archive:
        archive.writestr(info, data)
else:
    tar_bytes = io.BytesIO()
    with tarfile.open(fileobj=tar_bytes, mode="w", format=tarfile.GNU_FORMAT) as archive:
        directory = tarfile.TarInfo(root)
        directory.type = tarfile.DIRTYPE
        directory.mode = 0o755
        directory.uid = directory.gid = directory.mtime = 0
        directory.uname = directory.gname = ""
        archive.addfile(directory)
        entry = tarfile.TarInfo(f"{root}/{binary_name}")
        entry.size = len(data)
        entry.mode = 0o755
        entry.uid = entry.gid = entry.mtime = 0
        entry.uname = entry.gname = ""
        archive.addfile(entry, io.BytesIO(data))
    with open(output, "wb") as raw:
        with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as compressed:
            compressed.write(tar_bytes.getvalue())
PY
[[ -s $RELEASE_OUTPUT_DIR/$artifact_name ]] || fail "The native archive was not created."
printf '%s\n' "$artifact_name"
