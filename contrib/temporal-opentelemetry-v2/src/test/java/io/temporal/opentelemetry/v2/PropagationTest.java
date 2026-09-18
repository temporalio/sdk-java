package io.temporal.opentelemetry.v2;

import static io.temporal.opentelemetry.v2.TestWorkflows.NEXUS_CANCEL_OPERATION_NAME;
import static io.temporal.opentelemetry.v2.TestWorkflows.NEXUS_OPERATION_NAME;
import static io.temporal.opentelemetry.v2.TestWorkflows.NEXUS_SERVICE_NAME;
import static io.temporal.opentelemetry.v2.TestWorkflows.TASK_TOKENS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.client.StartActivityOptions;
import io.temporal.client.StartNexusOperationOptions;
import io.temporal.client.UpdateOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowUpdateStage;
import io.temporal.client.schedules.Schedule;
import io.temporal.client.schedules.ScheduleActionStartWorkflow;
import io.temporal.client.schedules.ScheduleClient;
import io.temporal.client.schedules.ScheduleClientOptions;
import io.temporal.client.schedules.ScheduleHandle;
import io.temporal.client.schedules.ScheduleOptions;
import io.temporal.client.schedules.ScheduleSpec;
import io.temporal.client.schedules.ScheduleUpdate;
import io.temporal.opentelemetry.v2.TestWorkflows.AsyncCompletionWorkflow;
import io.temporal.opentelemetry.v2.TestWorkflows.AsyncCompletionWorkflowImpl;
import io.temporal.opentelemetry.v2.TestWorkflows.ChildWorkflowWithSignalImpl;
import io.temporal.opentelemetry.v2.TestWorkflows.ComprehensiveNexusServiceImpl;
import io.temporal.opentelemetry.v2.TestWorkflows.ComprehensiveWorkflow;
import io.temporal.opentelemetry.v2.TestWorkflows.ComprehensiveWorkflowImpl;
import io.temporal.opentelemetry.v2.TestWorkflows.ContinueAsNewToDifferentWorkflow;
import io.temporal.opentelemetry.v2.TestWorkflows.ContinueAsNewToDifferentWorkflowImpl;
import io.temporal.opentelemetry.v2.TestWorkflows.DifferentWorkflowImpl;
import io.temporal.opentelemetry.v2.TestWorkflows.ExternalWorkflowWithSignal;
import io.temporal.opentelemetry.v2.TestWorkflows.ExternalWorkflowWithSignalImpl;
import io.temporal.opentelemetry.v2.TestWorkflows.NexusCancelHandlerWorkflowImpl;
import io.temporal.opentelemetry.v2.TestWorkflows.NexusHandlerWorkflowImpl;
import io.temporal.opentelemetry.v2.TestWorkflows.SchedulePropagationReceiver;
import io.temporal.opentelemetry.v2.TestWorkflows.SchedulePropagationReceiverImpl;
import io.temporal.opentelemetry.v2.TestWorkflows.SchedulePropagationWorkflowImpl;
import io.temporal.opentelemetry.v2.TestWorkflows.SignalWithStartTargetImpl;
import io.temporal.opentelemetry.v2.TestWorkflows.StandaloneWorkflow;
import io.temporal.opentelemetry.v2.TestWorkflows.StandaloneWorkflowImpl;
import io.temporal.opentelemetry.v2.TestWorkflows.TestActivities;
import io.temporal.opentelemetry.v2.TestWorkflows.TestActivitiesImpl;
import io.temporal.opentelemetry.v2.TestWorkflows.UnservedWorkflowImpl;
import io.temporal.opentelemetry.v2.TestWorkflows.UpdateTargetWorkflowImpl;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Covers one scenario touching every traced operation, asserted as a span tree with Temporal spans
 * on and off.
 *
 * <p>Needs a real server: client-started Nexus operations, standalone activities, and schedules are
 * not implemented by the in-memory test server. CI runs it in the dev-server job.
 */
@RunWith(Parameterized.class)
public class PropagationTest extends OtelTestBase {
  private static final String COMPREHENSIVE_UPDATE_ID = "comprehensive-update";
  private static final String UPDATE_WITH_START_UPDATE_ID = "comprehensive-update-with-start";
  private static final String UNSERVED_TASK_QUEUE = "opentelemetry-v2-unserved";
  private static final String TERMINATE_REASON = "otel-terminate-reason";

  @Parameters(name = "addTemporalSpans={0}")
  public static List<Boolean> addTemporalSpans() {
    return Arrays.asList(true, false);
  }

  private final boolean addTemporalSpans;

