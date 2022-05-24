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

package io.temporal.serviceclient.functional;

import static org.junit.Assert.assertEquals;

import io.grpc.health.v1.HealthCheckResponse;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import org.junit.Rule;
import org.junit.Test;

public class HealthCheckTest {

  @Rule public SDKTestWorkflowRule testWorkflowRule = SDKTestWorkflowRule.newBuilder().build();

  @Test
  public void testHealthCheck() {
    HealthCheckResponse healthCheckResponse =
        testWorkflowRule.getWorkflowServiceStubs().healthCheck();
    assertEquals(HealthCheckResponse.ServingStatus.SERVING, healthCheckResponse.getStatus());
  }
}
