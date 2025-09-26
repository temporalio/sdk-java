package io.temporal.worker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.opentelemetry.OpenTelemetryBaseTest;
import io.temporal.opentelemetry.OpenTelemetryClientInterceptor;
import io.temporal.opentelemetry.OpenTelemetrySpansHelper;
import io.temporal.opentelemetry.OpenTelemetryWorkerInterceptor;
import io.temporal.opentelemetry.StandardTagNames;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowEvictionTest extends OpenTelemetryBaseTest {

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
          .setWorkflowTypes(SleepingWorkflowImpl.class)
          .build();

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    String workflow(String input);
  }

  public static class SleepingWorkflowImpl implements TestWorkflow {
    @Override
    public String workflow(String input) {
      Workflow.sleep(1000);
      return "ok";
    }
  }

  @Test
  public void workflowEvicted() {
    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    TestWorkflow workflow =
        client.newWorkflowStub(
            TestWorkflow.class,
            WorkflowOptions.newBuilder()
                .setTaskQueue(testWorkflowRule.getTaskQueue())
                .validateBuildWithDefaults());

    // Create a client span
    Span span =
        openTelemetry.getTracer("io.temporal.test").spanBuilder("ClientFunction").startSpan();

    try (Scope ignored = span.makeCurrent()) {
      WorkflowClient.start(workflow::workflow, "input");
    } finally {
      span.end();
    }

    SDKTestWorkflowRule.waitForOKQuery(WorkflowStub.fromTyped(workflow));

    // Evict all workflows from cache
    testWorkflowRule.getTestEnvironment().getWorkerFactory().getCache().invalidateAll();

    // Create a helper to work with spans
    OpenTelemetrySpansHelper spansHelper = new OpenTelemetrySpansHelper(spanExporter);

    // Get all spans and initialize internal lookup maps
    spansHelper.initializeSpanLookups();

    // Find the client span
    SpanData clientSpan = spansHelper.getSpanByName("ClientFunction");
    assertNotNull(clientSpan);

    // Find workflow start span (child of client span)
    List<SpanData> workflowStartSpans = spansHelper.getSpansWithName("StartWorkflow:TestWorkflow");
    assertEquals(1, workflowStartSpans.size());
    SpanData workflowStartSpan = workflowStartSpans.get(0);
    assertEquals("StartWorkflow:TestWorkflow", workflowStartSpan.getName());

    // Find workflow run span (child of workflow start span)
    List<SpanData> workflowRunSpans = spansHelper.getSpansWithName("RunWorkflow:TestWorkflow");
    assertEquals(1, workflowRunSpans.size());
    SpanData workflowRunSpan = workflowRunSpans.get(0);
    assertEquals("RunWorkflow:TestWorkflow", workflowRunSpan.getName());

    // Verify the evicted tag is present
    assertEquals(
        true,
        workflowRunSpan.getAttributes().get(AttributeKey.booleanKey(StandardTagNames.EVICTED)));

    // Verify there's no error status on the evicted span
    assertEquals(StatusCode.UNSET, workflowRunSpan.getStatus().getStatusCode());
  }
}
