package io.temporal.opentelemetry.v2;

import static io.temporal.opentelemetry.v2.TestWorkflows.TASK_TOKENS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowIdConflictPolicy;
import io.temporal.client.UpdateOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowTargetOptions;
import io.temporal.client.WorkflowUpdateStage;
import io.temporal.opentelemetry.v2.TestWorkflows.AsyncCompletionWorkflow;
import io.temporal.opentelemetry.v2.TestWorkflows.AsyncCompletionWorkflowImpl;
import io.temporal.opentelemetry.v2.TestWorkflows.AsyncLambdaWorkflow;
import io.temporal.opentelemetry.v2.TestWorkflows.AsyncLambdaWorkflowImpl;
import io.temporal.opentelemetry.v2.TestWorkflows.BenignErrorWorkflow;
import io.temporal.opentelemetry.v2.TestWorkflows.BenignErrorWorkflowImpl;
import io.temporal.opentelemetry.v2.TestWorkflows.ChildWorkflowWithSignal;
import io.temporal.opentelemetry.v2.TestWorkflows.ChildWorkflowWithSignalImpl;
import io.temporal.opentelemetry.v2.TestWorkflows.ErrorWorkflow;
import io.temporal.opentelemetry.v2.TestWorkflows.ErrorWorkflowImpl;
import io.temporal.opentelemetry.v2.TestWorkflows.PromiseCallbackWorkflow;
import io.temporal.opentelemetry.v2.TestWorkflows.PromiseCallbackWorkflowImpl;
import io.temporal.opentelemetry.v2.TestWorkflows.SpanKindWorkflow;
import io.temporal.opentelemetry.v2.TestWorkflows.SpanKindWorkflowImpl;
import io.temporal.opentelemetry.v2.TestWorkflows.TestActivitiesImpl;
import io.temporal.opentelemetry.v2.TestWorkflows.TracerWorkflow;
import io.temporal.opentelemetry.v2.TestWorkflows.TracerWorkflowImpl;
import io.temporal.opentelemetry.v2.TestWorkflows.UnservedWorkflow;
import io.temporal.opentelemetry.v2.TestWorkflows.UnservedWorkflowImpl;
import io.temporal.opentelemetry.v2.TestWorkflows.UpdateTargetWorkflowImpl;
import io.temporal.opentelemetry.v2.TestWorkflows.WorkflowOutboundCall;
import io.temporal.opentelemetry.v2.TestWorkflows.WorkflowOutboundTagsWorkflow;
import io.temporal.opentelemetry.v2.TestWorkflows.WorkflowOutboundTagsWorkflowImpl;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;

/** Tests interceptor behavior. */
public class InterceptorTest extends OtelTestBase {
  private static final String UNSERVED_TASK_QUEUE = "opentelemetry-v2-unserved";
  private static final String UPDATE_WITH_START_ID = "interceptor-update-with-start";
  private static final String TARGET_UPDATE_ID = "interceptor-update";
  private static final AttributeKey<String> WORKFLOW_ID =
      AttributeKey.stringKey("temporalWorkflowID");
  private static final AttributeKey<String> RUN_ID = AttributeKey.stringKey("temporalRunID");
  private static final AttributeKey<String> UPDATE_ID = AttributeKey.stringKey("temporalUpdateID");

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      newRuleBuilder(true)
          .setWorkflowTypes(
              SpanKindWorkflowImpl.class,
              BenignErrorWorkflowImpl.class,
              ErrorWorkflowImpl.class,
              AsyncLambdaWorkflowImpl.class,
              PromiseCallbackWorkflowImpl.class,
              AsyncCompletionWorkflowImpl.class,
              TracerWorkflowImpl.class,
              ChildWorkflowWithSignalImpl.class,
              UnservedWorkflowImpl.class,
              UpdateTargetWorkflowImpl.class,
              WorkflowOutboundTagsWorkflowImpl.class)
          .setActivityImplementations(new TestActivitiesImpl())
          .build();

