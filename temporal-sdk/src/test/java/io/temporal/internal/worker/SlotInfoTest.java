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
import io.temporal.testUtils.RecordingSlotSupplier;
import io.temporal.testing.CloudTestExclusion.RequiresCloudProvisioning;
import io.temporal.testing.CloudTestExclusionNote;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerDeploymentOptions;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.tuning.ActivitySlotInfo;
import io.temporal.worker.tuning.CompositeTuner;
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
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@SuppressWarnings("deprecation")
@CloudTestExclusionNote("Cloud CI does not provision the Nexus endpoint required by this test.")
@Category(RequiresCloudProvisioning.class)
public class SlotInfoTest {
  private static final String WORKFLOW_TYPE = "slot-info-workflow";
  private static final String ACTIVITY_TYPE = "slot-info-activity";
  private static final String WORKFLOW_ID = "slot-info-workflow-id";
  private static final String WORKER_IDENTITY = "slot-info-worker-identity";
  private static final String WORKER_BUILD_ID = "slot-info-worker-build-id";

  private final RecordingSlotSupplier<WorkflowSlotInfo> workflowSlotSupplier =
      new RecordingSlotSupplier<>(100);
  private final RecordingSlotSupplier<ActivitySlotInfo> activitySlotSupplier =
      new RecordingSlotSupplier<>(100);
  private final RecordingSlotSupplier<LocalActivitySlotInfo> localActivitySlotSupplier =
      new RecordingSlotSupplier<>(100);
  private final RecordingSlotSupplier<NexusSlotInfo> nexusSlotSupplier =
      new RecordingSlotSupplier<>(100);

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

    List<WorkflowSlotInfo> workflowSlotInfos = getSlotInfos(workflowSlotSupplier);
    assertFalse(workflowSlotInfos.isEmpty());
    for (WorkflowSlotInfo slotInfo : workflowSlotInfos) {
      assertEquals(WORKFLOW_TYPE, slotInfo.getWorkflowType());
      assertEquals(testWorkflowRule.getTaskQueue(), slotInfo.getTaskQueue());
      assertEquals(WORKFLOW_ID, slotInfo.getWorkflowId());
      assertEquals(runId, slotInfo.getRunId());
      assertEquals(WORKER_IDENTITY, slotInfo.getWorkerIdentity());
      assertEquals(WORKER_BUILD_ID, slotInfo.getWorkerBuildId());
    }

    ActivitySlotInfo activitySlotInfo = getOnlySlotInfo(activitySlotSupplier);
    assertActivityInfo(activitySlotInfo, runId, false);

    LocalActivitySlotInfo localActivitySlotInfo = getOnlySlotInfo(localActivitySlotSupplier);
    assertActivityInfo(localActivitySlotInfo, runId, true);

    NexusSlotInfo nexusSlotInfo = getOnlySlotInfo(nexusSlotSupplier);
    assertEquals(
        TestNexusServices.TestNexusService1.class.getSimpleName(), nexusSlotInfo.getService());
    assertEquals("operation", nexusSlotInfo.getOperation());
    assertEquals(testWorkflowRule.getTaskQueue(), nexusSlotInfo.getTaskQueue());
    assertEquals(WORKER_IDENTITY, nexusSlotInfo.getWorkerIdentity());
    assertEquals(WORKER_BUILD_ID, nexusSlotInfo.getWorkerBuildId());

    workflowSlotInfos.forEach(
        slotInfo -> assertReleasedWithSameSlotInfo(workflowSlotSupplier, slotInfo));
    assertReleasedWithSameSlotInfo(activitySlotSupplier, activitySlotInfo);
    assertReleasedWithSameSlotInfo(localActivitySlotSupplier, localActivitySlotInfo);
    assertReleasedWithSameSlotInfo(nexusSlotSupplier, nexusSlotInfo);
  }

  private static <SI extends SlotInfo> List<SI> getSlotInfos(
      RecordingSlotSupplier<SI> slotSupplier) {
    List<SI> result = new ArrayList<>();
    for (SlotMarkUsedContext<SI> context : slotSupplier.getMarkUsedContexts()) {
      result.add(context.getSlotInfo());
    }
    return result;
  }

  private static <SI extends SlotInfo> SI getOnlySlotInfo(RecordingSlotSupplier<SI> slotSupplier) {
    List<SI> slotInfos = getSlotInfos(slotSupplier);
    assertEquals(1, slotInfos.size());
    return slotInfos.get(0);
  }

  private static <SI extends SlotInfo> void assertReleasedWithSameSlotInfo(
      RecordingSlotSupplier<SI> slotSupplier, SI expectedSlotInfo) {
    SlotPermit permit = null;
    for (SlotMarkUsedContext<SI> context : slotSupplier.getMarkUsedContexts()) {
      if (context.getSlotInfo() == expectedSlotInfo) {
        permit = context.getSlotPermit();
        break;
      }
    }
    assertNotNull(permit);
    SlotPermit markedPermit = permit;
    assertEventually(
        Duration.ofSeconds(1),
        () -> {
          SI releasedSlotInfo = null;
          for (SlotReleaseContext<SI> context : slotSupplier.getReleaseContexts()) {
            if (context.getSlotPermit() == markedPermit) {
              releasedSlotInfo = context.getSlotInfo();
              break;
            }
          }
          assertSame(expectedSlotInfo, releasedSlotInfo);
        });
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
    assertEquals(
        testWorkflowRule.getWorkflowClient().getOptions().getNamespace(),
        activityInfo.getNamespace());
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
}
