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

package io.temporal.worker;

import static org.junit.Assume.assumeTrue;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.BuildIdOperation;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.internal.Signal;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.UUID;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class BuildIdVersioningTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkerOptions(
              WorkerOptions.newBuilder().setBuildId("1.0").setUseBuildIdForVersioning(true).build())
          .setWorkflowTypes(BuildIdVersioningTest.TestVersioningWorkflowImpl.class)
          .setActivityImplementations(new BuildIdVersioningTest.ActivityImpl())
          .setDoNotStart(true)
          .build();

  @Test
  public void testBuildIdVersioningDataSetProperly() {
    assumeTrue(
        "Test Server doesn't support versioning yet", SDKTestWorkflowRule.useExternalService);

    String taskQueue = testWorkflowRule.getTaskQueue();
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();

    // Add 1.0 to the queue
    workflowClient.updateWorkerBuildIdCompatability(
        taskQueue, BuildIdOperation.newIdInNewDefaultSet("1.0"));

    // Now start the worker (to avoid poll timeout while queue is unversioned)
    testWorkflowRule.getTestEnvironment().start();

    // Start a workflow
    String workflowId = "build-id-versioning-1.0-" + UUID.randomUUID();
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(taskQueue).toBuilder()
            .setWorkflowId(workflowId)
            .build();
    TestWorkflows.QueryableWorkflow wf1 =
        workflowClient.newWorkflowStub(TestWorkflows.QueryableWorkflow.class, options);
    WorkflowClient.start(wf1::execute);

    wf1.mySignal("activity");
    testWorkflowRule.waitForTheEndOfWFT(workflowId);

    // Add 2.0 to the queue
    workflowClient.updateWorkerBuildIdCompatability(
        taskQueue, BuildIdOperation.newIdInNewDefaultSet("2.0"));

    // Continue driving original workflow
    wf1.mySignal("activity");
    testWorkflowRule.waitForTheEndOfWFT(workflowId);

    // Launch a workflow that will run on 2.0
    String workflowId2 = "build-id-versioning-2.0-" + UUID.randomUUID();
    WorkflowOptions options2 =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(taskQueue).toBuilder()
            .setWorkflowId(workflowId2)
            .build();
    TestWorkflows.QueryableWorkflow wf2 =
        workflowClient.newWorkflowStub(TestWorkflows.QueryableWorkflow.class, options2);
    WorkflowClient.start(wf2::execute);

    // Java SDK (reasonably) doesn't allow multiple workers at a time on the same queue, so we need
    // a whole new factory for the 2.0 worker.
    WorkerFactory w2F =
        WorkerFactory.newInstance(workflowClient, testWorkflowRule.getWorkerFactoryOptions());
    Worker w2 =
        w2F.newWorker(
            taskQueue,
            WorkerOptions.newBuilder().setBuildId("2.0").setUseBuildIdForVersioning(true).build());
    w2.registerWorkflowImplementationTypes(BuildIdVersioningTest.TestVersioningWorkflowImpl.class);
    w2.registerActivitiesImplementations(new BuildIdVersioningTest.ActivityImpl());
    w2F.start();

    wf2.mySignal("activity");
    testWorkflowRule.waitForTheEndOfWFT(workflowId2);

    wf1.mySignal("done");
    wf2.mySignal("done");

    workflowClient.newUntypedWorkflowStub(workflowId).getResult(String.class);
    workflowClient.newUntypedWorkflowStub(workflowId2).getResult(String.class);

    w2F.shutdown();
  }

  private static final Signal ACTIVITY_RAN = new Signal();

  @Test
  public void testCurrentBuildIDSetProperly() throws InterruptedException {
    assumeTrue(
        "Test Server doesn't support versioning yet", SDKTestWorkflowRule.useExternalService);

    String taskQueue = testWorkflowRule.getTaskQueue();
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();

    // Add 1.0 to the queue
    workflowClient.updateWorkerBuildIdCompatability(
        taskQueue, BuildIdOperation.newIdInNewDefaultSet("1.0"));

    // Now start the worker (to avoid poll timeout while queue is unversioned)
    testWorkflowRule.getTestEnvironment().start();

    // Start a workflow
    String workflowId = "build-id-versioning-1.0-" + UUID.randomUUID();
    WorkflowOptions options =
        SDKTestOptions.newWorkflowOptionsWithTimeouts(taskQueue).toBuilder()
            .setWorkflowId(workflowId)
            .build();
    TestWorkflows.QueryableWorkflow wf1 =
        workflowClient.newWorkflowStub(TestWorkflows.QueryableWorkflow.class, options);
    WorkflowClient.start(wf1::execute);

    Assert.assertEquals("1.0", wf1.getState());

    // Wait for activity to run
    ACTIVITY_RAN.waitForSignal();
    Assert.assertEquals("1.0", wf1.getState());
    testWorkflowRule.getTestEnvironment().shutdown();

    // Add 1.1 to the queue
    workflowClient.updateWorkerBuildIdCompatability(
        taskQueue, BuildIdOperation.newCompatibleVersion("1.1", "1.0"));

    WorkerFactory w11F =
        WorkerFactory.newInstance(workflowClient, testWorkflowRule.getWorkerFactoryOptions());
    Worker w11 =
        w11F.newWorker(
            taskQueue,
            WorkerOptions.newBuilder().setBuildId("1.1").setUseBuildIdForVersioning(true).build());
    w11.registerWorkflowImplementationTypes(BuildIdVersioningTest.TestVersioningWorkflowImpl.class);
    w11.registerActivitiesImplementations(new BuildIdVersioningTest.ActivityImpl());
    w11F.start();

    Assert.assertEquals("1.0", wf1.getState());
    wf1.mySignal("finish");

    Assert.assertEquals("1.1", wf1.getState());

    w11F.shutdown();
  }

  public static class TestVersioningWorkflowImpl implements TestWorkflows.QueryableWorkflow {
    WorkflowQueue<String> sigQueue = Workflow.newWorkflowQueue(1);
    private final TestActivities.TestActivity1 activity =
        Workflow.newActivityStub(
            TestActivities.TestActivity1.class,
            ActivityOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofSeconds(10)).build());

    @WorkflowMethod
    public String execute() {
      while (true) {
        String sig = sigQueue.take();
        if (sig.equals("activity")) {
          activity.execute("foo");
        } else {
          return "Yay done";
        }
      }
    }

    @Override
    public void mySignal(String arg) {
      sigQueue.put(arg);
    }

    @Override
    public String getState() {
      return null;
    }
  }

  public static class ActivityImpl implements TestActivities.TestActivity1 {
    @Override
    public String execute(String input) {
      return Activity.getExecutionContext().getInfo().getActivityType() + "-" + input;
    }
  }

  public static class TestCurrentBuildIdWorkflow implements TestWorkflows.QueryableWorkflow {
    private final TestActivities.TestActivity1 activity =
        Workflow.newActivityStub(
            TestActivities.TestActivity1.class,
            ActivityOptions.newBuilder().setScheduleToCloseTimeout(Duration.ofSeconds(10)).build());
    private boolean doFinish = false;

    @WorkflowMethod
    public String execute() {
      Workflow.sleep(1);
      if (Workflow.getInfo().getCurrentBuildId().orElse("").equals("1.0")) {
        activity.execute("foo");
        ACTIVITY_RAN.signal();
      }
      Workflow.await(() -> doFinish);
      return "Yay done";
    }

    @Override
    public void mySignal(String arg) {
      doFinish = true;
    }

    @Override
    public String getState() {
      return Workflow.getInfo().getCurrentBuildId().orElse("");
    }
  }
}
