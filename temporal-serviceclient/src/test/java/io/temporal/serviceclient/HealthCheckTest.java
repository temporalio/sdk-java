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

import org.junit.Assert;
import org.junit.Test;

/**
 * This test needs to be performed with env variable USE_DOCKER_SERVICE=true. It should fail if
 * docker is not up or returns health status other than SERVING.
 */
public class HealthCheckTest {

  private static final boolean useDockerService =
      Boolean.parseBoolean(System.getenv("USE_DOCKER_SERVICE"));
  private static final String HEALTH_CHECK_SERVICE_NAME =
      "temporal.api.workflowservice.v1.WorkflowService";

  @Test
  public void testHealthCheck() {
    if (useDockerService) {
      try {
        WorkflowServiceStubsImpl service =
            (WorkflowServiceStubsImpl) WorkflowServiceStubs.newInstance();
        service.checkHealth(HEALTH_CHECK_SERVICE_NAME);
      } catch (Exception e) {
        Assert.fail("Health check failed");
      }
    }
  }

  @Test
  public void testUnhealthyStatus() {
    if (useDockerService) {
      try {
        WorkflowServiceStubsImpl service =
            (WorkflowServiceStubsImpl) WorkflowServiceStubs.newInstance();
        service.checkHealth("IncorrectServiceName");
        Assert.fail("Health check for IncorrectServiceName should fail");
      } catch (RuntimeException e) {
        Assert.assertTrue(e.getMessage().startsWith("Health check returned unhealthy status: "));
      }
    }
  }
}
