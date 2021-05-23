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

public class HealthCheckTest {

  private static final boolean useDockerService =
      Boolean.parseBoolean(System.getenv("USE_DOCKER_SERVICE"));

  /**
   * This test needs to be performed with env variable USE_DOCKER_SERVICE=true. It should fail if
   * docker is not up or returns health status other than SERVING.
   */
  @Test
  public void testHealthCheck() {
    if (useDockerService) {
      WorkflowServiceStubs.newInstance(
          WorkflowServiceStubsOptions.newBuilder().setEnableHealthCheck(true).build());
    }
  }

  @Test
  public void testFaiLedHealthCheck() {
    if (useDockerService) {
      WorkflowServiceStubsImpl service =
          (WorkflowServiceStubsImpl)
              WorkflowServiceStubs.newInstance(
                  WorkflowServiceStubsOptions.newBuilder().setEnableHealthCheck(true).build());
      try {
        service.checkHealth("IncorrectServiceName");
      } catch (RuntimeException e) {
        Assert.assertTrue(e.getMessage().startsWith("Health check returned unhealthy status: "));
      }
    }
  }
}