  private final String runId = UUID.randomUUID().toString();
  private final String scheduleId = "otel-schedule-" + runId;
  private final String reusedScheduleSeedId = "otel-reused-schedule-seed-" + runId;
  private final String reusedScheduleId = "otel-reused-schedule-" + runId;
  private final String reusedScheduleWorkflowId = "otel-reused-schedule-workflow-" + runId;
  private final String reusedScheduleReceiverId = "otel-reused-schedule-receiver-" + runId;
  private final String externalWorkflowId = "externalWorkflowWithSignal-" + runId;
  private final String comprehensiveWorkflowId = "comprehensive-outbound-" + runId;
  private final String updateWithStartWorkflowId = "otel-update-with-start-" + runId;

  @Rule public SDKTestWorkflowRule testWorkflowRule;

  public PropagationTest(boolean addTemporalSpans) {
    this.addTemporalSpans = addTemporalSpans;
    this.testWorkflowRule =
        newRuleBuilder(addTemporalSpans)
            .setWorkflowTypes(
                ComprehensiveWorkflowImpl.class,
                ContinueAsNewToDifferentWorkflowImpl.class,
                DifferentWorkflowImpl.class,
                ChildWorkflowWithSignalImpl.class,
                ExternalWorkflowWithSignalImpl.class,
                NexusHandlerWorkflowImpl.class,
                NexusCancelHandlerWorkflowImpl.class,
                StandaloneWorkflowImpl.class,
                SchedulePropagationWorkflowImpl.class,
                SchedulePropagationReceiverImpl.class,
                SignalWithStartTargetImpl.class,
                UpdateTargetWorkflowImpl.class,
                AsyncCompletionWorkflowImpl.class,
                UnservedWorkflowImpl.class)
            .setActivityImplementations(new TestActivitiesImpl())
            .setNexusServiceImplementation(new ComprehensiveNexusServiceImpl())
            .build();
  }

  @Before
  public void requireRealServer() {
    assumeTrue(
        "Test Server doesn't support client Nexus operations, standalone activities, or schedules",
        SDKTestWorkflowRule.useExternalService);
  }

  @Test
  public void comprehensive() {
    List<SpanData> spans = runScenario();
    assertSpanTree(addTemporalSpans ? fullTree() : noTemporalSpansTree(), spans);
    requireUniqueSpanIds(spans);
  }

