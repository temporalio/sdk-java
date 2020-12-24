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

package io.temporal.workflow;

import io.temporal.api.common.v1.Payloads;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.converter.DataConverter;
import java.util.Optional;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.Timeout;

public class UntypedWorkflowTest {

  @Rule public Timeout globalTimeout = Timeout.seconds(10);

  @Rule
  public TestWorkflowRule testWorkflowRule =
      TestWorkflowRule.newBuilder()
          .setWorkflowTypes(UntypedWorkflowImpl.class)
          .setUseExternalService(Boolean.parseBoolean(System.getenv("USE_DOCKER_SERVICE")))
          .setTarget(System.getenv("TEMPORAL_SERVICE_ADDRESS"))
          .build();

  public static class UntypedWorkflowImpl implements UntypedWorkflow {

    @Override
    public Optional<Payloads> execute(Optional<Payloads> input, DataConverter dataConverter) {
      String type = Workflow.getInfo().getWorkflowType();
      String arg0 =
          dataConverter.fromPayload(input.get().getPayloads(0), String.class, String.class);
      return dataConverter.toPayloads(arg0 + type);
    }
  }

  /**
   * This test runs a workflow that executes multiple activities in the single handler thread. Test
   * workflow is configured to fail with heartbeat timeout errors in case if activity pollers are
   * too eager to poll tasks before previously fetched tasks are handled.
   */
  @Test
  public void testUntypedWorkflow() {
    WorkflowOptions workflowOptions =
        WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.getTaskQueue()).build();
    WorkflowStub workflow =
        testWorkflowRule.getWorkflowClient().newUntypedWorkflowStub("workflowFoo", workflowOptions);
    workflow.start("arg0-");
    String result = workflow.getResult(String.class);
    Assert.assertEquals("arg0-workflowFoo", result);
  }
}
