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
