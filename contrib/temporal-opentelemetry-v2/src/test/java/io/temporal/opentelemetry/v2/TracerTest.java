package io.temporal.opentelemetry.v2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import io.opentelemetry.sdk.trace.data.SpanData;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.workflowservice.v1.ResetWorkflowExecutionRequest;
import io.temporal.api.workflowservice.v1.ResetWorkflowExecutionResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowFailedException;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.client.WorkflowTargetOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.TimeoutFailure;
import io.temporal.opentelemetry.v2.TestWorkflows.ChainedContinueAsNewWorkflow;
import io.temporal.opentelemetry.v2.TestWorkflows.ChainedContinueAsNewWorkflowImpl;
import io.temporal.opentelemetry.v2.TestWorkflows.TestActivitiesImpl;
import io.temporal.opentelemetry.v2.TestWorkflows.TracerResetDuringSpanImpl;
import io.temporal.opentelemetry.v2.TestWorkflows.TracerResetLateSourceWorkflowImpl;
import io.temporal.opentelemetry.v2.TestWorkflows.TracerResetWorkflowImpl;
import io.temporal.opentelemetry.v2.TestWorkflows.TracerSpanTimestampWorkflow;
import io.temporal.opentelemetry.v2.TestWorkflows.TracerSpanTimestampWorkflowImpl;
import io.temporal.opentelemetry.v2.TestWorkflows.TracerWorkflow;
import io.temporal.opentelemetry.v2.TestWorkflows.TracerWorkflowImpl;
import io.temporal.opentelemetry.v2.TestWorkflows.TracerWorkflowTaskRetry;
import io.temporal.opentelemetry.v2.TestWorkflows.TracerWorkflowTaskRetryImpl;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;

/**
 * Verifies that spans started by workflow code through the replay-safe global preserve parentage,
 * with Temporal spans disabled.
 */
public class TracerTest extends OtelTestBase {
  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      newRuleBuilder(false)
          .setWorkflowTypes(
              TracerWorkflowImpl.class,
              ChainedContinueAsNewWorkflowImpl.class,
              TracerResetWorkflowImpl.class,
              TracerResetLateSourceWorkflowImpl.class,
              TracerResetDuringSpanImpl.class,
              TracerWorkflowTaskRetryImpl.class,
              TracerSpanTimestampWorkflowImpl.class)
          .setActivityImplementations(new TestActivitiesImpl())
          .build();

  @Test
  public void tracerWorkflow() {
    TracerWorkflow workflow = testWorkflowRule.newWorkflowStub(TracerWorkflow.class);
    WorkflowClient.start(workflow::run, false);

    workflow.update();
    workflow.query();
    workflow.gate();
    WorkflowStub.fromTyped(workflow).getResult(Void.class);
    workflow.query();

    List<SpanData> spans = endedSpans();
    assertSpanTree(
        Arrays.asList(
            "validate start",
            "update start",
            "query start",
            "process start",
            "  record results",
            "process start", // ContinueAsNew
            "  record results",
            // The query after completion replays the run with no span current, so its span is a
            // root like the first query's.
            "query start"),
        spans);
    requireUniqueSpanIds(spans);
  }

  @Test
  public void continueAsNewUnderUserSpan() {
    testWorkflowRule.newWorkflowStub(ChainedContinueAsNewWorkflow.class).run(false);

    List<SpanData> spans = endedSpans();
    assertSpanTree(Arrays.asList("chained-span", "  chained-span"), spans);
    requireUniqueSpanIds(spans);
  }

  @Test
  public void workflowTaskRetryReusesSpanId() {
    TracerWorkflowTaskRetry workflow =
        testWorkflowRule
            .getWorkflowClient()
            .newWorkflowStub(
                TracerWorkflowTaskRetry.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .setWorkflowRunTimeout(Duration.ofSeconds(1))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                    .build());
    try {
      workflow.run();
      throw new AssertionError("expected the workflow run to time out");
    } catch (WorkflowFailedException e) {
      assertTrue(e.getCause().toString(), e.getCause() instanceof TimeoutFailure);
    }

    List<SpanData> spans = endedSpans();
    assertTrue("expected the span from more than one task attempt", spans.size() > 1);
    Set<String> spanIds = new HashSet<>();
    for (SpanData span : spans) {
      spanIds.add(span.getSpanId());
    }
    assertEquals(spanTree(spans).toString(), 1, spanIds.size());
  }

