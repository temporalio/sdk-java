package io.temporal.internal.worker;

import static io.temporal.testUtils.Eventually.assertEventually;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.RetryOptions;
import io.temporal.common.WorkerDeploymentVersion;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerDeploymentOptions;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.tuning.ActivitySlotInfo;
import io.temporal.worker.tuning.CompositeTuner;
import io.temporal.worker.tuning.FixedSizeSlotSupplier;
import io.temporal.worker.tuning.LocalActivitySlotInfo;
import io.temporal.worker.tuning.NexusSlotInfo;
import io.temporal.worker.tuning.SlotInfo;
import io.temporal.worker.tuning.SlotMarkUsedContext;
import io.temporal.worker.tuning.SlotPermit;
import io.temporal.worker.tuning.SlotReleaseContext;
import io.temporal.worker.tuning.WorkflowSlotInfo;
import io.temporal.workflow.NexusOperationOptions;
import io.temporal.workflow.NexusServiceOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.shared.TestNexusServices;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.Rule;
import org.junit.Test;

@SuppressWarnings("deprecation")
public class SlotInfoTest {
  private static final String WORKFLOW_TYPE = "slot-info-workflow";
  private static final String ACTIVITY_TYPE = "slot-info-activity";
  private static final String WORKFLOW_ID = "slot-info-workflow-id";
  private static final String WORKER_IDENTITY = "slot-info-worker-identity";
  private static final String WORKER_BUILD_ID = "slot-info-worker-build-id";

