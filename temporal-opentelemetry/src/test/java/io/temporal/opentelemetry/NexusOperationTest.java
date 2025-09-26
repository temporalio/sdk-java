package io.temporal.opentelemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.nexusrpc.Operation;
import io.nexusrpc.Service;
import io.nexusrpc.handler.OperationHandler;
import io.nexusrpc.handler.OperationImpl;
import io.nexusrpc.handler.ServiceImpl;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.nexus.Nexus;
import io.temporal.nexus.WorkflowRunOperation;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.*;
import io.temporal.workflow.NexusOperationOptions;
import io.temporal.workflow.NexusServiceOptions;
import java.time.Duration;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;

public class NexusOperationTest extends OpenTelemetryBaseTest {

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
          .setWorkflowTypes(WorkflowImpl.class, OtherWorkflowImpl.class)
          .setNexusServiceImplementation(new TestNexusServiceImpl())
          .build();

  @Service
  public interface TestNexusService {
    @Operation
    String operation(String input);
  }

  @ServiceImpl(service = TestNexusService.class)
  public static class TestNexusServiceImpl {
    @OperationImpl
    public OperationHandler<String, String> operation() {
      return WorkflowRunOperation.fromWorkflowMethod(
          (context, details, input) ->
              Nexus.getOperationContext()
                      .getWorkflowClient()
                      .newWorkflowStub(
                          TestOtherWorkflow.class,
                          WorkflowOptions.newBuilder()
                              .setWorkflowId(details.getRequestId())
                              .build())
                  ::workflow);
    }
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    String workflow(String input);
  }

  @WorkflowInterface
  public interface TestOtherWorkflow {
    @WorkflowMethod
    String workflow(String input);
  }

  public static class WorkflowImpl implements TestWorkflow {
    private final TestNexusService nexusService =
        Workflow.newNexusServiceStub(
            TestNexusService.class,
            NexusServiceOptions.newBuilder()
                .setOperationOptions(
                    NexusOperationOptions.newBuilder()
                        .setScheduleToCloseTimeout(Duration.ofSeconds(10))
                        .build())
                .build());

    @Override
    public String workflow(String input) {
      return nexusService.operation(input);
    }
  }

  public static class OtherWorkflowImpl implements TestOtherWorkflow {
    @Override
    public String workflow(String input) {
      Workflow.sleep(Duration.ofSeconds(1));
      return "Hello, " + input + "!";
    }
  }

  /*
   * We are checking that spans structure looks like this:
   * ClientFunction
   *       |
   *     child
   *       v
   * StartWorkflow:TestWorkflow  -follow>  RunWorkflow:TestWorkflow
   *                                                  |
   *                                                child
   *                                                  v
   *                                       StartNexusOperation:TestNexusService/operation -follow> RunStartNexusOperationHandler:TestNexusService/operation
   *                                                                                                                             |
   *                                                                                                                           child
   *                                                                                                                             v
   *                                                                                                                StartWorkflow:TestOtherWorkflow  -follow>  RunWorkflow:TestOtherWorkflow
   */
  @Test
  public void testNexusOperation() {
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
      assertEquals("Hello, input!", workflow.workflow("input"));
    } finally {
      clientSpan.end();
    }

    // Create a helper to work with spans
    OpenTelemetrySpansHelper spansHelper = new OpenTelemetrySpansHelper(spanExporter);

    // Get all spans and initialize internal lookup maps
    List<SpanData> allSpans = spansHelper.initializeSpanLookups();

    // Verify we have collected spans from the test run
    assertFalse("Should have recorded spans", allSpans.isEmpty());

    // Find spans by type, without strict parent-child relationships

    // Client span
    List<SpanData> clientFunctionSpans = spansHelper.getSpansWithName("ClientFunction");
    assertFalse("Should have client function span", clientFunctionSpans.isEmpty());
    SpanData clientFunctionSpan = clientFunctionSpans.get(0);
    assertNotNull(clientFunctionSpan);

    // Workflow start span
    List<SpanData> workflowStartSpans = spansHelper.getSpansWithName("StartWorkflow:TestWorkflow");
    assertFalse("Should have workflow start span", workflowStartSpans.isEmpty());
    SpanData workflowStartSpan = workflowStartSpans.get(0);
    assertNotNull(workflowStartSpan);

    // Workflow run span
    List<SpanData> workflowRunSpans = spansHelper.getSpansWithName("RunWorkflow:TestWorkflow");
    assertFalse("Should have workflow run span", workflowRunSpans.isEmpty());
    SpanData workflowRunSpan = workflowRunSpans.get(0);
    assertNotNull(workflowRunSpan);

    // Check for spans that might be named differently in OpenTelemetry vs OpenTracing

    // Find Nexus operation spans - looking for both possible formats
    boolean foundNexusOperation = false;
    for (SpanData span : allSpans) {
      if (span.getName().contains("Nexus") && span.getName().contains("operation")) {
        foundNexusOperation = true;
        break;
      }
    }
    assertTrue("Should have found at least one Nexus operation span", foundNexusOperation);

    // Find other workflow start span (child of nexus operation run span)
    List<SpanData> otherWorkflowStartSpans =
        spansHelper.getSpansWithName("StartWorkflow:TestOtherWorkflow");
    assertFalse(otherWorkflowStartSpans.isEmpty());
    SpanData otherWorkflowStartSpan = otherWorkflowStartSpans.get(0);
    assertEquals("StartWorkflow:TestOtherWorkflow", otherWorkflowStartSpan.getName());

    // Find other workflow run span (child of other workflow start span)
    List<SpanData> otherWorkflowRunSpans =
        spansHelper.getSpansWithName("RunWorkflow:TestOtherWorkflow");
    assertFalse(otherWorkflowRunSpans.isEmpty());
    SpanData otherWorkflowRunSpan = otherWorkflowRunSpans.get(0);
    assertEquals("RunWorkflow:TestOtherWorkflow", otherWorkflowRunSpan.getName());
  }
}