  @Test
  public void spanKind() {
    testWorkflowRule.newWorkflowStub(SpanKindWorkflow.class).run();

    Map<String, SpanKind> kinds = new HashMap<>();
    for (SpanData span : endedSpans()) {
      kinds.put(span.getName(), span.getKind());
    }
    assertEquals(SpanKind.SERVER, kinds.get("RunWorkflow:SpanKindWorkflow"));
    assertEquals(SpanKind.CLIENT, kinds.get("StartActivity:NopActivity"));
    assertEquals(SpanKind.SERVER, kinds.get("RunActivity:NopActivity"));
  }

  @Test
  public void asyncLambdaPreservesApplicationContext() {
    testWorkflowRule.newWorkflowStub(AsyncLambdaWorkflow.class).run();

    assertSpanTree(
        Arrays.asList(
            "StartWorkflow:AsyncLambdaWorkflow",
            "  RunWorkflow:AsyncLambdaWorkflow",
            "    parent",
            "      child",
            "        StartActivity:NopActivity",
            "          RunActivity:NopActivity"),
        endedSpans());
  }

  @Test
  public void promiseCallbackPreservesApplicationContext() {
    PromiseCallbackWorkflow workflow =
        testWorkflowRule.newWorkflowStub(PromiseCallbackWorkflow.class);
    WorkflowClient.start(workflow::run);
    testWorkflowRule
        .getWorkflowClient()
        .newActivityCompletionClient()
        .complete(takeTaskToken(), null);
    WorkflowStub.fromTyped(workflow).getResult(Void.class);

    assertSpanTree(
        Arrays.asList(
            "StartWorkflow:PromiseCallbackWorkflow",
            "  RunWorkflow:PromiseCallbackWorkflow",
            "    application",
            "      StartActivity:AsyncCompletionActivity",
            "        RunActivity:AsyncCompletionActivity",
            "      callback",
            "        StartActivity:NopActivity",
            "          RunActivity:NopActivity"),
        endedSpans());
  }

  @Test
  public void workflowClientSignalIncludesTargetRunId() {
    TracerWorkflow workflow = testWorkflowRule.newWorkflowStub(TracerWorkflow.class);
    WorkflowExecution execution = WorkflowClient.start(workflow::run, false);
    TracerWorkflow target = targetWorkflow(execution);
    target.gate();
    WorkflowStub.fromTyped(workflow).getResult(Void.class);

    assertWorkflowExecutionTags(requireSpanNamed(endedSpans(), "SignalWorkflow:gate"), execution);
  }

  @Test
  public void workflowClientQueryIncludesTargetRunId() {
    TracerWorkflow workflow = testWorkflowRule.newWorkflowStub(TracerWorkflow.class);
    WorkflowExecution execution = WorkflowClient.start(workflow::run, false);
    TracerWorkflow target = targetWorkflow(execution);

    assertEquals("ok", target.query());
    target.gate();
    WorkflowStub.fromTyped(workflow).getResult(Void.class);

    assertWorkflowExecutionTags(requireSpanNamed(endedSpans(), "QueryWorkflow:query"), execution);
  }

  @Test
  public void workflowClientUpdateIncludesTargetRunIdAndUpdateId() {
    TracerWorkflow workflow = testWorkflowRule.newWorkflowStub(TracerWorkflow.class);
    WorkflowExecution execution = WorkflowClient.start(workflow::run, false);
    TracerWorkflow target = targetWorkflow(execution);

    WorkflowStub.fromTyped(target)
        .startUpdate(
            UpdateOptions.newBuilder(Void.class)
                .setUpdateName("update")
                .setUpdateId(TARGET_UPDATE_ID)
                .setWaitForStage(WorkflowUpdateStage.COMPLETED)
                .build())
        .getResult();
    target.gate();
    WorkflowStub.fromTyped(workflow).getResult(Void.class);

    SpanData update = requireSpanNamed(endedSpans(), "StartWorkflowUpdate:update");
    assertWorkflowExecutionTags(update, execution);
    assertEquals(TARGET_UPDATE_ID, update.getAttributes().get(UPDATE_ID));
  }

