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

import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.workflow.shared.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestOptions;
import io.temporal.workflow.shared.TestWorkflows.TestTraceWorkflow;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class TerminatedWorkflowQueryTest {

  private final TestActivitiesImpl activitiesImpl = new TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestTraceWorkflowImpl.class)
          .setActivityImplementations(activitiesImpl)
          .setWorkflowClientOptions(WorkflowClientOptions.newBuilder().build())
          .build();

  @Test
  public void testShouldReturnQueryResultAfterWorkflowTimeout() {
    WorkflowOptions options =
        TestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
            .toBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(1))
            .build();
    TestTraceWorkflow client =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestTraceWorkflow.class, options);

    Assert.assertThrows(
        "Workflow should throw because of timeout",
        WorkflowFailedException.class,
        () -> client.execute(SDKTestWorkflowRule.useExternalService));

    Assert.assertEquals(1, client.getTrace().size());
    Assert.assertEquals("started", client.getTrace().get(0));
  }

  public static class TestTraceWorkflowImpl implements TestTraceWorkflow {

    private final List<String> trace = new ArrayList<>();

    @Override
    public String execute(boolean useExternalService) {
      VariousTestActivities localActivities =
          Workflow.newLocalActivityStub(
              VariousTestActivities.class, TestOptions.newLocalActivityOptions());

      trace.add("started");
      localActivities.sleepActivity(5000, 123);
      trace.add("finished");
      return "";
    }

    @Override
    public List<String> getTrace() {
      return trace;
    }
  }
}
