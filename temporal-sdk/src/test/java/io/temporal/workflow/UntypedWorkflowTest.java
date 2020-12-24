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

import static org.junit.Assert.assertEquals;

import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.converter.EncodedValues;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;

public class UntypedWorkflowTest {

  @Rule
  public TestWorkflowRule testWorkflowRule =
      TestWorkflowRule.newBuilder()
          .setWorkflowTypes(UntypedWorkflowImpl.class)
          .setUseExternalService(Boolean.parseBoolean(System.getenv("USE_DOCKER_SERVICE")))
          .setTarget(System.getenv("TEMPORAL_SERVICE_ADDRESS"))
          .build();

  public static class UntypedWorkflowImpl implements UntypedWorkflow {

    @Override
    public Object execute(EncodedValues args) {
      List<String> signals = new ArrayList<>();
      String type = Workflow.getInfo().getWorkflowType();
      Workflow.registerListener(
          (UntypedSignalHandler)
              (signalName, encodedArgs) ->
                  signals.add(signalName + "-" + encodedArgs.get(0, String.class)));
      Workflow.registerListener(
          (UntypedQueryHandler)
              (queryType, encodedArgs) ->
                  queryType
                      + "-"
                      + encodedArgs.get(0, String.class)
                      + "-"
                      + signals.get(signals.size() - 1));
      String arg0 = args.get(0, String.class);
      return arg0 + "-" + type;
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
    workflow.signalWithStart("signal1", new Object[] {"signalArg0"}, new Object[] {"startArg0"});
    String queryResult = workflow.query("query1", String.class, "queryArg0");
    assertEquals("query1-queryArg0-signal1-signalArg0", queryResult);
    String result = workflow.getResult(String.class);
    assertEquals("startArg0-workflowFoo", result);
  }
}
