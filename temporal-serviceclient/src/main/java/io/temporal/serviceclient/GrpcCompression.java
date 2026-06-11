package io.temporal.serviceclient;

import io.grpc.Codec;
import io.grpc.DecompressorRegistry;
import javax.annotation.Nullable;

/** Selects transport-level gRPC compression for service calls. */
public enum GrpcCompression {
  /** Do not compress requests or advertise support for compressed responses. */
  NONE(null, DecompressorRegistry.emptyInstance().with(Codec.Identity.NONE, false)),

  /** Gzip-compress outbound requests and accept gzip-compressed responses. */
  GZIP("gzip", DecompressorRegistry.getDefaultInstance());

  private final @Nullable String compressorName;
  private final DecompressorRegistry decompressorRegistry;

  GrpcCompression(@Nullable String compressorName, DecompressorRegistry decompressorRegistry) {
    this.compressorName = compressorName;
    this.decompressorRegistry = decompressorRegistry;
  }

  @Nullable
  String getCompressorName() {
    return compressorName;
  }

  DecompressorRegistry getDecompressorRegistry() {
    return decompressorRegistry;
  }
}
