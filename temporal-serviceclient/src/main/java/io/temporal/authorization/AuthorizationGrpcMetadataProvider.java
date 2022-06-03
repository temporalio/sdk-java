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
