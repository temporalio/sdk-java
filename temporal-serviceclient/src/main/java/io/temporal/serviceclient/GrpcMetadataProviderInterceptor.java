/*
 *  Copyright (C) 2020 Temporal Technologies, Inc. All Rights Reserved.
 *
 *  Copyright 2012-2016 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 *  Modifications copyright (C) 2017 Uber Technologies, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"). You may not
 *  use this file except in compliance with the License. A copy of the License is
 *  located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 *  or in the "license" file accompanying this file. This file is distributed on
 *  an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language governing
 *  permissions and limitations under the License.
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
