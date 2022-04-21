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

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.api.operatorservice.v1.*;
import java.io.Closeable;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In memory implementation of the Operator Service. To be used for testing purposes only.
 *
 * <p>Do not use directly, instead use {@link io.temporal.testing.TestWorkflowEnvironment}.
 */
final class TestOperatorService extends OperatorServiceGrpc.OperatorServiceImplBase
    implements Closeable {
  private static final Logger log = LoggerFactory.getLogger(TestOperatorService.class);

  private final TestVisibilityStore visibilityStore;

  public TestOperatorService(TestVisibilityStore visibilityStore) {
    this.visibilityStore = visibilityStore;
  }

  @Override
  public void addSearchAttributes(
      AddSearchAttributesRequest request,
      StreamObserver<AddSearchAttributesResponse> responseObserver) {
    try {
      Map<String, IndexedValueType> registeredSearchAttributes =
          visibilityStore.getRegisteredSearchAttributes();
      request.getSearchAttributesMap().keySet().stream()
          .filter(registeredSearchAttributes::containsKey)
          .findFirst()
          .ifPresent(
              sa -> {
                throw Status.ALREADY_EXISTS
                    .withDescription("Search attribute " + sa + " already exists.")
                    .asRuntimeException();
              });
      request.getSearchAttributesMap().forEach(visibilityStore::addSearchAttribute);
      responseObserver.onNext(AddSearchAttributesResponse.newBuilder().build());
      responseObserver.onCompleted();
    } catch (StatusRuntimeException e) {
      handleStatusRuntimeException(e, responseObserver);
    }
  }

  @Override
  public void removeSearchAttributes(
      RemoveSearchAttributesRequest request,
      StreamObserver<RemoveSearchAttributesResponse> responseObserver) {
    try {
      Map<String, IndexedValueType> registeredSearchAttributes =
          visibilityStore.getRegisteredSearchAttributes();
      request.getSearchAttributesList().stream()
          .filter(k -> !registeredSearchAttributes.containsKey(k))
          .findFirst()
          .ifPresent(
              sa -> {
                throw Status.NOT_FOUND
                    .withDescription("Search attribute " + sa + " doesn't exist.")
                    .asRuntimeException();
              });
      request.getSearchAttributesList().forEach(visibilityStore::removeSearchAttribute);
      responseObserver.onNext(RemoveSearchAttributesResponse.newBuilder().build());
      responseObserver.onCompleted();
    } catch (StatusRuntimeException e) {
      handleStatusRuntimeException(e, responseObserver);
    }
  }

  private void handleStatusRuntimeException(
      StatusRuntimeException e, StreamObserver<?> responseObserver) {
    if (e.getStatus().getCode() == Status.Code.INTERNAL) {
      log.error("unexpected", e);
    }
    responseObserver.onError(e);
  }

  @Override
  public void close() {}
}
