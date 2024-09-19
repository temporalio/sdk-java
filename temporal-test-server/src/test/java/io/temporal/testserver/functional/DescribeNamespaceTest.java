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

import static org.junit.Assert.*;

import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.enums.v1.NamespaceState;
import io.temporal.api.workflowservice.v1.DescribeNamespaceRequest;
import io.temporal.api.workflowservice.v1.DescribeNamespaceResponse;
import io.temporal.internal.docker.RegisterTestNamespace;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import org.junit.Rule;
import org.junit.Test;

public class DescribeNamespaceTest {
  @Rule public SDKTestWorkflowRule testWorkflowRule = SDKTestWorkflowRule.newBuilder().build();

  @Test
  public void testDescribeNamespace() {
    DescribeNamespaceResponse describeNamespaceResponse =
        testWorkflowRule
            .getWorkflowServiceStubs()
            .blockingStub()
            .describeNamespace(
                DescribeNamespaceRequest.newBuilder()
                    .setNamespace(RegisterTestNamespace.NAMESPACE)
                    .build());
    assertEquals(
        NamespaceState.NAMESPACE_STATE_REGISTERED,
        describeNamespaceResponse.getNamespaceInfo().getState());
    assertEquals(
        RegisterTestNamespace.NAMESPACE, describeNamespaceResponse.getNamespaceInfo().getName());
    assertTrue(describeNamespaceResponse.getNamespaceInfo().getId().length() > 0);
    assertNotNull(describeNamespaceResponse.getNamespaceInfo().getCapabilities());
    assertTrue(describeNamespaceResponse.getNamespaceInfo().getCapabilities().getEagerWorkflowStart());
    assertTrue(describeNamespaceResponse.getNamespaceInfo().getCapabilities().getSyncUpdate());
    assertTrue(describeNamespaceResponse.getNamespaceInfo().getCapabilities().getAsyncUpdate());
  }

  @Test
  public void noNamespaceSet() {
    StatusRuntimeException ex =
        assertThrows(
            StatusRuntimeException.class,
            () ->
                testWorkflowRule
                    .getWorkflowServiceStubs()
                    .blockingStub()
                    .describeNamespace(DescribeNamespaceRequest.newBuilder().build()));
    assertEquals(Status.Code.INVALID_ARGUMENT, ex.getStatus().getCode());
    assertEquals("Namespace not set on request.", ex.getStatus().getDescription());
  }
}
