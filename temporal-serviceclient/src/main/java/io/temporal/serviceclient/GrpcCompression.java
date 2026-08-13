package io.temporal.serviceclient;

import javax.annotation.Nullable;

/** Selects outbound transport-level gRPC compression for service calls. */
public enum GrpcCompression {
  /** Do not compress requests. */
  NONE(null),

  /**
   * Gzip-compress requests. If a specific server RPC does not support gzip, the SDK may retry that
   * RPC without compression and continue using gzip for other RPCs.
   */
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
