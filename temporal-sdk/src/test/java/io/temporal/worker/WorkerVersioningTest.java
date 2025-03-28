package io.temporal.worker;

import static org.junit.Assume.assumeTrue;

import com.google.protobuf.ByteString;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.workflowservice.v1.DescribeWorkerDeploymentRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkerDeploymentResponse;
import io.temporal.api.workflowservice.v1.SetWorkerDeploymentCurrentVersionRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.common.VersioningBehavior;
import io.temporal.common.WorkerDeploymentVersion;
import io.temporal.testUtils.Eventually;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestWorkflows;
import java.time.Duration;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

public class WorkerVersioningTest {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkerOptions(WorkerOptions.newBuilder().setLocalActivityWorkerOnly(true).build())
          .setDoNotStart(true)
          .setEnableWorkerDeployment("1.0")
          .build();

  public static class QueueLoop {
    WorkflowQueue<String> sigQueue = Workflow.newWorkflowQueue(1);

    public void queueLoop() {
      while (true) {
        String res = sigQueue.take();
        if (res.equals("done")) {
          break;
        }
      }
    }
  }

  public static class TestWorkerVersioningAutoUpgradeV1 extends QueueLoop
      implements TestWorkflows.QueryableWorkflow {
    @Override
    @WorkflowMethod(name = "versioning-test-wf")
    @WorkflowVersioningBehavior(VersioningBehavior.VERSIONING_BEHAVIOR_AUTO_UPGRADE)
    public String execute() {
      queueLoop();
      return "version-v1";
    }

    @Override
    public void mySignal(String arg) {
      sigQueue.put(arg);
    }

    @Override
    public String getState() {
      return "v1";
    }
  }

  public static class TestWorkerVersioningPinnedV2 extends QueueLoop
      implements TestWorkflows.QueryableWorkflow {
    @Override
    @WorkflowMethod(name = "versioning-test-wf")
    @WorkflowVersioningBehavior(VersioningBehavior.VERSIONING_BEHAVIOR_PINNED)
    public String execute() {
      queueLoop();
      return "version-v2";
    }

    @Override
    public void mySignal(String arg) {
      sigQueue.put(arg);
    }

    @Override
    public String getState() {
      return "v2";
    }
  }

  public static class TestWorkerVersioningAutoUpgradeV3 extends QueueLoop
      implements TestWorkflows.QueryableWorkflow {
    @Override
    @WorkflowMethod(name = "versioning-test-wf")
    @WorkflowVersioningBehavior(VersioningBehavior.VERSIONING_BEHAVIOR_AUTO_UPGRADE)
    public String execute() {
      queueLoop();
      return "version-v3";
    }

    @Override
    public void mySignal(String arg) {
      sigQueue.put(arg);
    }

    @Override
    public String getState() {
      return "v3";
    }
  }

