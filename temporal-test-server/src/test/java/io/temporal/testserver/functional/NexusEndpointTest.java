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

package io.temporal.testserver.functional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeFalse;

import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.Payload;
import io.temporal.api.nexus.v1.Endpoint;
import io.temporal.api.nexus.v1.EndpointSpec;
import io.temporal.api.nexus.v1.EndpointTarget;
import io.temporal.api.operatorservice.v1.*;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class NexusEndpointTest {
  @Rule public SDKTestWorkflowRule testWorkflowRule = SDKTestWorkflowRule.newBuilder().build();

  @Before
  public void checkExternal() {
    // TODO: remove this skip once 1.25.0 is officially released and
    // https://github.com/temporalio/sdk-java/issues/2165 is resolved
    assumeFalse(
        "Nexus APIs are not supported for server versions < 1.25.0",
        testWorkflowRule.isUseExternalService());
  }

  @Test
  public void testValidateEndpointSpec() {
    // Create and Update use same validation logic, so just test once
    EndpointSpec.Builder specBuilder = getTestEndpointSpecBuilder("valid-name-01");

    // Valid
    Endpoint testEndpoint = createTestEndpoint(specBuilder);
    assertEquals(1, testEndpoint.getVersion());
    assertEquals(specBuilder.build(), testEndpoint.getSpec());

    // Missing name
    specBuilder.setName("");
    StatusRuntimeException ex =
        assertThrows(StatusRuntimeException.class, () -> createTestEndpoint(specBuilder));
    assertEquals(Status.Code.INVALID_ARGUMENT, ex.getStatus().getCode());
    assertEquals("Nexus endpoint name cannot be empty", ex.getStatus().getDescription());

    // Name contains invalid characters
    specBuilder.setName("*(test)_- :invalid");
    ex = assertThrows(StatusRuntimeException.class, () -> createTestEndpoint(specBuilder));
    assertEquals(Status.Code.INVALID_ARGUMENT, ex.getStatus().getCode());
    assertEquals(
        "Nexus endpoint name ("
            + specBuilder.getName()
            + ") does not match expected pattern: ^[a-zA-Z][a-zA-Z0-9\\-]*[a-zA-Z0-9]$",
        ex.getStatus().getDescription());

    // Missing target
    specBuilder.setName("valid-name-02");
    specBuilder.clearTarget();
    ex = assertThrows(StatusRuntimeException.class, () -> createTestEndpoint(specBuilder));
    assertEquals(Status.Code.INVALID_ARGUMENT, ex.getStatus().getCode());
    assertEquals("Nexus endpoint spec must have a target", ex.getStatus().getDescription());

    // External target (test server only supports worker targets)
    specBuilder.setTarget(
        EndpointTarget.newBuilder()
            .setExternal(EndpointTarget.External.newBuilder().setUrl("localhost:8080")));
    ex = assertThrows(StatusRuntimeException.class, () -> createTestEndpoint(specBuilder));
    assertEquals(Status.Code.INVALID_ARGUMENT, ex.getStatus().getCode());
    assertEquals(
        "Test server only supports Nexus endpoints with worker targets",
        ex.getStatus().getDescription());
  }

  @Test
  public void testCreate() {
    EndpointSpec.Builder specBuilder = getTestEndpointSpecBuilder("valid-create-test-endpoint");

    // Valid create
    Endpoint testEndpoint = createTestEndpoint(specBuilder);
    assertEquals(1, testEndpoint.getVersion());
    assertEquals(specBuilder.build(), testEndpoint.getSpec());

    // Name already registered
    StatusRuntimeException ex =
        assertThrows(StatusRuntimeException.class, () -> createTestEndpoint(specBuilder));
    assertEquals(Status.Code.ALREADY_EXISTS, ex.getStatus().getCode());
    assertEquals(
        "Nexus endpoint already registered with name: " + specBuilder.getName(),
        ex.getStatus().getDescription());
  }

  @Test
  public void testUpdate() {
    // Setup
    Endpoint testEndpoint = createTestEndpoint(getTestEndpointSpecBuilder("update-test-endpoint"));
    assertEquals(1, testEndpoint.getVersion());
    EndpointSpec updatedSpec =
        EndpointSpec.newBuilder(testEndpoint.getSpec())
            .setDescription(
                Payload.newBuilder().setData(ByteString.copyFromUtf8("updated description")))
            .build();

    // Not found
    String missingID = UUID.randomUUID().toString();
    StatusRuntimeException ex =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                testWorkflowRule
                    .getTestEnvironment()
                    .getOperatorServiceStubs()
                    .blockingStub()
                    .updateNexusEndpoint(
                        UpdateNexusEndpointRequest.newBuilder()
                            .setId(missingID)
                            .setVersion(testEndpoint.getVersion())
                            .setSpec(updatedSpec)
                            .build()));
    assertEquals(Status.Code.NOT_FOUND, ex.getStatus().getCode());
    assertEquals(
        "Could not find Nexus endpoint with ID: " + missingID, ex.getStatus().getDescription());

    // Version mismatch
    ex =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                testWorkflowRule
                    .getTestEnvironment()
                    .getOperatorServiceStubs()
                    .blockingStub()
                    .updateNexusEndpoint(
                        UpdateNexusEndpointRequest.newBuilder()
                            .setId(testEndpoint.getId())
                            .setVersion(15)
                            .setSpec(updatedSpec)
                            .build()));
    assertEquals(Status.Code.INVALID_ARGUMENT, ex.getStatus().getCode());
    assertEquals(
        "Error updating Nexus endpoint: version mismatch."
            + " Expected: "
            + testEndpoint.getVersion()
            + " Received: "
            + 15,
        ex.getStatus().getDescription());

    // Updated name already registered
    EndpointSpec.Builder otherSpec = getTestEndpointSpecBuilder("other-test-endpoint");
    createTestEndpoint(otherSpec);
    ex =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                testWorkflowRule
                    .getTestEnvironment()
                    .getOperatorServiceStubs()
                    .blockingStub()
                    .updateNexusEndpoint(
                        UpdateNexusEndpointRequest.newBuilder()
                            .setId(testEndpoint.getId())
                            .setVersion(testEndpoint.getVersion())
                            .setSpec(otherSpec.build())
                            .build()));
    assertEquals(Status.Code.ALREADY_EXISTS, ex.getStatus().getCode());
    assertEquals(
        "Error updating Nexus endpoint: "
            + "endpoint already registered with updated name: "
            + otherSpec.getName(),
        ex.getStatus().getDescription());

    // Valid update
    UpdateNexusEndpointResponse resp =
        testWorkflowRule
            .getTestEnvironment()
            .getOperatorServiceStubs()
            .blockingStub()
            .updateNexusEndpoint(
                UpdateNexusEndpointRequest.newBuilder()
                    .setId(testEndpoint.getId())
                    .setVersion(testEndpoint.getVersion())
                    .setSpec(updatedSpec)
                    .build());
    assertEquals(2, resp.getEndpoint().getVersion());
    assertEquals(updatedSpec, resp.getEndpoint().getSpec());
  }

  @Test
  public void testDelete() {
    // Setup
    Endpoint testEndpoint = createTestEndpoint(getTestEndpointSpecBuilder("delete-test-endpoint"));
    assertEquals(1, testEndpoint.getVersion());

    // Not found
    String missingID = UUID.randomUUID().toString();
    StatusRuntimeException ex =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                testWorkflowRule
                    .getTestEnvironment()
                    .getOperatorServiceStubs()
                    .blockingStub()
                    .deleteNexusEndpoint(
                        DeleteNexusEndpointRequest.newBuilder()
                            .setId(missingID)
                            .setVersion(testEndpoint.getVersion())
                            .build()));
    assertEquals(Status.Code.NOT_FOUND, ex.getStatus().getCode());
    assertEquals(
        "Could not find Nexus endpoint with ID: " + missingID, ex.getStatus().getDescription());

    // Version mismatch
    ex =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                testWorkflowRule
                    .getTestEnvironment()
                    .getOperatorServiceStubs()
                    .blockingStub()
                    .deleteNexusEndpoint(
                        DeleteNexusEndpointRequest.newBuilder()
                            .setId(testEndpoint.getId())
                            .setVersion(15)
                            .build()));
    assertEquals(Status.Code.INVALID_ARGUMENT, ex.getStatus().getCode());
    assertEquals(
        "Error deleting Nexus endpoint: version mismatch."
            + " Expected "
            + testEndpoint.getVersion()
            + " Received: "
            + 15,
        ex.getStatus().getDescription());

    // Valid delete
    DeleteNexusEndpointResponse resp =
        testWorkflowRule
            .getTestEnvironment()
            .getOperatorServiceStubs()
            .blockingStub()
            .deleteNexusEndpoint(
                DeleteNexusEndpointRequest.newBuilder()
                    .setId(testEndpoint.getId())
                    .setVersion(testEndpoint.getVersion())
                    .build());
    assertEquals(DeleteNexusEndpointResponse.newBuilder().build(), resp);
  }

  @Test
  public void testGet() {
    // Setup
    Endpoint testEndpoint = createTestEndpoint(getTestEndpointSpecBuilder("get-test-endpoint"));
    assertEquals(1, testEndpoint.getVersion());

    // Not found
    String missingID = UUID.randomUUID().toString();
    StatusRuntimeException ex =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                testWorkflowRule
                    .getTestEnvironment()
                    .getOperatorServiceStubs()
                    .blockingStub()
                    .getNexusEndpoint(
                        GetNexusEndpointRequest.newBuilder().setId(missingID).build()));
    assertEquals(Status.Code.NOT_FOUND, ex.getStatus().getCode());
    assertEquals(
        "Could not find Nexus endpoint with ID: " + missingID, ex.getStatus().getDescription());

    // Valid get
    GetNexusEndpointResponse resp =
        testWorkflowRule
            .getTestEnvironment()
            .getOperatorServiceStubs()
            .blockingStub()
            .getNexusEndpoint(
                GetNexusEndpointRequest.newBuilder().setId(testEndpoint.getId()).build());
    assertEquals(testEndpoint, resp.getEndpoint());
  }

  @Test
  public void testList() {
    // Setup
    List<Endpoint> testEndpoints = new ArrayList<>(3);
    for (int i = 0; i < 3; i++) {
      testEndpoints.add(createTestEndpoint(getTestEndpointSpecBuilder("list-test-endpoint-" + i)));
    }
    testEndpoints.sort(Comparator.comparing(Endpoint::getId));

    // List with filter for non-existent name
    ListNexusEndpointsResponse resp =
        testWorkflowRule
            .getTestEnvironment()
            .getOperatorServiceStubs()
            .blockingStub()
            .listNexusEndpoints(
                ListNexusEndpointsRequest.newBuilder().setName("some-missing-name").build());
    assertEquals(0, resp.getEndpointsCount());

    // List with filter for existing name
    resp =
        testWorkflowRule
            .getTestEnvironment()
            .getOperatorServiceStubs()
            .blockingStub()
            .listNexusEndpoints(
                ListNexusEndpointsRequest.newBuilder()
                    .setName(testEndpoints.get(1).getSpec().getName())
                    .build());
    assertEquals(1, resp.getEndpointsCount());
    assertEquals(testEndpoints.get(1), resp.getEndpoints(0));

    // List all
    resp =
        testWorkflowRule
            .getTestEnvironment()
            .getOperatorServiceStubs()
            .blockingStub()
            .listNexusEndpoints(ListNexusEndpointsRequest.newBuilder().setPageSize(10).build());
    assertEquals(testEndpoints.size(), resp.getEndpointsCount());
    assertEquals(ByteString.empty(), resp.getNextPageToken());
    assertEquals(testEndpoints, resp.getEndpointsList());

    // List page 1
    resp =
        testWorkflowRule
            .getTestEnvironment()
            .getOperatorServiceStubs()
            .blockingStub()
            .listNexusEndpoints(ListNexusEndpointsRequest.newBuilder().setPageSize(2).build());
    assertEquals(2, resp.getEndpointsCount());
    assertEquals(testEndpoints.get(1).getIdBytes(), resp.getNextPageToken());
    assertEquals(testEndpoints.subList(0, 2), resp.getEndpointsList());

    // List page 2
    resp =
        testWorkflowRule
            .getTestEnvironment()
            .getOperatorServiceStubs()
            .blockingStub()
            .listNexusEndpoints(
                ListNexusEndpointsRequest.newBuilder()
                    .setPageSize(2)
                    .setNextPageToken(resp.getNextPageToken())
                    .build());
    assertEquals(1, resp.getEndpointsCount());
    assertEquals(ByteString.empty(), resp.getNextPageToken());
    assertEquals(testEndpoints.subList(2, testEndpoints.size()), resp.getEndpointsList());
  }

  private EndpointSpec.Builder getTestEndpointSpecBuilder(String name) {
    return EndpointSpec.newBuilder()
        .setName(name)
        .setDescription(Payload.newBuilder().setData(ByteString.copyFromUtf8("test endpoint")))
        .setTarget(
            EndpointTarget.newBuilder()
                .setWorker(
                    EndpointTarget.Worker.newBuilder()
                        .setNamespace(testWorkflowRule.getTestEnvironment().getNamespace())
                        .setTaskQueue(testWorkflowRule.getTaskQueue())));
  }

  private Endpoint createTestEndpoint(EndpointSpec.Builder spec) {
    CreateNexusEndpointResponse resp =
        testWorkflowRule
            .getTestEnvironment()
            .getOperatorServiceStubs()
            .blockingStub()
            .createNexusEndpoint(CreateNexusEndpointRequest.newBuilder().setSpec(spec).build());
    return resp.getEndpoint();
  }
}
