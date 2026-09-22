#!/usr/bin/env bash
set -euo pipefail

readonly MUSL_VERSION=1.2.5
readonly ZLIB_VERSION=1.2.13

export MUSL_HOME="$PWD/musl-toolchain"

curl --fail --location --retry 5 --retry-all-errors --output "musl-${MUSL_VERSION}.tar.gz" \
  "https://musl.libc.org/releases/musl-${MUSL_VERSION}.tar.gz"
curl --fail --location --retry 5 --retry-all-errors --output "zlib-${ZLIB_VERSION}.tar.gz" \
  "https://github.com/madler/zlib/releases/download/v${ZLIB_VERSION}/zlib-${ZLIB_VERSION}.tar.gz"

# Build musl from source.
tar -xzf "musl-${MUSL_VERSION}.tar.gz"
cd "musl-${MUSL_VERSION}"
./configure --prefix=$MUSL_HOME --static
make -j"$(nproc)"
make install
cd ..

# Install a symlink for use by native-image.
ln -sf "$MUSL_HOME/bin/musl-gcc" "$MUSL_HOME/bin/x86_64-linux-musl-gcc"

# Extend the system path and confirm that musl is available by printing its version.
export PATH="$MUSL_HOME/bin:$PATH"
x86_64-linux-musl-gcc --version

# Build zlib with musl from source and install into the MUSL_HOME directory.
tar -xzf "zlib-${ZLIB_VERSION}.tar.gz"
cd "zlib-${ZLIB_VERSION}"
CC=musl-gcc ./configure --prefix=$MUSL_HOME --static
make -j"$(nproc)"
make install
cd ..
