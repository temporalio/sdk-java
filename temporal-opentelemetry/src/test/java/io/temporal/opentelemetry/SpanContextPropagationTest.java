package io.temporal.opentelemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
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
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;

public class SpanContextPropagationTest extends OpenTelemetryBaseTest {

  private static final String BAGGAGE_ITEM_KEY = "baggage-item-key";

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
          .setActivityImplementations(new OpenTelemetryAwareActivityImpl())
          .build();

  @ActivityInterface
  public interface TestActivity {
    @ActivityMethod
    String activity1(String input);
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    String workflow1(String input);
  }

  public static class OpenTelemetryAwareActivityImpl implements TestActivity {
    @Override
    public String activity1(String input) {
      Span activeSpan = Span.current();

      assertNotNull(activeSpan);

      // Get baggage value
      String baggageValue = Baggage.current().getEntryValue(BAGGAGE_ITEM_KEY);
      return baggageValue;
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
    public String workflow1(String input) {
      Span activeSpan = Span.current();

      assertNotNull(activeSpan);

      return activity.activity1(input);
    }
  }

  /**
   * This test checks that all elements of a trivial workflow with an activity are connected with
   * the same root span. We set baggage item on the top level in a client span and use the baggage
   * item inside the activity. This way we ensure that the baggage item has been propagated all the
   * way from top to bottom.
   */
  @Test
  public void testBaggageItemPropagationToActivity() {
    // Create a client span
    Span span =
        openTelemetry.getTracer("io.temporal.test").spanBuilder("ClientFunction").startSpan();

    // Create the baggage
    final String BAGGAGE_ITEM_VALUE = "baggage-item-value";
    Baggage baggage = Baggage.builder().put(BAGGAGE_ITEM_KEY, BAGGAGE_ITEM_VALUE).build();

    // Create a context with both the span and baggage
    Context context = Context.current().with(span).with(baggage);

    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    try (Scope ignored = context.makeCurrent()) {
      // Now both span and baggage are part of the same context
      TestWorkflow workflow =
          client.newWorkflowStub(
              TestWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setTaskQueue(testWorkflowRule.getTaskQueue())
                  .validateBuildWithDefaults());
      assertEquals(BAGGAGE_ITEM_VALUE, workflow.workflow1("input"));
    } finally {
      span.end();
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
    List<SpanData> workflowStartSpans = spansHelper.getSpansWithName("StartWorkflow:TestWorkflow");
    assertFalse(workflowStartSpans.isEmpty());
    SpanData workflowStartSpan = workflowStartSpans.get(0);
    assertEquals("StartWorkflow:TestWorkflow", workflowStartSpan.getName());

    // Find workflow run span (child of workflow start span)
    List<SpanData> workflowRunSpans = spansHelper.getSpansWithName("RunWorkflow:TestWorkflow");
    assertFalse(workflowRunSpans.isEmpty());
    SpanData workflowRunSpan = workflowRunSpans.get(0);
    assertEquals("RunWorkflow:TestWorkflow", workflowRunSpan.getName());

    // Find activity start span (child of workflow run span)
    List<SpanData> activityStartSpans = spansHelper.getSpansWithName("StartActivity:Activity1");
    assertFalse(activityStartSpans.isEmpty());
    SpanData activityStartSpan = activityStartSpans.get(0);
    assertEquals("StartActivity:Activity1", activityStartSpan.getName());

    // Find activity run span (child of activity start span)
    List<SpanData> activityRunSpans = spansHelper.getSpansWithName("RunActivity:Activity1");
    assertFalse(activityRunSpans.isEmpty());
    SpanData activityRunSpan = activityRunSpans.get(0);
    assertEquals("RunActivity:Activity1", activityRunSpan.getName());
  }
}
