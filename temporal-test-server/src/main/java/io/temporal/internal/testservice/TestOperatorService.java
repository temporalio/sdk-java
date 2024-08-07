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

package io.temporal.internal.testservice;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.api.nexus.v1.Endpoint;
import io.temporal.api.operatorservice.v1.*;
import java.io.Closeable;
import java.util.List;
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
  private final TestNexusEndpointStore nexusEndpointStore;

  public TestOperatorService(
      TestVisibilityStore visibilityStore, TestNexusEndpointStore nexusEndpointStore) {
    this.visibilityStore = visibilityStore;
    this.nexusEndpointStore = nexusEndpointStore;
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

  @Override
  public void getNexusEndpoint(
      GetNexusEndpointRequest request, StreamObserver<GetNexusEndpointResponse> responseObserver) {
    try {
      Endpoint endpoint = nexusEndpointStore.getEndpoint(request.getId());
      responseObserver.onNext(GetNexusEndpointResponse.newBuilder().setEndpoint(endpoint).build());
      responseObserver.onCompleted();
    } catch (StatusRuntimeException e) {
      handleStatusRuntimeException(e, responseObserver);
    }
  }

  @Override
  public void createNexusEndpoint(
      CreateNexusEndpointRequest request,
      StreamObserver<CreateNexusEndpointResponse> responseObserver) {
    try {
      Endpoint created = nexusEndpointStore.createEndpoint(request.getSpec());
      responseObserver.onNext(
          CreateNexusEndpointResponse.newBuilder().setEndpoint(created).build());
      responseObserver.onCompleted();
    } catch (StatusRuntimeException e) {
      handleStatusRuntimeException(e, responseObserver);
    }
  }

  @Override
  public void updateNexusEndpoint(
      UpdateNexusEndpointRequest request,
      StreamObserver<UpdateNexusEndpointResponse> responseObserver) {
    try {
      Endpoint updated =
          nexusEndpointStore.updateEndpoint(
              request.getId(), request.getVersion(), request.getSpec());
      responseObserver.onNext(
          UpdateNexusEndpointResponse.newBuilder().setEndpoint(updated).build());
      responseObserver.onCompleted();
    } catch (StatusRuntimeException e) {
      handleStatusRuntimeException(e, responseObserver);
    }
  }

  @Override
  public void deleteNexusEndpoint(
      DeleteNexusEndpointRequest request,
      StreamObserver<DeleteNexusEndpointResponse> responseObserver) {
    try {
      nexusEndpointStore.deleteEndpoint(request.getId(), request.getVersion());
      responseObserver.onNext(DeleteNexusEndpointResponse.newBuilder().build());
      responseObserver.onCompleted();
    } catch (StatusRuntimeException e) {
      handleStatusRuntimeException(e, responseObserver);
    }
  }

  @Override
  public void listNexusEndpoints(
      ListNexusEndpointsRequest request,
      StreamObserver<ListNexusEndpointsResponse> responseObserver) {
    try {
      List<Endpoint> endpoints =
          nexusEndpointStore.listEndpoints(
              request.getPageSize(), request.getNextPageToken().toByteArray(), request.getName());
      ByteString nextPageToken =
          (!endpoints.isEmpty() && endpoints.size() == request.getPageSize())
              ? endpoints.get(endpoints.size() - 1).getIdBytes()
              : ByteString.empty();
      responseObserver.onNext(
          ListNexusEndpointsResponse.newBuilder()
              .addAllEndpoints(endpoints)
              .setNextPageToken(nextPageToken)
              .build());
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
