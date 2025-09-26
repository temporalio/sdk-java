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
import io.temporal.workflow.*;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;

/**
 * Tests for async child workflow span context propagation with OpenTelemetry. This test verifies
 * that span contexts and baggage are properly propagated to child workflows when using async child
 * workflows.
 */
public class AsyncChildWorkflowTest extends OpenTelemetryBaseTest {

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
          .setWorkflowTypes(ParentWorkflowImpl.class, ChildWorkflowImpl.class)
          .build();

  @WorkflowInterface
  public interface ParentWorkflow {
    @WorkflowMethod
    String workflow(String input);
  }

  @WorkflowInterface
  public interface ChildWorkflow {
    @WorkflowMethod
    String childWorkflow(String input);
  }

  public static class ParentWorkflowImpl implements ParentWorkflow {
    @Override
    public String workflow(String input) {
      // Get the current span and check it's valid
      Span activeSpan = Span.current();
      assertNotNull("Active span should not be null in parent workflow", activeSpan);

      // Create a child workflow and execute it asynchronously
      ChildWorkflow child = Workflow.newChildWorkflowStub(ChildWorkflow.class);
      return Async.function(child::childWorkflow, input).get();
    }
  }

  public static class ChildWorkflowImpl implements ChildWorkflow {
    @Override
    public String childWorkflow(String input) {
      // Get the current span and check it's valid
      Span activeSpan = Span.current();
      assertNotNull("Active span should not be null in child workflow", activeSpan);

      // Check if baggage was propagated
      String baggageValue = Baggage.current().getEntryValue(BAGGAGE_ITEM_KEY);
      return baggageValue;
    }
  }

  /*
   * We are checking that spans structure looks like this:
   * ClientFunction
   *       |
   *     child
   *       v
   * StartWorkflow:ParentWorkflow  -follow>  RunWorkflow:ParentWorkflow
   *                                                  |
   *                                                child
   *                                                  v
   *                                       StartChildWorkflow:ChildWorkflow -follow> RunWorkflow:ChildWorkflow
   */
  @Test
  public void asyncChildWFCorrectSpanStructureAndBaggagePropagation() {
    // Create a client span
    SpanBuilder spanBuilder =
        openTelemetry.getTracer("io.temporal.test").spanBuilder("ClientFunction");
    Span clientSpan = spanBuilder.startSpan();

    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    try (Scope ignored = clientSpan.makeCurrent()) {
      // Set baggage item
      final String BAGGAGE_ITEM_VALUE = "baggage-item-value";
      Baggage baggage = Baggage.builder().put(BAGGAGE_ITEM_KEY, BAGGAGE_ITEM_VALUE).build();

      // Make the baggage current
      try (Scope ignored2 = Context.current().with(baggage).makeCurrent()) {
        ParentWorkflow workflow =
            client.newWorkflowStub(
                ParentWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .validateBuildWithDefaults());

        assertEquals(
            "Baggage item should be propagated all the way down to the child workflow",
            BAGGAGE_ITEM_VALUE,
            workflow.workflow("input"));
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
    List<SpanData> workflowStartSpans =
        spansHelper.getSpansWithName("StartWorkflow:ParentWorkflow");
    assertFalse(workflowStartSpans.isEmpty());
    SpanData workflowStartSpan = workflowStartSpans.get(0);
    assertEquals("StartWorkflow:ParentWorkflow", workflowStartSpan.getName());

    // Find the workflow run span (child of workflow start span)
    List<SpanData> workflowRunSpans = spansHelper.getSpansWithName("RunWorkflow:ParentWorkflow");
    assertFalse(workflowRunSpans.isEmpty());
    SpanData workflowRunSpan = workflowRunSpans.get(0);
    assertEquals("RunWorkflow:ParentWorkflow", workflowRunSpan.getName());

    // Find the child workflow start span (child of workflow run span)
    List<SpanData> childWorkflowStartSpans =
        spansHelper.getSpansWithName("StartChildWorkflow:ChildWorkflow");
    assertFalse(childWorkflowStartSpans.isEmpty());
    SpanData childWorkflowStartSpan = childWorkflowStartSpans.get(0);
    assertEquals("StartChildWorkflow:ChildWorkflow", childWorkflowStartSpan.getName());

    // Find the child workflow run span (child of child workflow start span)
    List<SpanData> childWorkflowRunSpans =
        spansHelper.getSpansWithName("RunWorkflow:ChildWorkflow");
    assertFalse(childWorkflowRunSpans.isEmpty());
    SpanData childWorkflowRunSpan = childWorkflowRunSpans.get(0);
    assertEquals("RunWorkflow:ChildWorkflow", childWorkflowRunSpan.getName());
  }
}