  @Test
  public void explicitSpanStartTimestampSurvivesReplay() {
    TracerSpanTimestampWorkflow workflow =
        testWorkflowRule.newWorkflowStub(TracerSpanTimestampWorkflow.class);
    workflow.run();

    SpanData span = requireSpanNamed(endedSpans(), "explicit timestamp");
    assertEquals(TimeUnit.MILLISECONDS.toNanos(123456789L), span.getStartEpochNanos());
  }

  @Test
  public void resetWithTracerCreatedBeforeResetPoint() {
    List<SpanData> spans = runAndReset(TracerResetWorkflowImpl.class);
    assertSpanTree(Arrays.asList("process start", "  record results", "  record results"), spans);
    requireUniqueSpanIds(spans);
  }

  @Test
  public void resetWithTracerCreatedAfterResetPoint() {
    List<SpanData> spans = runAndReset(TracerResetLateSourceWorkflowImpl.class);
    assertSpanTree(Arrays.asList("process start", "  record results", "  record results"), spans);
    requireUniqueSpanIds(spans);
  }

  @Test
  public void resetWithSpanCrossingResetPoint() {
    List<SpanData> spans = runAndReset(TracerResetDuringSpanImpl.class);
    assertSpanTree(
        Arrays.asList("process start", "  record results", "process start", "  record results"),
        spans);
    // These spans reuse their IDs because they were created before the reset point.
    assertEquals(spans.get(0).getSpanContext(), spans.get(2).getSpanContext());
    assertEquals(spans.get(1).getSpanContext(), spans.get(3).getSpanContext());
    // The new spans end after the old ones.
    assertTrue(spans.get(2).getEndEpochNanos() > spans.get(0).getEndEpochNanos());
    assertTrue(spans.get(3).getEndEpochNanos() > spans.get(1).getEndEpochNanos());
  }

  /**
   * Runs a gated workflow to completion, resets it to the task that handled the gate signal so the
   * work after the signal is redone, and returns every span from both runs.
   */
  private List<SpanData> runAndReset(Class<?> workflowImpl) {
    assumeTrue(
        "Test Server doesn't support reset workflow", SDKTestWorkflowRule.useExternalService);
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    WorkflowStub stub =
        client.newUntypedWorkflowStub(
            workflowImpl.getInterfaces()[0].getSimpleName(),
            WorkflowOptions.newBuilder().setTaskQueue(testWorkflowRule.getTaskQueue()).build());
    stub.start();
    WorkflowExecution execution = stub.getExecution();
    stub.getResult(Void.class);

    ResetWorkflowExecutionResponse response =
        client
            .getWorkflowServiceStubs()
            .blockingStub()
            .resetWorkflowExecution(
                ResetWorkflowExecutionRequest.newBuilder()
                    .setNamespace(client.getOptions().getNamespace())
                    .setWorkflowExecution(execution)
                    .setWorkflowTaskFinishEventId(secondWorkflowTaskCompletedEventId(execution))
                    .setReason("Integration test")
                    .setRequestId(UUID.randomUUID().toString())
                    .build());
    client
        .newUntypedWorkflowStub(
            WorkflowTargetOptions.newBuilder()
                .setWorkflowId(execution.getWorkflowId())
                .setRunId(response.getRunId())
                .build())
        .getResult(Void.class);

    return endedSpans();
  }

  private long secondWorkflowTaskCompletedEventId(WorkflowExecution execution) {
    List<HistoryEvent> completed =
        testWorkflowRule.getHistoryEvents(
            execution.getWorkflowId(), EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED);
    return completed.get(1).getEventId();
  }
}