  @Test
  public void workflowClientStartIncludesWorkflowId() {
    TracerWorkflow workflow = testWorkflowRule.newWorkflowStub(TracerWorkflow.class);
    WorkflowExecution execution = WorkflowClient.start(workflow::run, true);

    SpanData start = requireSpanNamed(endedSpans(), "StartWorkflow:TracerWorkflow");
    assertEquals(execution.getWorkflowId(), start.getAttributes().get(WORKFLOW_ID));
    assertNull(start.getAttributes().get(RUN_ID));
  }

  @Test
  public void workflowClientUpdateWithStartIncludesWorkflowIdAndUpdateId() {
    String workflowId = "interceptor-update-with-start-target";
    WorkflowStub target =
        testWorkflowRule
            .getWorkflowClient()
            .newUntypedWorkflowStub(
                "UpdateTargetWorkflow",
                WorkflowOptions.newBuilder()
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .setWorkflowId(workflowId)
                    .setWorkflowIdConflictPolicy(
                        WorkflowIdConflictPolicy.WORKFLOW_ID_CONFLICT_POLICY_USE_EXISTING)
                    .build());

    target
        .startUpdateWithStart(
            UpdateOptions.newBuilder(Void.class)
                .setUpdateName("doUpdate")
                .setUpdateId(UPDATE_WITH_START_ID)
                .setWaitForStage(WorkflowUpdateStage.COMPLETED)
                .build(),
            new Object[0],
            new Object[0])
        .getResult();
    target.signal("updateSignal");
    target.getResult(Void.class);

    SpanData updateWithStart = requireSpanNamed(endedSpans(), "UpdateWithStartWorkflow:doUpdate");
    assertEquals(workflowId, updateWithStart.getAttributes().get(WORKFLOW_ID));
    assertEquals(UPDATE_WITH_START_ID, updateWithStart.getAttributes().get(UPDATE_ID));
  }

  @Test
  public void workflowOutboundChildStartIncludesWorkflowId() {
    runWorkflowOutboundCall(WorkflowOutboundCall.CHILD_WORKFLOW);

    SpanData start = requireSpanNamed(endedSpans(), "StartChildWorkflow:ChildWorkflowWithSignal");
    SpanData run = requireSpanNamed(endedSpans(), "RunWorkflow:ChildWorkflowWithSignal");
    assertEquals(run.getAttributes().get(WORKFLOW_ID), start.getAttributes().get(WORKFLOW_ID));
    assertNull(start.getAttributes().get(RUN_ID));
  }

  @Test
  public void workflowOutboundChildSignalIncludesTargetExecution() {
    runWorkflowOutboundCall(WorkflowOutboundCall.CHILD_WORKFLOW);

    SpanData run = requireSpanNamed(endedSpans(), "RunWorkflow:ChildWorkflowWithSignal");
    SpanData signal = requireSpanNamed(endedSpans(), "SignalExternalWorkflow:childSignal");
    assertWorkflowExecutionTags(signal, workflowExecution(run));
  }

  @Test
  public void workflowOutboundExternalSignalIncludesTargetExecution() {
    ChildWorkflowWithSignal target =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                ChildWorkflowWithSignal.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .setWorkflowId("external-signal-target-" + UUID.randomUUID())
                    .build());
    WorkflowExecution execution = WorkflowClient.start(target::run);

    WorkflowOutboundTagsWorkflow workflow =
        testWorkflowRule.newWorkflowStub(WorkflowOutboundTagsWorkflow.class);
    workflow.run(WorkflowOutboundCall.EXTERNAL_SIGNAL, execution);

