package io.temporal.serviceclient;

import static com.google.common.base.Preconditions.checkNotNull;

import io.grpc.*;
import java.util.Collection;

public class GrpcMetadataProviderInterceptor implements ClientInterceptor {
  private final Collection<GrpcMetadataProvider> grpcMetadataProviders;
  private final boolean override;

  public GrpcMetadataProviderInterceptor(Collection<GrpcMetadataProvider> grpcMetadataProviders) {
    this.grpcMetadataProviders = checkNotNull(grpcMetadataProviders, "grpcMetadataProviders");
    this.override = false;
  }

  public GrpcMetadataProviderInterceptor(
      Collection<GrpcMetadataProvider> grpcMetadataProviders, boolean override) {
    this.grpcMetadataProviders = checkNotNull(grpcMetadataProviders, "grpcMetadataProviders");
    this.override = override;
  }

  @Override
  public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
      MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
    return new HeaderAttachingClientCall<>(next.newCall(method, callOptions));
  }

  private final class HeaderAttachingClientCall<ReqT, RespT>
      extends ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT> {

    HeaderAttachingClientCall(ClientCall<ReqT, RespT> call) {
      super(call);
    }

    @Override
    public void start(Listener<RespT> responseListener, Metadata headers) {
      grpcMetadataProviders.stream()
          .map(GrpcMetadataProvider::getMetadata)
          .forEach(
              m -> {
                // If override is true, discard all existing headers with the same key
                // before adding the new ones. Otherwise, merge will add the new value to the
                // existing key.
                if (override) {
                  for (String key : m.keys()) {
                    headers.discardAll(Metadata.Key.of(key, Metadata.ASCII_STRING_MARSHALLER));
                  }
                }
                headers.merge(m);
              });
      super.start(responseListener, headers);
    }
  }
}
