package io.temporal.serviceclient;

import javax.annotation.Nullable;

/** Selects outbound transport-level gRPC compression for service calls. */
public enum GrpcCompression {
  /** Do not compress requests. */
  NONE(null),

  /** Gzip-compress requests. */
  GZIP("gzip");

  private final @Nullable String compressorName;

  GrpcCompression(@Nullable String compressorName) {
    this.compressorName = compressorName;
  }

  @Nullable
  String getCompressorName() {
    return compressorName;
  }
}
