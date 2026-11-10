package io.temporal.worker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.activity.ActivityInterface;
import io.temporal.api.worker.v1.WorkerHeartbeat;
import io.temporal.testing.CloudTestExclusion.RequiresLocalServer;
import io.temporal.testing.CloudTestExclusionNote;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.tuning.PollerBehavior;
import io.temporal.worker.tuning.PollerBehaviorAutoscaling;
import io.temporal.worker.tuning.PollerBehaviorSimpleMaximum;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.shared.TestNexusServices;
import org.junit.Test;
import org.junit.experimental.categories.Category;

/** Each poller pool reports its own target on the worker heartbeat. */
@CloudTestExclusionNote("This test directly creates and controls a local test service.")
@Category(RequiresLocalServer.class)
public class WorkerHeartbeatPollerTargetTest {

  @Test
  public void eachPoolReportsItsOwnTarget() {
    // Pinned min == max == initial so the autoscaler cannot move the targets mid-test.
    WorkerHeartbeat heartbeat =
        heartbeat(
            new PollerBehaviorAutoscaling(3, 3, 3),
            new PollerBehaviorAutoscaling(7, 7, 7),
            new PollerBehaviorAutoscaling(5, 5, 5));

    assertEquals(3, heartbeat.getWorkflowPollerInfo().getTargetPollers());
    assertEquals(3, heartbeat.getWorkflowStickyPollerInfo().getTargetPollers());
    assertEquals(7, heartbeat.getActivityPollerInfo().getTargetPollers());
    assertEquals(5, heartbeat.getNexusPollerInfo().getTargetPollers());
  }

  @Test
  public void fixedSizePoolsReportTheirConfiguredSize() {
    WorkerHeartbeat heartbeat =
        heartbeat(
            new PollerBehaviorSimpleMaximum(3),
            new PollerBehaviorSimpleMaximum(7),
            new PollerBehaviorSimpleMaximum(5));

    assertEquals(3, heartbeat.getWorkflowPollerInfo().getTargetPollers());
    // The sticky polls come out of the same fixed pool as the normal ones.
    assertEquals(3, heartbeat.getWorkflowStickyPollerInfo().getTargetPollers());
    assertEquals(7, heartbeat.getActivityPollerInfo().getTargetPollers());
    assertEquals(5, heartbeat.getNexusPollerInfo().getTargetPollers());

    assertFalse(heartbeat.getWorkflowPollerInfo().getIsAutoscaling());
    assertFalse(heartbeat.getActivityPollerInfo().getIsAutoscaling());
    assertFalse(heartbeat.getNexusPollerInfo().getIsAutoscaling());
  }

  @Test
  public void poolsWithNothingRegisteredReportNoTarget() {
    // Only workflows are registered, so the activity and Nexus pollers never start. They must not
    // report a target they will never poll with.
    WorkerHeartbeat heartbeat =
        heartbeat(
            new PollerBehaviorSimpleMaximum(3),
            new PollerBehaviorSimpleMaximum(7),
            new PollerBehaviorSimpleMaximum(5),
            false);

    assertEquals(3, heartbeat.getWorkflowPollerInfo().getTargetPollers());
    assertEquals(0, heartbeat.getActivityPollerInfo().getTargetPollers());
    assertEquals(0, heartbeat.getNexusPollerInfo().getTargetPollers());
  }

  private static WorkerHeartbeat heartbeat(
      PollerBehavior workflowBehavior,
      PollerBehavior activityBehavior,
      PollerBehavior nexusBehavior) {
    return heartbeat(workflowBehavior, activityBehavior, nexusBehavior, true);
  }

  private static WorkerHeartbeat heartbeat(
      PollerBehavior workflowBehavior,
      PollerBehavior activityBehavior,
      PollerBehavior nexusBehavior,
      boolean registerAllTypes) {
    try (TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance()) {
      Worker worker =
          env.newWorker(
              "poller-target-queue",
              WorkerOptions.newBuilder()
                  .setWorkflowTaskPollersBehavior(workflowBehavior)
                  .setActivityTaskPollersBehavior(activityBehavior)
                  .setNexusTaskPollersBehavior(nexusBehavior)
                  .build());
      // Every pool needs a registered implementation, otherwise its worker never starts polling.
      worker.registerWorkflowImplementationTypes(TestWorkflowImpl.class);
      if (registerAllTypes) {
        worker.registerActivitiesImplementations(new TestActivityImpl());
        worker.registerNexusServiceImplementation(new TestNexusServiceImpl());
      }
      env.start();

      return worker.buildHeartbeatCallback("test-worker-group", null).get();
    }
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class TestWorkflowImpl implements TestWorkflow {
    @Override
    public void execute() {}
  }

  @ActivityInterface
  public interface TestActivity {
    void execute();
  }

  public static class TestActivityImpl implements TestActivity {
    @Override
    public void execute() {}
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public static class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return OperationHandler.sync((context, details, input) -> input);
    }
  }
}
