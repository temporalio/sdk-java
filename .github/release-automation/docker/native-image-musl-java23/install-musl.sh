#!/usr/bin/env bash
set -euo pipefail
MUSL_VERSION=1.2.5
ZLIB_VERSION=1.2.13
export MUSL_HOME=/opt/musl-toolchain
curl --fail --location --retry 5 --retry-all-errors \
  --output "musl-$MUSL_VERSION.tar.gz" \
  "https://musl.libc.org/releases/musl-$MUSL_VERSION.tar.gz"
curl --fail --location --retry 5 --retry-all-errors \
  --output "zlib-$ZLIB_VERSION.tar.gz" \
  "https://github.com/madler/zlib/releases/download/v$ZLIB_VERSION/zlib-$ZLIB_VERSION.tar.gz"
tar -xzf "musl-$MUSL_VERSION.tar.gz"
cd "musl-$MUSL_VERSION"
./configure --prefix="$MUSL_HOME" --static
make -j"$(nproc)"
make install
cd ..
ln -sf "$MUSL_HOME/bin/musl-gcc" "$MUSL_HOME/bin/x86_64-linux-musl-gcc"
export PATH="$MUSL_HOME/bin:$PATH"
tar -xzf "zlib-$ZLIB_VERSION.tar.gz"
cd "zlib-$ZLIB_VERSION"
CC=musl-gcc ./configure --prefix="$MUSL_HOME" --static
make -j"$(nproc)"
make install
