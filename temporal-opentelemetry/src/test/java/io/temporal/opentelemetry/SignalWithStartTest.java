package io.temporal.opentelemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.temporal.client.BatchRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;

public class SignalWithStartTest extends OpenTelemetryBaseTest {

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

    @SignalMethod
    void signal(String signal);
  }

  public static class WorkflowImpl implements TestWorkflow {
    private String signal;

    @Override
    public String workflow(String input) {
      return signal;
    }

    @Override
    public void signal(String signal) {
      this.signal = signal;
    }
  }

  /*
   * We are checking that spans structure looks like this:
   * ClientFunction
   *       |
   *     child
   *       v
   * SignalWithStartWorkflow:TestWorkflow
   *       |
   *     child
   *       |----> HandleSignal:signal (only if using external service)
   *       |
   *     child
   *       v
   * RunWorkflow:TestWorkflow
   */
  @Test
  public void signalWithStart() {
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    TestWorkflow workflow =
        client.newWorkflowStub(
            TestWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .validateBuildWithDefaults());

    Span span =
        openTelemetry.getTracer("io.temporal.test").spanBuilder("ClientFunction").startSpan();

    try (Scope ignored = span.makeCurrent()) {
      BatchRequest batchRequest = client.newSignalWithStartRequest();
      batchRequest.add(workflow::workflow, "input");
      batchRequest.add(workflow::signal, "signal");
      client.signalWithStart(batchRequest);
    } finally {
      span.end();
    }

    // wait for the workflow completion
    WorkflowStub.fromTyped(workflow).getResult(String.class);

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
    List<SpanData> workflowStartSpans =
        spansHelper.getSpansWithName("SignalWithStartWorkflow:TestWorkflow");
    assertFalse(workflowStartSpans.isEmpty());
    SpanData workflowStartSpan = workflowStartSpans.get(0);
    assertEquals("SignalWithStartWorkflow:TestWorkflow", workflowStartSpan.getName());

    if (SDKTestWorkflowRule.useExternalService) {
      List<SpanData> workflowSpans = spansHelper.getByParentSpan(workflowStartSpan);
      assertEquals(2, workflowSpans.size());

      SpanData workflowSignalSpan = workflowSpans.get(0);
      assertEquals("HandleSignal:signal", workflowSignalSpan.getName());

      SpanData workflowRunSpan = workflowSpans.get(1);
      assertEquals("RunWorkflow:TestWorkflow", workflowRunSpan.getName());
    } else {
      List<SpanData> workflowRunSpans = spansHelper.getSpansWithName("RunWorkflow:TestWorkflow");
      assertFalse(workflowRunSpans.isEmpty());
      SpanData workflowRunSpan = workflowRunSpans.get(0);
      assertEquals("RunWorkflow:TestWorkflow", workflowRunSpan.getName());
    }
  }
}
