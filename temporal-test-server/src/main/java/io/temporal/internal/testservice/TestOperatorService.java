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

package io.temporal.internal.testservice;

import io.grpc.stub.StreamObserver;
import io.temporal.api.operatorservice.v1.*;
import java.io.Closeable;

/**
 * In memory implementation of the Operator Service. To be used for testing purposes only.
 *
 * <p>Do not use directly, instead use {@link io.temporal.testing.TestWorkflowEnvironment}.
 */
final class TestOperatorService extends OperatorServiceGrpc.OperatorServiceImplBase
    implements Closeable {

  private final TestVisibilityStore visibilityStore;

  public TestOperatorService(TestVisibilityStore visibilityStore) {
    this.visibilityStore = visibilityStore;
  }

  @Override
  public void addSearchAttributes(
      AddSearchAttributesRequest request,
      StreamObserver<AddSearchAttributesResponse> responseObserver) {
    request.getSearchAttributesMap().forEach(visibilityStore::registerSearchAttribute);
    responseObserver.onNext(AddSearchAttributesResponse.newBuilder().build());
    responseObserver.onCompleted();
  }

  @Override
  public void removeSearchAttributes(
      RemoveSearchAttributesRequest request,
      StreamObserver<RemoveSearchAttributesResponse> responseObserver) {
    super.removeSearchAttributes(request, responseObserver);
  }

  @Override
  public void listSearchAttributes(
      ListSearchAttributesRequest request,
      StreamObserver<ListSearchAttributesResponse> responseObserver) {
    super.listSearchAttributes(request, responseObserver);
  }

  @Override
  public void close() {}
}
