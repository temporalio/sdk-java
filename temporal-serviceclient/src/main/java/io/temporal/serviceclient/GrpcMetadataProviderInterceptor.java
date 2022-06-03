/*
 * Copyright (C) 2022 Temporal Technologies, Inc. All Rights Reserved.
 *
 * Copyright (C) 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this material except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.temporal.serviceclient;

import static com.google.common.base.Preconditions.checkNotNull;

import io.grpc.*;
import java.util.Collection;

public class GrpcMetadataProviderInterceptor implements ClientInterceptor {
  private final Collection<GrpcMetadataProvider> grpcMetadataProviders;

  public GrpcMetadataProviderInterceptor(Collection<GrpcMetadataProvider> grpcMetadataProviders) {
    this.grpcMetadataProviders = checkNotNull(grpcMetadataProviders, "grpcMetadataProviders");
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
      grpcMetadataProviders.stream().map(GrpcMetadataProvider::getMetadata).forEach(headers::merge);
      super.start(responseListener, headers);
    }
  }
}
