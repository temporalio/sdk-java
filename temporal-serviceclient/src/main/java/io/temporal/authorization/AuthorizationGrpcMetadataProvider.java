package io.temporal.authorization;

import io.grpc.Metadata;
import io.temporal.serviceclient.GrpcMetadataProvider;

public class AuthorizationGrpcMetadataProvider implements GrpcMetadataProvider {
  public static final Metadata.Key<String> AUTHORIZATION_HEADER_KEY =
      Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

  private final AuthorizationTokenSupplier authorizationTokenSupplier;

  public AuthorizationGrpcMetadataProvider(AuthorizationTokenSupplier authorizationTokenSupplier) {
    this.authorizationTokenSupplier = authorizationTokenSupplier;
  }

  @Override
  public Metadata getMetadata() {
    Metadata metadata = new Metadata();
    metadata.put(AUTHORIZATION_HEADER_KEY, authorizationTokenSupplier.supply());
    return metadata;
  }
}
