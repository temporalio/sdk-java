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

package io.temporal.workflow;

import static org.junit.Assert.*;

import io.temporal.client.*;
import io.temporal.failure.TerminatedFailure;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestActivities.TestActivitiesImpl;
import io.temporal.workflow.shared.TestActivities.VariousTestActivities;
import io.temporal.workflow.shared.TestWorkflows.TestTraceWorkflow;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

/**
 * Tests verifying the correct behavior of the SDK if the workflow is in unsuccessful final states
 */
public class TerminatedWorkflowTest {
  private final TestActivitiesImpl activitiesImpl = new TestActivitiesImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TraceTimingOutWorkflowImpl.class)
          .setActivityImplementations(activitiesImpl)
          .setWorkflowClientOptions(WorkflowClientOptions.newBuilder().build())
          .build();

  @Test
  public void testShouldReturnQueryResultAfterWorkflowTimeout() {
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue()).toBuilder()
            .setWorkflowRunTimeout(Duration.ofSeconds(1))
            .build();
    TestTraceWorkflow workflow =
        testWorkflowRule.getWorkflowClient().newWorkflowStub(TestTraceWorkflow.class, options);

    Assert.assertThrows(
        "Workflow should throw because of timeout",
        WorkflowFailedException.class,
        workflow::execute);

    Assert.assertEquals(1, workflow.getTrace().size());
    Assert.assertEquals("started", workflow.getTrace().get(0));
  }

  @Test
  public void getResultShouldThrowAfterTerminationOfWorkflow() {
    WorkflowOptions options =
        WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.getTaskQueue()).build();

    WorkflowStub workflow =
        testWorkflowRule.getWorkflowClient().newUntypedWorkflowStub("execute", options);

    workflow.start();

    workflow.terminate("testing");

    WorkflowFailedException exception = null;
    try {
      workflow.getResult(1000, TimeUnit.MILLISECONDS, String.class);
      fail("getResult should throw WorkflowFailedException because the workflow was terminated");
    } catch (WorkflowFailedException e) {
      // This is expected
      exception = e;
    } catch (TimeoutException e) {
      fail(
          "getResult shouldn't wait all 5 seconds till the end of the workflow because it was already terminated");
    }
    assertNotNull(exception);
    assertTrue(exception.getCause() instanceof TerminatedFailure);
  }

  public static class TraceTimingOutWorkflowImpl implements TestTraceWorkflow {
    private final List<String> trace = new ArrayList<>();

    @Override
    public String execute() {
      VariousTestActivities localActivities =
          Workflow.newLocalActivityStub(
              VariousTestActivities.class, SDKTestOptions.newLocalActivityOptions());

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