  @Test
  public void testBasicWorkerVersioning() {
    assumeTrue("Test Server doesn't support versioning", SDKTestWorkflowRule.useExternalService);

    // Start the 1.0 worker
    testWorkflowRule
        .getWorker()
        .registerWorkflowImplementationTypes(TestWorkerVersioningAutoUpgradeV1.class);
    testWorkflowRule.getTestEnvironment().start();

    // Start the 2.0 worker
    Worker w2 = testWorkflowRule.newWorkerWithBuildID("2.0");
    w2.registerWorkflowImplementationTypes(TestWorkerVersioningPinnedV2.class);
    w2.start();

    // Start the 3.0 worker
    Worker w3 = testWorkflowRule.newWorkerWithBuildID("3.0");
    w3.registerWorkflowImplementationTypes(TestWorkerVersioningAutoUpgradeV3.class);
    w3.start();

    WorkerDeploymentVersion v1 =
        new WorkerDeploymentVersion(testWorkflowRule.getDeploymentName(), "1.0");
    DescribeWorkerDeploymentResponse describeResp1 = waitUntilWorkerDeploymentVisible(v1);

    setCurrentVersion(v1, describeResp1.getConflictToken());

    // Start workflow 1 which will use the 1.0 worker on auto-upgrade
    TestWorkflows.QueryableWorkflow wf1 =
        testWorkflowRule.newWorkflowStubTimeoutOptions(
            TestWorkflows.QueryableWorkflow.class, "basic-versioning-v1");
    WorkflowExecution we1 = WorkflowClient.start(wf1::execute);
    // Make sure it's running
    Assert.assertEquals("v1", wf1.getState());

    // Set current version to 2.0
    WorkerDeploymentVersion v2 =
        new WorkerDeploymentVersion(testWorkflowRule.getDeploymentName(), "2.0");
    DescribeWorkerDeploymentResponse describeResp2 = waitUntilWorkerDeploymentVisible(v2);
    setCurrentVersion(v2, describeResp2.getConflictToken());

    TestWorkflows.QueryableWorkflow wf2 =
        testWorkflowRule.newWorkflowStubTimeoutOptions(
            TestWorkflows.QueryableWorkflow.class, "basic-versioning-v2");
    WorkflowExecution we2 = WorkflowClient.start(wf2::execute);
    Assert.assertEquals("v2", wf2.getState());

    WorkerDeploymentVersion v3 =
        new WorkerDeploymentVersion(testWorkflowRule.getDeploymentName(), "3.0");
    DescribeWorkerDeploymentResponse describeResp3 = waitUntilWorkerDeploymentVisible(v3);

    // Set current version to 3.0
    setCurrentVersion(v3, describeResp3.getConflictToken());

    TestWorkflows.QueryableWorkflow wf3 =
        testWorkflowRule.newWorkflowStubTimeoutOptions(
            TestWorkflows.QueryableWorkflow.class, "basic-versioning-v3");
    WorkflowExecution we3 = WorkflowClient.start(wf3::execute);
    Assert.assertEquals("v3", wf3.getState());

    // Signal all workflows to finish
    wf1.mySignal("done");
    wf2.mySignal("done");
    wf3.mySignal("done");

    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    // Wait for all workflows to finish
    String res = workflowClient.newUntypedWorkflowStub(we1.getWorkflowId()).getResult(String.class);
    // Should have been auto-upgraded to 3
    Assert.assertEquals("version-v3", res);
    String res2 =
        workflowClient.newUntypedWorkflowStub(we2.getWorkflowId()).getResult(String.class);
    // Should've stayed pinned to 2
    Assert.assertEquals("version-v2", res2);
    String res3 =
        workflowClient.newUntypedWorkflowStub(we3.getWorkflowId()).getResult(String.class);
    // Started and finished on 3
    Assert.assertEquals("version-v3", res3);
  }

  @WorkflowInterface
  public interface DontAllowBehaviorAnnotationOnInterface {
    @WorkflowMethod
    @WorkflowVersioningBehavior(VersioningBehavior.VERSIONING_BEHAVIOR_PINNED)
    void execute();
  }

  public static class DontAllowBehaviorAnnotationOnInterfaceImpl
      implements DontAllowBehaviorAnnotationOnInterface {
    @Override
    public void execute() {}
  }

  @Test
  public void testAnnotationNotAllowedOnInterface() {
    IllegalArgumentException e =
        Assert.assertThrows(
            IllegalArgumentException.class,
            () ->
                testWorkflowRule
                    .getWorker()
                    .registerWorkflowImplementationTypes(
                        DontAllowBehaviorAnnotationOnInterfaceImpl.class));
    Assert.assertEquals(
        "@WorkflowVersioningBehavior annotation is not allowed on interface methods, only on "
            + "implementation methods: public abstract void io.temporal.worker.WorkerVersioningTest"
            + "$DontAllowBehaviorAnnotationOnInterface.execute()",
        e.getMessage());
  }

  private DescribeWorkerDeploymentResponse waitUntilWorkerDeploymentVisible(
      WorkerDeploymentVersion v) {
    DescribeWorkerDeploymentRequest req =
        DescribeWorkerDeploymentRequest.newBuilder()
            .setNamespace(testWorkflowRule.getTestEnvironment().getNamespace())
            .setDeploymentName(v.getDeploymentName())
            .build();
    return Eventually.assertEventually(
        Duration.ofSeconds(15),
        () -> {
          DescribeWorkerDeploymentResponse resp =
              testWorkflowRule
                  .getWorkflowClient()
                  .getWorkflowServiceStubs()
                  .blockingStub()
                  .describeWorkerDeployment(req);
          Assert.assertTrue(
              resp.getWorkerDeploymentInfo().getVersionSummariesList().stream()
                  .anyMatch(vs -> vs.getVersion().equals(v.toCanonicalString())));
          return resp;
        });
  }

  private void setCurrentVersion(WorkerDeploymentVersion v, ByteString conflictToken) {
    testWorkflowRule
        .getWorkflowClient()
        .getWorkflowServiceStubs()
        .blockingStub()
        .setWorkerDeploymentCurrentVersion(
            SetWorkerDeploymentCurrentVersionRequest.newBuilder()
                .setNamespace(testWorkflowRule.getTestEnvironment().getNamespace())
                .setDeploymentName(testWorkflowRule.getDeploymentName())
                .setVersion(v.toCanonicalString())
                .setConflictToken(conflictToken)
                .build());
  }
}
