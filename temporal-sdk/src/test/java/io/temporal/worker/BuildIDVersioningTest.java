package io.temporal.worker;

import io.temporal.activity.Activity;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.BuildIDOperation;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.WorkflowQueue;
import io.temporal.workflow.shared.TestActivities;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import java.util.UUID;
import org.junit.Rule;
import org.junit.Test;

public class BuildIDVersioningTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkerOptions(
              WorkerOptions.newBuilder().setBuildID("1.0").setUseBuildIDForVersioning(true).build())
          .setWorkflowTypes(BuildIDVersioningTest.TestVersioningWorkflowImpl.class)
          .setActivityImplementations(new BuildIDVersioningTest.ActivityImpl())
          .setUseExternalService(true)
          .setNamespace("default")
          .setDoNotStart(true)
          .build();

  @Test
  public void testBuildIDVersioningDataSetProperly() {
    String taskQueue = testWorkflowRule.getTaskQueue();
    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();

    // Add 1.0 to the queue
    workflowClient.updateWorkerBuildIDCompatability(
        taskQueue, BuildIDOperation.newIDInNewDefaultSet("1.0"));

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
    workflowClient.updateWorkerBuildIDCompatability(
        taskQueue, BuildIDOperation.newIDInNewDefaultSet("2.0"));

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
            WorkerOptions.newBuilder().setBuildID("2.0").setUseBuildIDForVersioning(true).build());
    w2.registerWorkflowImplementationTypes(BuildIDVersioningTest.TestVersioningWorkflowImpl.class);
    w2.registerActivitiesImplementations(new BuildIDVersioningTest.ActivityImpl());
    w2F.start();

    wf2.mySignal("activity");
    testWorkflowRule.waitForTheEndOfWFT(workflowId2);

    wf1.mySignal("done");
    wf2.mySignal("done");

    workflowClient.newUntypedWorkflowStub(workflowId).getResult(String.class);
    workflowClient.newUntypedWorkflowStub(workflowId2).getResult(String.class);

    w2F.shutdown();
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
}