    assertWorkflowExecutionTags(
        requireSpanNamed(endedSpans(), "SignalExternalWorkflow:childSignal"), execution);
  }

  @Test
  public void workflowOutboundCancelIncludesTargetExecution() {
    UnservedWorkflow target =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                UnservedWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(UNSERVED_TASK_QUEUE)
                    .setWorkflowId("external-cancel-target-" + UUID.randomUUID())
                    .build());
    WorkflowExecution execution = WorkflowClient.start(target::run);

    WorkflowOutboundTagsWorkflow workflow =
        testWorkflowRule.newWorkflowStub(WorkflowOutboundTagsWorkflow.class);
    workflow.run(WorkflowOutboundCall.EXTERNAL_CANCEL, execution);

    assertWorkflowExecutionTags(requireSpanNamed(endedSpans(), "CancelWorkflow"), execution);
  }

  private TracerWorkflow targetWorkflow(WorkflowExecution execution) {
    return testWorkflowRule
        .getWorkflowClient()
        .newWorkflowStub(
            TracerWorkflow.class,
            WorkflowTargetOptions.newBuilder().setWorkflowExecution(execution).build());
  }

  private void runWorkflowOutboundCall(WorkflowOutboundCall call) {
    WorkflowOutboundTagsWorkflow workflow =
        testWorkflowRule.newWorkflowStub(WorkflowOutboundTagsWorkflow.class);
    workflow.run(call, WorkflowExecution.getDefaultInstance());
  }

  private static WorkflowExecution workflowExecution(SpanData span) {
    return WorkflowExecution.newBuilder()
        .setWorkflowId(span.getAttributes().get(WORKFLOW_ID))
        .setRunId(span.getAttributes().get(RUN_ID))
        .build();
  }

  private static void assertWorkflowExecutionTags(SpanData span, WorkflowExecution execution) {
    assertEquals(execution.getWorkflowId(), span.getAttributes().get(WORKFLOW_ID));
    assertEquals(execution.getRunId(), span.getAttributes().get(RUN_ID));
  }

  @Test
  public void benignErrorLeavesSpanStatusUnset() {
    assertThrows(
        WorkflowFailedException.class,
        () -> testWorkflowRule.newWorkflowStub(BenignErrorWorkflow.class).run());

    assertSpanTree(
        Arrays.asList("StartWorkflow:BenignErrorWorkflow", "  RunWorkflow:BenignErrorWorkflow"),
        endedSpans());
    SpanData run = requireSpanNamed(endedSpans(), "RunWorkflow:BenignErrorWorkflow");
    assertEquals(StatusCode.UNSET, run.getStatus().getStatusCode());
  }

  @Test
  public void errorSetsSpanStatusError() {
    assertThrows(
        WorkflowFailedException.class,
        () -> testWorkflowRule.newWorkflowStub(ErrorWorkflow.class).run());

    assertSpanTree(
        Arrays.asList("StartWorkflow:ErrorWorkflow", "  RunWorkflow:ErrorWorkflow"), endedSpans());
    SpanData run = requireSpanNamed(endedSpans(), "RunWorkflow:ErrorWorkflow");
    assertEquals(StatusCode.ERROR, run.getStatus().getStatusCode());
  }

  @Test
  public void continueAsNewLeavesWorkflowSpanUnset() {
    TracerWorkflow workflow = testWorkflowRule.newWorkflowStub(TracerWorkflow.class);
    WorkflowClient.start(workflow::run, false);
    workflow.gate();
    WorkflowStub.fromTyped(workflow).getResult(Void.class);

    SpanData run = requireSpanNamed(endedSpans(), "RunWorkflow:TracerWorkflow");
    assertEquals(StatusCode.UNSET, run.getStatus().getStatusCode());
    assertTrue(run.getEvents().toString(), run.getEvents().isEmpty());
  }

  @Test
  public void pendingActivityLeavesActivitySpanUnset() {
    AsyncCompletionWorkflow workflow =
        testWorkflowRule.newWorkflowStub(AsyncCompletionWorkflow.class);
    WorkflowClient.start(workflow::run);
    byte[] taskToken = takeTaskToken();
    testWorkflowRule.getWorkflowClient().newActivityCompletionClient().complete(taskToken, null);
    WorkflowStub.fromTyped(workflow).getResult(Void.class);

    SpanData activity = requireSpanNamed(endedSpans(), "RunActivity:AsyncCompletionActivity");
    assertEquals(StatusCode.UNSET, activity.getStatus().getStatusCode());
    assertTrue(activity.getEvents().toString(), activity.getEvents().isEmpty());
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
}