  /** Drives every traced operation under one client span and returns the ended spans. */
  private List<SpanData> runScenario() {
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    String taskQueue = testWorkflowRule.getTaskQueue();
    String nexusEndpoint = testWorkflowRule.getNexusEndpoint().getSpec().getName();

    // All client calls share this parent span.
    Span clientSpan =
        GlobalOpenTelemetry.getTracer("client").spanBuilder("client-span").startSpan();
    try (Scope ignored = clientSpan.makeCurrent()) {
      // Start the external signal target first.
      ExternalWorkflowWithSignal external =
          client.newWorkflowStub(
              ExternalWorkflowWithSignal.class, options(taskQueue, externalWorkflowId));
      WorkflowClient.start(external::run);

      client
          .newWorkflowStub(
              ContinueAsNewToDifferentWorkflow.class,
              options(taskQueue, "otel-continue-as-new-" + UUID.randomUUID()))
          .run();

      ComprehensiveWorkflow comprehensive =
          client.newWorkflowStub(
              ComprehensiveWorkflow.class, options(taskQueue, comprehensiveWorkflowId));
      WorkflowClient.start(comprehensive::run, false, nexusEndpoint, externalWorkflowId);
      WorkflowStub comprehensiveStub = WorkflowStub.fromTyped(comprehensive);

      comprehensiveStub
          .startUpdate(
              UpdateOptions.newBuilder(Void.class)
                  .setUpdateName("testUpdate")
                  .setUpdateId(COMPREHENSIVE_UPDATE_ID)
                  .setWaitForStage(WorkflowUpdateStage.COMPLETED)
                  .build())
          .getResult();
      assertEquals("ok", comprehensive.getStatus());
      comprehensive.proceed();
      comprehensiveStub.getResult(Void.class);

      testWorkflowRule
          .getActivityClient()
          .execute(
              TestActivities.class,
              TestActivities::standaloneActivity,
              StartActivityOptions.newBuilder()
                  .setId("otel-standalone-activity-" + UUID.randomUUID())
                  .setTaskQueue(taskQueue)
                  .setStartToCloseTimeout(Duration.ofSeconds(10))
                  .build());

      client
          .newWorkflowStub(
              StandaloneWorkflow.class,
              options(taskQueue, "otel-standalone-workflow-" + UUID.randomUUID()))
          .run(null);

      AsyncCompletionWorkflow asyncCompletion =
          client.newWorkflowStub(
              AsyncCompletionWorkflow.class,
              options(taskQueue, "otel-async-completion-" + UUID.randomUUID()));
      WorkflowClient.start(asyncCompletion::run);
      byte[] taskToken = takeTaskToken();
      client.newActivityCompletionClient().complete(taskToken, null);
      WorkflowStub.fromTyped(asyncCompletion).getResult(Void.class);

      WorkflowStub cancelTarget =
          client.newUntypedWorkflowStub(
              "UnservedWorkflow",
              options(UNSERVED_TASK_QUEUE, "otel-cancel-target-" + UUID.randomUUID()));
      cancelTarget.start();
      cancelTarget.cancel();

      WorkflowStub terminateTarget =
          client.newUntypedWorkflowStub(
              "UnservedWorkflow",
              options(UNSERVED_TASK_QUEUE, "otel-terminate-target-" + UUID.randomUUID()));
      terminateTarget.start();
      terminateTarget.terminate(TERMINATE_REASON);

      comprehensiveStub.describe();

      ScheduleActionStartWorkflow scheduleAction =
          ScheduleActionStartWorkflow.newBuilder()
              .setWorkflowType("StandaloneWorkflow")
              .setOptions(options(taskQueue, "otel-schedule-workflow-" + UUID.randomUUID()))
              .build();
      ScheduleClient scheduleClient =
          ScheduleClient.newInstance(
              testWorkflowRule.getWorkflowServiceStubs(),
              ScheduleClientOptions.newBuilder()
                  .setNamespace(client.getOptions().getNamespace())
                  .build());
      ScheduleHandle schedule =
          scheduleClient.createSchedule(
              scheduleId,
              Schedule.newBuilder()
                  .setAction(scheduleAction)
                  .setSpec(ScheduleSpec.newBuilder().build())
                  .build(),
              ScheduleOptions.newBuilder().build());
      try {
        String updatedScheduleWorkflowId = "otel-schedule-updated-workflow-" + UUID.randomUUID();
        try (Scope rootScope = Context.root().makeCurrent()) {
          schedule.update(
              input ->
                  new ScheduleUpdate(
                      Schedule.newBuilder(input.getDescription().getSchedule())
                          .setAction(
                              ScheduleActionStartWorkflow.newBuilder(scheduleAction)
                                  .setOptions(options(taskQueue, updatedScheduleWorkflowId))
                                  .setArguments(externalWorkflowId)
                                  .build())
                          .build()));
        }
        schedule.trigger();
        String scheduledWorkflowId = WorkflowStub.fromTyped(external).getResult(String.class);
        client.newUntypedWorkflowStub(scheduledWorkflowId).getResult(Void.class);

        // Reusing this action verifies a root-context create cannot retain the first create's
        // trace.
        SchedulePropagationReceiver reusedScheduleReceiver =
            client.newWorkflowStub(
                SchedulePropagationReceiver.class, options(taskQueue, reusedScheduleReceiverId));
        WorkflowClient.start(reusedScheduleReceiver::run);
        ScheduleActionStartWorkflow reusedScheduleAction =
            ScheduleActionStartWorkflow.newBuilder()
                .setWorkflowType("SchedulePropagationWorkflow")
                .setOptions(options(taskQueue, reusedScheduleWorkflowId))
                .setArguments(reusedScheduleReceiverId)
                .build();
        ScheduleHandle reusedScheduleSeed =
            scheduleClient.createSchedule(
                reusedScheduleSeedId,
                Schedule.newBuilder()
                    .setAction(reusedScheduleAction)
                    .setSpec(ScheduleSpec.newBuilder().build())
                    .build(),
                ScheduleOptions.newBuilder().build());
        ScheduleHandle reusedSchedule = null;
        try {
          try (Scope rootScope = Context.root().makeCurrent()) {
            reusedSchedule =
                scheduleClient.createSchedule(
                    reusedScheduleId,
                    Schedule.newBuilder()
                        .setAction(reusedScheduleAction)
                        .setSpec(ScheduleSpec.newBuilder().build())
                        .build(),
                    ScheduleOptions.newBuilder().build());
          }
          reusedSchedule.trigger();
          String reusedScheduledWorkflowId =
              WorkflowStub.fromTyped(reusedScheduleReceiver).getResult(String.class);
          client.newUntypedWorkflowStub(reusedScheduledWorkflowId).getResult(Void.class);
        } finally {
          reusedScheduleSeed.delete();
          if (reusedSchedule != null) {
            reusedSchedule.delete();
          }
        }

        WorkflowStub signalWithStart =
            client.newUntypedWorkflowStub(
                "SignalWithStartTarget",
                options(taskQueue, "otel-signal-with-start-" + UUID.randomUUID()));
        signalWithStart.signalWithStart("startSignal", new Object[0], new Object[0]);
        signalWithStart.getResult(Void.class);

        WorkflowStub updateWithStart =
            client.newUntypedWorkflowStub(
                "UpdateTargetWorkflow",
                options(taskQueue, updateWithStartWorkflowId).toBuilder()
                    .setWorkflowIdConflictPolicy(
                        WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
                    .build());
        updateWithStart
            .startUpdateWithStart(
                UpdateOptions.newBuilder(Void.class)
                    .setUpdateName("doUpdate")
                    .setUpdateId(UPDATE_WITH_START_UPDATE_ID)
                    .setWaitForStage(WorkflowUpdateStage.COMPLETED)
                    .build(),
                new Object[0],
                new Object[0])
            .getResult();
        updateWithStart.signal("updateSignal");
        updateWithStart.getResult(Void.class);

        testWorkflowRule
            .getNexusClient()
            .newUntypedNexusServiceClient(nexusEndpoint, NEXUS_SERVICE_NAME)
            .execute(
                NEXUS_OPERATION_NAME,
                Void.class,
                StartNexusOperationOptions.newBuilder()
                    .setId("otel-nexus-operation-" + UUID.randomUUID())
                    .setScheduleToCloseTimeout(Duration.ofSeconds(10))
                    .build(),
                "");
      } finally {
        schedule.delete();
      }
    } finally {
      clientSpan.end();
    }
    return endedSpans();
  }

  private static WorkflowOptions options(String taskQueue, String workflowId) {
    return WorkflowOptions.newBuilder().setTaskQueue(taskQueue).setWorkflowId(workflowId).build();
  }

  private static byte[] takeTaskToken() {
    try {
      byte[] taskToken = TASK_TOKENS.poll(30, TimeUnit.SECONDS);
      assertNotNull("timed out waiting for activity task token", taskToken);
      return taskToken;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  private static String nexusOp(String operation) {
    return NEXUS_SERVICE_NAME + "/" + operation;
  }

  /** The span tree with Temporal spans enabled. */
  private List<String> fullTree() {
    return Arrays.asList(
        // The scheduled workflow finishes before the client span, so its root appears first.
        "UpdateSchedule:" + scheduleId,
        "  RunWorkflow:StandaloneWorkflow",
        "    standalone-workflow-span",
        "      SignalExternalWorkflow:scheduleStarted",
        "        HandleSignal:scheduleStarted",
        "CreateSchedule:" + reusedScheduleId,
        "  RunWorkflow:SchedulePropagationWorkflow",
        "    schedule-propagation-workflow-span",
        "      SignalExternalWorkflow:scheduleStarted",
        "        HandleSignal:scheduleStarted",
        "client-span",
        "  StartWorkflow:ExternalWorkflowWithSignal",
        "    RunWorkflow:ExternalWorkflowWithSignal",
        "      external-workflow-with-signal-span",
        "  StartWorkflow:ContinueAsNewToDifferentWorkflow",
        "    RunWorkflow:ContinueAsNewToDifferentWorkflow",
        "      ContinueAsNew:DifferentWorkflow",
        "        RunWorkflow:DifferentWorkflow",
        "  StartWorkflow:ComprehensiveWorkflow",
        "    RunWorkflow:ComprehensiveWorkflow",
        "      StartActivity:Activity",
        "        RunActivity:Activity",
        "          activity-span",
        "      StartActivity:LocalActivity",
        "        RunActivity:LocalActivity",
        "          local-activity-span",
        "      StartChildWorkflow:ChildWorkflowWithSignal",
        "        RunWorkflow:ChildWorkflowWithSignal",
        "          child-workflow-with-signal-span",
        // There is no SignalChildWorkflow outbound method; child signals go through
        // signalExternalWorkflow, so this span is named for that method.
        "      SignalExternalWorkflow:childSignal",
        "        HandleSignal:childSignal",
        "      SignalExternalWorkflow:externalSignal",
        "        HandleSignal:externalSignal",
        "      StartNexusOperation:" + nexusOp(NEXUS_OPERATION_NAME),
        "        RunStartNexusOperationHandler:" + nexusOp(NEXUS_OPERATION_NAME),
        "          StartWorkflow:NexusHandlerWorkflow",
        "            RunWorkflow:NexusHandlerWorkflow",
        "              workflow-with-nexus-handler-span",
        "      StartNexusOperation:" + nexusOp(NEXUS_CANCEL_OPERATION_NAME),
        "        RunStartNexusOperationHandler:" + nexusOp(NEXUS_CANCEL_OPERATION_NAME),
        "          StartWorkflow:NexusCancelHandlerWorkflow",
        "            RunWorkflow:NexusCancelHandlerWorkflow",
        "              nexus-cancel-handler-span",
        "        RunCancelNexusOperationHandler:" + nexusOp(NEXUS_CANCEL_OPERATION_NAME),
        "          CancelWorkflow",
        // Continue-as-new links the outbound, continued-run, and user spans.
        "      ContinueAsNew:ComprehensiveWorkflow",
        "        RunWorkflow:ComprehensiveWorkflow",
        "          comprehensive-outbound-workflow-span",
        "      comprehensive-outbound-workflow-span",
        // Update user spans follow their current inbound operation.
        "  StartWorkflowUpdate:testUpdate",
        "    ValidateUpdate:testUpdate",
        "      validate-update-span",
        "        validate-update-span-child",
        "    HandleUpdate:testUpdate",
        "      update-handler-span",
        "        update-handler-child-span",
        // Query handler spans parent under the query that ran them.
        "  QueryWorkflow:getStatus",
        "    HandleQuery:getStatus",
        "      query-handler-span",
        "        query-handler-child-span",
        "  SignalWorkflow:proceed",
        "    HandleSignal:proceed",
        // Headers link standalone StartActivity and RunActivity spans.
        "  StartActivity:StandaloneActivity",
        "    RunActivity:StandaloneActivity",
        "  StartWorkflow:StandaloneWorkflow",
        "    RunWorkflow:StandaloneWorkflow",
        "  StartWorkflow:AsyncCompletionWorkflow",
        "    RunWorkflow:AsyncCompletionWorkflow",
        "      StartActivity:AsyncCompletionActivity",
        "        RunActivity:AsyncCompletionActivity",
        // Cancel, terminate, and describe have no propagation carrier but are still traced.
        "  StartWorkflow:UnservedWorkflow",
        "  CancelWorkflow",
        "  StartWorkflow:UnservedWorkflow",
        "  TerminateWorkflow",
        "  DescribeWorkflow",
        "  CreateSchedule:" + scheduleId,
        "  StartWorkflow:SchedulePropagationReceiver",
        "    RunWorkflow:SchedulePropagationReceiver",
        "  CreateSchedule:" + reusedScheduleSeedId,
        // Signal-with-start links client, worker, signal, and user spans.
        "  SignalWithStartWorkflow:SignalWithStartTarget",
        "    HandleSignal:startSignal",
        "    RunWorkflow:SignalWithStartTarget",
        "      signal-with-start-target-span",
        // Update-with-start links validation, execution, worker, and user spans.
        "  UpdateWithStartWorkflow:doUpdate",
        "    ValidateUpdate:doUpdate",
        "    HandleUpdate:doUpdate",
        "      update start",
        "    RunWorkflow:UpdateTargetWorkflow",
        "      update-target-workflow-span",
        "  SignalWorkflow:updateSignal",
        "    HandleSignal:updateSignal",
        "  StartNexusOperation:" + nexusOp(NEXUS_OPERATION_NAME),
        "    RunStartNexusOperationHandler:" + nexusOp(NEXUS_OPERATION_NAME),
        "      StartWorkflow:NexusHandlerWorkflow",
        "        RunWorkflow:NexusHandlerWorkflow",
        "          workflow-with-nexus-handler-span");
  }

  /**
   * {@link #fullTree()} without any Temporal spans. Only user spans remain, and each reattaches to
   * the nearest surviving ancestor.
   */
  private static List<String> noTemporalSpansTree() {
    return Arrays.asList(
        "standalone-workflow-span",
        "schedule-propagation-workflow-span",
        "client-span",
        "  validate-update-span",
        "    validate-update-span-child",
        "  update-handler-span",
        "    update-handler-child-span",
        "  query-handler-span",
        "    query-handler-child-span",
        "  activity-span",
        "  local-activity-span",
        "  child-workflow-with-signal-span",
        "  workflow-with-nexus-handler-span",
        "  nexus-cancel-handler-span",
        // Continue-as-new emits the user span once per run.
        "  comprehensive-outbound-workflow-span",
        "  comprehensive-outbound-workflow-span",
        "  external-workflow-with-signal-span",
        "  signal-with-start-target-span",
        "  update start",
        "  update-target-workflow-span",
        "  workflow-with-nexus-handler-span");
  }
}