  private final RecordingSlotSupplier<WorkflowSlotInfo> workflowSlotSupplier =
      new RecordingSlotSupplier<>();
  private final RecordingSlotSupplier<ActivitySlotInfo> activitySlotSupplier =
      new RecordingSlotSupplier<>();
  private final RecordingSlotSupplier<LocalActivitySlotInfo> localActivitySlotSupplier =
      new RecordingSlotSupplier<>();
  private final RecordingSlotSupplier<NexusSlotInfo> nexusSlotSupplier =
      new RecordingSlotSupplier<>();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setIdentity(WORKER_IDENTITY)
                  .setDeploymentOptions(
                      WorkerDeploymentOptions.newBuilder()
                          .setVersion(
                              new WorkerDeploymentVersion("slot-info-deployment", WORKER_BUILD_ID))
                          .setUseVersioning(false)
                          .build())
                  .setWorkerTuner(
                      new CompositeTuner(
                          workflowSlotSupplier,
                          activitySlotSupplier,
                          localActivitySlotSupplier,
                          nexusSlotSupplier))
                  .build())
          .setWorkflowTypes(SlotInfoWorkflowImpl.class)
          .setActivityImplementations(new SlotInfoActivityImpl())
          .setNexusServiceImplementation(new SlotInfoNexusService())
          .build();

  @Test
  public void customSlotSuppliersReceiveExpectedSlotInfo() {
    SlotInfoWorkflow workflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                SlotInfoWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setWorkflowId(WORKFLOW_ID)
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .build());

    assertEquals("done", workflow.execute());
    String runId = WorkflowStub.fromTyped(workflow).getExecution().getRunId();

    List<WorkflowSlotInfo> workflowSlotInfos =
        workflowSlotSupplier.getSlotInfos(WorkflowSlotInfo.class);
    assertFalse(workflowSlotInfos.isEmpty());
    for (WorkflowSlotInfo slotInfo : workflowSlotInfos) {
      assertEquals(WORKFLOW_TYPE, slotInfo.getWorkflowType());
      assertEquals(testWorkflowRule.getTaskQueue(), slotInfo.getTaskQueue());
      assertEquals(WORKFLOW_ID, slotInfo.getWorkflowId());
      assertEquals(runId, slotInfo.getRunId());
      assertEquals(WORKER_IDENTITY, slotInfo.getWorkerIdentity());
      assertEquals(WORKER_BUILD_ID, slotInfo.getWorkerBuildId());
    }

    ActivitySlotInfo activitySlotInfo =
        activitySlotSupplier.getOnlySlotInfo(ActivitySlotInfo.class);
    assertActivityInfo(activitySlotInfo, runId, false);

    LocalActivitySlotInfo localActivitySlotInfo =
        localActivitySlotSupplier.getOnlySlotInfo(LocalActivitySlotInfo.class);
    assertActivityInfo(localActivitySlotInfo, runId, true);

    NexusSlotInfo nexusSlotInfo = nexusSlotSupplier.getOnlySlotInfo(NexusSlotInfo.class);
    assertEquals(
        TestNexusServices.TestNexusService1.class.getSimpleName(), nexusSlotInfo.getService());
    assertEquals("operation", nexusSlotInfo.getOperation());
    assertEquals(testWorkflowRule.getTaskQueue(), nexusSlotInfo.getTaskQueue());
    assertEquals(WORKER_IDENTITY, nexusSlotInfo.getWorkerIdentity());
    assertEquals(WORKER_BUILD_ID, nexusSlotInfo.getWorkerBuildId());

    workflowSlotInfos.forEach(workflowSlotSupplier::assertReleasedWithSameSlotInfo);
    activitySlotSupplier.assertReleasedWithSameSlotInfo(activitySlotInfo);
    localActivitySlotSupplier.assertReleasedWithSameSlotInfo(localActivitySlotInfo);
    nexusSlotSupplier.assertReleasedWithSameSlotInfo(nexusSlotInfo);
  }

  private void assertActivityInfo(ActivitySlotInfo slotInfo, String runId, boolean expectedLocal) {
    assertActivityInfo(
        slotInfo.getActivityInfo(),
        slotInfo.getWorkerIdentity(),
        slotInfo.getWorkerBuildId(),
        runId,
        expectedLocal);
  }

  private void assertActivityInfo(
      LocalActivitySlotInfo slotInfo, String runId, boolean expectedLocal) {
    assertActivityInfo(
        slotInfo.getActivityInfo(),
        slotInfo.getWorkerIdentity(),
        slotInfo.getWorkerBuildId(),
        runId,
        expectedLocal);
  }

  private void assertActivityInfo(
      io.temporal.activity.ActivityInfo activityInfo,
      String workerIdentity,
      String workerBuildId,
      String runId,
      boolean expectedLocal) {
    assertEquals(ACTIVITY_TYPE, activityInfo.getActivityType());
    assertFalse(activityInfo.getActivityId().isEmpty());
    assertEquals(WORKFLOW_ID, activityInfo.getWorkflowId());
    assertEquals(runId, activityInfo.getWorkflowRunId());
    assertEquals(WORKFLOW_TYPE, activityInfo.getWorkflowType());
    assertEquals(testWorkflowRule.getTaskQueue(), activityInfo.getActivityTaskQueue());
    assertEquals(SDKTestWorkflowRule.NAMESPACE, activityInfo.getNamespace());
    assertEquals(1, activityInfo.getAttempt());
    assertEquals(expectedLocal, activityInfo.isLocal());
    assertEquals(WORKER_IDENTITY, workerIdentity);
    assertEquals(WORKER_BUILD_ID, workerBuildId);
  }

  @WorkflowInterface
  public interface SlotInfoWorkflow {
    @WorkflowMethod(name = WORKFLOW_TYPE)
    String execute();
  }

  public static class SlotInfoWorkflowImpl implements SlotInfoWorkflow {
    private final SlotInfoActivity activity =
        Workflow.newActivityStub(
            SlotInfoActivity.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build());
    private final SlotInfoActivity localActivity =
        Workflow.newLocalActivityStub(
            SlotInfoActivity.class,
            LocalActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(10))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                .build());
    private final TestNexusServices.TestNexusService1 nexusService =
        Workflow.newNexusServiceStub(
            TestNexusServices.TestNexusService1.class,
            NexusServiceOptions.newBuilder()
                .setOperationOptions(
                    NexusOperationOptions.newBuilder()
                        .setScheduleToCloseTimeout(Duration.ofSeconds(10))
                        .build())
                .build());

    @Override
    public String execute() {
      localActivity.execute();
      activity.execute();
      nexusService.operation("input");
      return "done";
    }
  }

  @ActivityInterface
  public interface SlotInfoActivity {
    @ActivityMethod(name = ACTIVITY_TYPE)
    String execute();
  }

  public static class SlotInfoActivityImpl implements SlotInfoActivity {
    @Override
    public String execute() {
      return "done";
    }
  }

  @ServiceImpl(service = TestNexusServices.TestNexusService1.class)
  public static class SlotInfoNexusService {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return OperationHandler.sync((ctx, details, input) -> "done");
    }
  }

  private static final class RecordingSlotSupplier<SI extends SlotInfo>
      extends FixedSizeSlotSupplier<SI> {
    private final ConcurrentLinkedQueue<SI> slotInfos = new ConcurrentLinkedQueue<>();
    private final Map<SlotPermit, SI> markedSlotInfos = new ConcurrentHashMap<>();
    private final Map<SlotPermit, SI> releasedSlotInfos = new ConcurrentHashMap<>();

    private RecordingSlotSupplier() {
      super(100);
    }

    @Override
    public void markSlotUsed(SlotMarkUsedContext<SI> ctx) {
      slotInfos.add(ctx.getSlotInfo());
      markedSlotInfos.put(ctx.getSlotPermit(), ctx.getSlotInfo());
      super.markSlotUsed(ctx);
    }

    @Override
    public void releaseSlot(SlotReleaseContext<SI> ctx) {
      if (ctx.getSlotInfo() != null) {
        releasedSlotInfos.put(ctx.getSlotPermit(), ctx.getSlotInfo());
      }
      super.releaseSlot(ctx);
    }

    private <T extends SI> List<T> getSlotInfos(Class<T> slotInfoClass) {
      List<T> result = new ArrayList<>();
      for (SI slotInfo : slotInfos) {
        if (slotInfoClass.isInstance(slotInfo)) {
          result.add(slotInfoClass.cast(slotInfo));
        }
      }
      return result;
    }

    private <T extends SI> T getOnlySlotInfo(Class<T> slotInfoClass) {
      List<T> result = getSlotInfos(slotInfoClass);
      assertEquals(1, result.size());
      return result.get(0);
    }

    private void assertReleasedWithSameSlotInfo(SI expectedSlotInfo) {
      SlotPermit permit = null;
      for (Map.Entry<SlotPermit, SI> entry : markedSlotInfos.entrySet()) {
        if (entry.getValue() == expectedSlotInfo) {
          permit = entry.getKey();
          break;
        }
      }
      assertNotNull(permit);
      SlotPermit markedPermit = permit;
      assertEventually(
          Duration.ofSeconds(1),
          () -> assertSame(expectedSlotInfo, releasedSlotInfos.get(markedPermit)));
    }
  }
}
