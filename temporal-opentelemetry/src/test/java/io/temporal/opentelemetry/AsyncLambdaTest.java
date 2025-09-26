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

public class AsyncLambdaTest extends OpenTelemetryBaseTest {

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
          .build();

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    String workflow(String input);
  }

  public static class WorkflowImpl implements TestWorkflow {
    @Override
    public String workflow(String input) {
      // Log initial context info
      Span workflowActiveSpan = Span.current();
      String workflowBaggage = Baggage.current().getEntryValue(BAGGAGE_ITEM_KEY);
      Workflow.getLogger(WorkflowImpl.class)
          .info("Workflow span: " + workflowActiveSpan + ", baggage: " + workflowBaggage);

      Promise<String> lambda =
          Async.function(
              () -> {
                // Get the current span in the lambda
                Span lambdaActiveSpan = Span.current();
                String lambdaBaggage = Baggage.current().getEntryValue(BAGGAGE_ITEM_KEY);

                Workflow.getLogger(WorkflowImpl.class)
                    .info("Lambda span: " + lambdaActiveSpan + ", baggage: " + lambdaBaggage);
                assertNotNull("Active span should not be null in lambda", lambdaActiveSpan);

                // Check if baggage was propagated to lambda
                return lambdaBaggage;
              });

      // Return lambda result which should be a value of the baggage item
      return lambda.get();
    }
  }

  /*
   * We are checking that spans structure looks like this:
   * ClientFunction
   *       |
   *     child
   *       v
   * StartWorkflow:TestWorkflow  -follow>  RunWorkflow:TestWorkflow
   * And async invocation of lambda doesn't create its own child or following spans
   */
  @Test
  public void asyncLambdaCorrectSpanStructureAndBaggagePropagation() {
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
        TestWorkflow workflow =
            client.newWorkflowStub(
                TestWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(testWorkflowRule.getTaskQueue())
                    .validateBuildWithDefaults());

        assertEquals(
            "Baggage item should be propagated all the way to the lambda body",
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
    List<SpanData> workflowStartSpans = spansHelper.getSpansWithName("StartWorkflow:TestWorkflow");
    assertFalse(workflowStartSpans.isEmpty());
    SpanData workflowStartSpan = workflowStartSpans.get(0);
    assertEquals("StartWorkflow:TestWorkflow", workflowStartSpan.getName());

    // Find the workflow run span (child of workflow start span)
    List<SpanData> workflowRunSpans = spansHelper.getSpansWithName("RunWorkflow:TestWorkflow");
    assertFalse(workflowRunSpans.isEmpty());
    SpanData workflowRunSpan = workflowRunSpans.get(0);
    assertEquals("RunWorkflow:TestWorkflow", workflowRunSpan.getName());

    // Verify that the lambda doesn't create any additional spans
    // We should count all spans that have workflowRunSpan as parent
    List<SpanData> workflowChildren = spansHelper.getByParentSpan(workflowRunSpan);

    assertEquals(
        "Lambda shouldn't create any new spans, it should carry an existing span",
        0,
        workflowChildren.size());
  }
}
