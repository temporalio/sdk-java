package io.temporal.worker;

import static org.junit.Assume.assumeTrue;

import io.temporal.api.workflowservice.v1.DescribeWorkerDeploymentRequest;
import io.temporal.api.workflowservice.v1.DescribeWorkerDeploymentResponse;
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
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setDeploymentOptions(
                      WorkerDeploymentOptions.newBuilder()
                          .setVersion(new WorkerDeploymentVersion("mydeployment", "1.0"))
                          .setUseVersioning(true)
                          .build())
                  .setLocalActivityWorkerOnly(true)
                  .build())
          .setDoNotStart(true)
          .build();

  public abstract static class TestWorkerVersioningPinned
      implements TestWorkflows.QueryableWorkflow {
    WorkflowQueue<String> sigQueue = Workflow.newWorkflowQueue(1);

    abstract String implementationVersion();

    @Override
    @WorkflowMethod(name = "versioning-test-wf")
    @WorkflowVersioningBehavior(VersioningBehavior.VERSIONING_BEHAVIOR_PINNED)
    public String execute() {
      while (true) {
        String res = sigQueue.take();
        if (res.equals("done")) {
          break;
        }
      }
      return "version-" + implementationVersion();
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

  public static class TestWorkerVersioningPinnedV1 extends TestWorkerVersioningPinned {
    @Override
    String implementationVersion() {
      return "V1";
    }
  }

  @Test
  public void testBasicWorkerVersioning() {
    assumeTrue("Test Server doesn't support versioning", SDKTestWorkflowRule.useExternalService);

    // Start the 1.0 worker
    testWorkflowRule
        .getWorker()
        .registerWorkflowImplementationTypes(TestWorkerVersioningPinnedV1.class);
    testWorkflowRule.getTestEnvironment().start();

    waitUntilWorkerDeploymentVisible(new WorkerDeploymentVersion("mydeployment", "1.0"));
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

  private void waitUntilWorkerDeploymentVisible(WorkerDeploymentVersion v) {
    DescribeWorkerDeploymentRequest req =
        DescribeWorkerDeploymentRequest.newBuilder()
            .setNamespace(testWorkflowRule.getTestEnvironment().getNamespace())
            .setDeploymentName(v.getDeploymentName())
            .build();
    Eventually.assertEventually(
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
        });
  }
}
