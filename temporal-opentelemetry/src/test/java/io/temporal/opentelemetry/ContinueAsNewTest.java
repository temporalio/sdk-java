package io.temporal.opentelemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;

public class ContinueAsNewTest extends OpenTelemetryBaseTest {

  private static final String BAGGAGE_ITEM_KEY = "baggage-item-key";
  private static final String BAGGAGE_ITEM_VALUE = "baggage-item-value";

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
          .build();

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    String workflow(String input, boolean continueAsNew);
  }

  public static class WorkflowImpl implements TestWorkflow {
    @Override
    public String workflow(String input, boolean continueAsNew) {
      // Get current span to get baggage
      Span activeSpan = Span.current();
      assertNotNull(activeSpan);

      // Get baggage value
      String baggageValue = Baggage.current().getEntryValue(BAGGAGE_ITEM_KEY);

      if (continueAsNew) {
        // Continue as new with the same input but don't continue further
        Workflow.continueAsNew(input, false);
      }

      return baggageValue;
    }
  }

  /*
   * We are checking that spans structure looks like this:
   * ClientFunction
   *       |
   *     child
   *       v
   * StartWorkflow:TestWorkflow
   *       |
   *       ↳ follow>  RunWorkflow:TestWorkflow
   *                             |
   *                             ↳ -follow> StartContinueAsNewWorkflow:TestWorkflow  -follow> RunWorkflow:TestWorkflow
   */
  @Test
  public void continueAsNewCorrectSpanStructureAndBaggagePropagation() {
    // Create a client span
    SpanBuilder spanBuilder =
        openTelemetry.getTracer("io.temporal.test").spanBuilder("ClientFunction");
    Span clientSpan = spanBuilder.startSpan();

    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    try (Scope ignored = clientSpan.makeCurrent()) {
      // Set baggage item
      Baggage baggage = Baggage.builder().put(BAGGAGE_ITEM_KEY, BAGGAGE_ITEM_VALUE).build();

      try (Scope ignored2 = Context.current().with(baggage).makeCurrent()) {
        TestWorkflow workflow =
            client.newWorkflowStub(
                TestWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .validateBuildWithDefaults());

        assertEquals(
            "Baggage item should be propagated all the way down continue-as-new chain",
            BAGGAGE_ITEM_VALUE,
            workflow.workflow("input", true));
      }
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

    // Find the continue-as-new start span (child of workflow run span)
    List<SpanData> continueAsNewStartSpans =
        spansHelper.getSpansWithName("StartContinueAsNewWorkflow:TestWorkflow");
    assertFalse(continueAsNewStartSpans.isEmpty());
    SpanData continueAsNewStartSpan = continueAsNewStartSpans.get(0);
    assertEquals("StartContinueAsNewWorkflow:TestWorkflow", continueAsNewStartSpan.getName());

    // Find the continue-as-new run span (child of continue-as-new start span)
    List<SpanData> continueAsNewRunSpans = spansHelper.getByParentSpan(continueAsNewStartSpan);
    assertFalse(continueAsNewRunSpans.isEmpty());
    SpanData continueAsNewRunSpan = continueAsNewRunSpans.get(0);
    assertEquals("RunWorkflow:TestWorkflow", continueAsNewRunSpan.getName());
  }
}
