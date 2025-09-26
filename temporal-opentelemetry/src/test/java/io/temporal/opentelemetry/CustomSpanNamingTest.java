package io.temporal.opentelemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;

public class CustomSpanNamingTest extends OpenTelemetryBaseTest {
  private final OpenTelemetryOptions OT_OPTIONS =
      OpenTelemetryOptions.newBuilder()
          .setOpenTelemetry(openTelemetry)
          .setTracer(openTelemetry.getTracer("io.temporal.test"))
          .setSpanBuilderProvider(new TestSpanBuilderProvider())
          .build();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder()
                  .setInterceptors(new OpenTelemetryClientInterceptor(OT_OPTIONS))
                  .validateAndBuildWithDefaults())
          .setWorkerFactoryOptions(
              WorkerFactoryOptions.newBuilder()
                  .setWorkerInterceptors(new OpenTelemetryWorkerInterceptor(OT_OPTIONS))
                  .validateAndBuildWithDefaults())
          .setWorkflowTypes(WorkflowImpl.class)
          .setActivityImplementations(new FailingActivityImpl())
          .build();

  @ActivityInterface
  public interface TestActivity {
    @ActivityMethod
    String activity(String input);
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    String workflow(String input);
  }

  private static final AtomicInteger failureCounter = new AtomicInteger(1);

  public static class FailingActivityImpl implements TestActivity {
    @Override
    public String activity(String input) {
      int counter = failureCounter.getAndDecrement();
      if (counter > 0) {
        throw ApplicationFailure.newFailure("fail", "fail");
      } else {
        return "bar";
      }
    }
  }

  public static class WorkflowImpl implements TestWorkflow {
    private final TestActivity activity =
        Workflow.newActivityStub(
            TestActivity.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofMinutes(1))
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
                .validateAndBuildWithDefaults());

    @Override
    public String workflow(String input) {
      return activity.activity(input);
    }
  }

  /** Custom span builder provider that customizes span names and adds resource.name attribute */
  private static class TestSpanBuilderProvider
      extends io.temporal.opentelemetry.internal.ActionTypeAndNameSpanBuilderProvider {
    @Override
    protected String getSpanName(SpanCreationContext context) {
      // Only use the operation type prefix without the action name
      return context.getSpanOperationType().getDefaultPrefix();
    }

    @Override
    protected Map<String, String> getSpanTags(SpanCreationContext context) {
      // Use a safer approach to handle potential null values
      Map<String, String> tags = new HashMap<>();

      // Safely add workflow ID if available
      if (context.getWorkflowId() != null) {
        tags.put(StandardTagNames.WORKFLOW_ID, context.getWorkflowId());
      }

      // Add run ID if available
      if (context.getRunId() != null) {
        tags.put(StandardTagNames.RUN_ID, context.getRunId());
      }

      // Add parent workflow ID if available
      if (context.getParentWorkflowId() != null) {
        tags.put(StandardTagNames.PARENT_WORKFLOW_ID, context.getParentWorkflowId());
      }

      // Add parent run ID if available
      if (context.getParentRunId() != null) {
        tags.put(StandardTagNames.PARENT_RUN_ID, context.getParentRunId());
      }

      // Always add the custom resource.name attribute
      tags.put("resource.name", context.getActionName());

      return tags;
    }
  }

  /*
   * We are checking that spans structure looks like this:
   * ClientFunction
   *       |
   *     child
   *       v
   * StartWorkflow  -follow>  RunWorkflow
   *       |                        |
   *     child                    child
   *       v                        v
   * StartActivity  -follow>  RunActivity (with error)
   *                                |
   *                              retry
   *                                v
   *                           RunActivity (success)
   */

  @Test
  public void testActivityFailureSpanStructure() {
    // Create a client span
    SpanBuilder spanBuilder =
        openTelemetry.getTracer("io.temporal.test").spanBuilder("ClientFunction");
    Span clientSpan = spanBuilder.startSpan();

    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    try (Scope ignored = clientSpan.makeCurrent()) {
      TestWorkflow workflow =
          client.newWorkflowStub(
              TestWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setTaskQueue(testWorkflowRule.getTaskQueue())
                  .validateBuildWithDefaults());
      assertEquals("bar", workflow.workflow("input"));
    } finally {
      clientSpan.end();
    }

    // Create a helper to work with spans
    OpenTelemetrySpansHelper spansHelper = new OpenTelemetrySpansHelper(spanExporter);

    // Get all spans and initialize internal lookup maps
    List<SpanData> allSpans = spansHelper.initializeSpanLookups();

    // Verify we have collected spans from the test run
    assertFalse("Should have recorded spans", allSpans.isEmpty());

    // Find the client span
    SpanData clientFunctionSpan = spansHelper.getSpanByName("ClientFunction");
    assertNotNull(clientFunctionSpan);

    // Find workflow start span (child of client span)
    List<SpanData> workflowStartSpans = spansHelper.getByParentSpan(clientFunctionSpan);
    assertFalse(workflowStartSpans.isEmpty());
    SpanData workflowStartSpan = workflowStartSpans.get(0);
    assertEquals("StartWorkflow", workflowStartSpan.getName());
    assertEquals(
        "TestWorkflow",
        workflowStartSpan.getAttributes().get(AttributeKey.stringKey("resource.name")));

    // Find workflow run span (child of workflow start span)
    List<SpanData> workflowRunSpans = spansHelper.getByParentSpan(workflowStartSpan);
    assertFalse(workflowRunSpans.isEmpty());
    SpanData workflowRunSpan = workflowRunSpans.get(0);
    assertEquals("RunWorkflow", workflowRunSpan.getName());
    assertEquals(
        "TestWorkflow",
        workflowRunSpan.getAttributes().get(AttributeKey.stringKey("resource.name")));

    // Find activity start span (child of workflow run span)
    List<SpanData> activityStartSpans = spansHelper.getByParentSpan(workflowRunSpan);
    assertFalse(activityStartSpans.isEmpty());
    SpanData activityStartSpan = activityStartSpans.get(0);
    assertEquals("StartActivity", activityStartSpan.getName());
    assertEquals(
        "Activity", activityStartSpan.getAttributes().get(AttributeKey.stringKey("resource.name")));

    // Find activity run spans (children of activity start span)
    List<SpanData> activityRunSpans = spansHelper.getByParentSpan(activityStartSpan);
    assertEquals(2, activityRunSpans.size());

    // First activity run span should have failed
    SpanData activityFailRunSpan = activityRunSpans.get(0);
    assertEquals("RunActivity", activityFailRunSpan.getName());
    assertEquals(
        "Activity",
        activityFailRunSpan.getAttributes().get(AttributeKey.stringKey("resource.name")));
    assertEquals(StatusCode.ERROR, activityFailRunSpan.getStatus().getStatusCode());

    // Second activity run span should have succeeded
    SpanData activitySuccessfulRunSpan = activityRunSpans.get(1);
    assertEquals("RunActivity", activitySuccessfulRunSpan.getName());
    assertEquals(
        "Activity",
        activitySuccessfulRunSpan.getAttributes().get(AttributeKey.stringKey("resource.name")));
  }
}
