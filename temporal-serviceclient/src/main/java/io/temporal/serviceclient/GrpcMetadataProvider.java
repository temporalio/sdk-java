package io.temporal.serviceclient;

import io.grpc.Metadata;

/** Provides additional Metadata (gRPC Headers) that should be used on every request. */
public interface GrpcMetadataProvider {
  Metadata getMetadata();
}
