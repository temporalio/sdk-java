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

import static org.junit.Assert.fail;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.TerminateWorkflowExecutionRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerFactoryOptions;
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

public class TerminatedWorkflowQueryTest {
  static final String TASK_QUEUE = "HelloSignalTaskQueue";
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
        SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
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

  @Test
  public void testShouldReturnQueryResultAfterTerminatedWorkflow() {
    // Get a Workflow service client which can be used to start, Signal, and Query Workflow
    // Executions.
    TestWorkflowEnvironment testEnv =
        TestWorkflowEnvironment.newInstance(
            TestEnvironmentOptions.newBuilder()
                .setWorkerFactoryOptions(WorkerFactoryOptions.newBuilder().build())
                .build());
    WorkflowClient client;
    WorkerFactory factory;
    if (testWorkflowRule.isUseExternalService()) {
      client = WorkflowClient.newInstance(WorkflowServiceStubs.newInstance());
      factory = WorkerFactory.newInstance(client);
    } else {
      client = testEnv.getWorkflowClient();
      factory = testEnv.getWorkerFactory();
    }

    // Define the workflow worker. Workflow workers listen to a defined task queue and process
    // workflows and activities.
    Worker worker = factory.newWorker(TASK_QUEUE);

    // Register the workflow implementation with the worker. Workflow implementations must be known
    // to the worker at runtime inorder to dispatch workflow tasks.
    worker.registerWorkflowImplementationTypes(TestTraceWorkflowImpl.class);

    // Start all the workers registered for a specific task queue. The started workers then start
    // polling for workflows and activities.
    factory.start();

    WorkflowOptions options = WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build();
    WorkflowStub workflow = client.newUntypedWorkflowStub("testActivity", options);
    WorkflowExecution execution = workflow.start();

    // Don't send any signals - just let the workflow sit there, so we can terminate it and see what
    // happens.
    TerminateWorkflowExecutionRequest request =
        TerminateWorkflowExecutionRequest.newBuilder()
            .setReason("testing")
            .setWorkflowExecution(execution)
            .setNamespace("default")
            .build();

    client.getWorkflowServiceStubs().blockingStub().terminateWorkflowExecution(request);

    try {
      System.out.println("Calling getResult");
      workflow.getResult(5000, TimeUnit.MILLISECONDS, String.class);
      fail();
    } catch (WorkflowFailedException e) {
      // This is expected
      System.out.println("getResult failed-fast as expected");
    } catch (TimeoutException e) {
      e.printStackTrace();
      fail();
    }
  }

  public static class TestTraceWorkflowImpl implements TestTraceWorkflow {
    private final List<String> trace = new ArrayList<>();

    @Override
    public String execute(boolean useExternalService) {
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
