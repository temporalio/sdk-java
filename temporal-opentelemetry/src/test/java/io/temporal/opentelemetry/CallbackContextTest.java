package io.temporal.opentelemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.*;
import java.time.Duration;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;

public class CallbackContextTest extends OpenTelemetryBaseTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder()
                  .setInterceptors(new OpenTelemetryClientInterceptor(openTelemetryOptions))
                  .validateAndBuildWithDefaults())
          .setWorkerFactoryOptions(
              WorkerFactoryOptions.newBuilder()
                  .setWorkerInterceptors(new OpenTelemetryWorkerInterceptor(openTelemetryOptions))
                  .validateAndBuildWithDefaults())
          .setWorkflowTypes(WorkflowImpl.class)
          .setActivityImplementations(new ActivityImpl())
          .build();

  @ActivityInterface
  public interface TestActivity {
    @ActivityMethod
    boolean activity();
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    boolean workflow();
  }

  public static class ActivityImpl implements TestActivity {
    @Override
    public boolean activity() {
      // Create a span for some work inside the activity
      Span span = Span.current().getSpanContext().isValid() ? Span.current() : Span.getInvalid();

      // Using the span
      span.setAttribute("operation", "someWork");

      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }

      return true;
    }
  }

  public static class WorkflowImpl implements TestWorkflow {
    private final TestActivity activity =
        Workflow.newActivityStub(
            TestActivity.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofMinutes(1))
                .validateAndBuildWithDefaults());

    @Override
    public boolean workflow() {
      // Chain of activity calls using thenCompose to test span context propagation across callbacks
      return Async.function(activity::activity)
          .thenCompose(
              result ->
                  Promise.allOf(
                      Async.function(activity::activity), Async.function(activity::activity)))
          .thenCompose(result -> Async.function(activity::activity))
          .get();
    }
  }

  /*
   * We are checking that spans structure looks like this:
   * ClientFunction
   *       |
   *     child
   *       v
   * StartWorkflow:TestWorkflow  -follow>  RunWorkflow:TestWorkflow
   *       |                                        |
   *     child                                    child
   *       v                                        v
   * StartActivity:Activity (x4)  -follow>  RunActivity:Activity (x4)
   *                                                |
   *                                              child
   *                                                v
   *                                            someWork (x4)
   */
  @Test
  public void testCallbackContext() {
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
      assertTrue(workflow.workflow());
    } finally {
      clientSpan.end();
    }

    // Create a helper to work with spans
    OpenTelemetrySpansHelper spansHelper = new OpenTelemetrySpansHelper(spanExporter);

    // Get all spans and initialize internal lookup maps
    List<SpanData> allSpans = spansHelper.initializeSpanLookups();

    // Verify we have collected spans from the test run
    assertFalse("Should have recorded spans", allSpans.isEmpty());

    // Find the workflow start span (child of client span)
    List<SpanData> workflowStartSpans = spansHelper.getSpansWithName("StartWorkflow:TestWorkflow");
    assertFalse(workflowStartSpans.isEmpty());
    SpanData workflowStartSpan = workflowStartSpans.get(0);
    assertEquals("StartWorkflow:TestWorkflow", workflowStartSpan.getName());

    // Find the workflow run span (child of workflow start span)
    List<SpanData> workflowRunSpans = spansHelper.getSpansWithName("RunWorkflow:TestWorkflow");
    assertFalse(workflowRunSpans.isEmpty());
    SpanData workflowRunSpan = workflowRunSpans.get(0);
    assertEquals("RunWorkflow:TestWorkflow", workflowRunSpan.getName());

    // Find activity start spans (children of workflow run span)
    List<SpanData> activityStartSpans = spansHelper.getSpansWithName("StartActivity:Activity");

    // There should be 4 activity start spans
    assertEquals(4, activityStartSpans.size());

    // Check each activity span
    for (SpanData activityStartSpan : activityStartSpans) {
      assertEquals("StartActivity:Activity", activityStartSpan.getName());

      // Find the activity run span for this activity start span
      List<SpanData> activityRunSpans = spansHelper.getByParentSpan(activityStartSpan);
      assertFalse(activityRunSpans.isEmpty());

      SpanData activityRunSpan = activityRunSpans.get(0);
      assertEquals("RunActivity:Activity", activityRunSpan.getName());
    }
  }
}
