package io.temporal.worker;

import io.temporal.common.VersioningBehavior;
import io.temporal.common.WorkerDeploymentVersion;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestWorkflows;
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
                  .build())
          .setActivityImplementations(new BuildIdVersioningTest.ActivityImpl())
          .setDoNotStart(true)
          .build();

  public static class TestWorkerVersioningPinned implements TestWorkflows.QueryableWorkflow {
    WorkflowQueue<String> sigQueue = Workflow.newWorkflowQueue(1);

    @WorkflowMethod
    @WorkflowVersioningBehavior(VersioningBehavior.VERSIONING_BEHAVIOR_PINNED)
    public String execute() {
      return "done";
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

  @Test
  public void testBasicWorkerVersioning() {}

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
}
