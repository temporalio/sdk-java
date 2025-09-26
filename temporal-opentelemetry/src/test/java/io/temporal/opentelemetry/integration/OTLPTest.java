package io.temporal.opentelemetry.integration;

import static org.junit.Assert.*;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.opentelemetry.OpenTelemetryClientInterceptor;
import io.temporal.opentelemetry.OpenTelemetryOptions;
import io.temporal.opentelemetry.OpenTelemetrySpanContextCodec;
import io.temporal.opentelemetry.OpenTelemetrySpansHelper;
import io.temporal.opentelemetry.OpenTelemetryWorkerInterceptor;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.List;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

/**
 * Integration test that demonstrates the usage of OpenTelemetry with Temporal using the OTLP
 * exporter. This is the equivalent of the JaegerTest in the opentracing module.
 */
public class OTLPTest {

  // In-memory span exporter for test verification
  private final InMemorySpanExporter spanExporter = InMemorySpanExporter.create();

  // Setup tracer provider with in-memory exporter
  private final SdkTracerProvider tracerProvider =
      SdkTracerProvider.builder()
          .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
          .setResource(
              Resource.create(
                  io.opentelemetry.api.common.Attributes.builder()
                      .put("service.name", "temporal-opentelemetry-test")
                      .build()))
          .build();

  // Create the OpenTelemetry instance with both trace and baggage propagators
  private final OpenTelemetry openTelemetry =
      OpenTelemetrySdk.builder()
          .setTracerProvider(tracerProvider)
          .setPropagators(
              ContextPropagators.create(
                  TextMapPropagator.composite(
                      W3CTraceContextPropagator.getInstance(),
                      io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator.getInstance())))
          .build();

  private final Tracer tracer = openTelemetry.getTracer("temporal-test");

  // Configure OpenTelemetry options similar to how JaegerTest does it
  private final OpenTelemetryOptions OTLP_CONFIG =
      OpenTelemetryOptions.newBuilder()
          .setOpenTelemetry(openTelemetry)
          .setTracer(tracer)
          .setSpanContextCodec(OpenTelemetrySpanContextCodec.TEXT_MAP_CODEC)
          .build();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder()
                  .setInterceptors(new OpenTelemetryClientInterceptor(OTLP_CONFIG))
                  .validateAndBuildWithDefaults())
          .setWorkerFactoryOptions(
              WorkerFactoryOptions.newBuilder()
                  .setWorkerInterceptors(new OpenTelemetryWorkerInterceptor(OTLP_CONFIG))
                  .validateAndBuildWithDefaults())
          .setWorkflowTypes(WorkflowImpl.class)
          .setActivityImplementations(new ActivityImpl())
          .build();

  @After
  public void tearDown() {
    spanExporter.reset();
    tracerProvider.close();
  }

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

  public static class ActivityImpl implements TestActivity {
    @Override
    public String activity(String input) {
      // Get current span to verify we have an active span in the activity
      Span currentSpan = Span.current();
      assertNotNull(currentSpan);

      // Add an attribute to the current span
      currentSpan.setAttribute("activity.input", input);

      return "bar";
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
    public String workflow(String input) {
      // Get current span to verify we have an active span in the workflow
      Span currentSpan = Span.current();
      assertNotNull(currentSpan);

      // Add an attribute to the current span
      currentSpan.setAttribute("workflow.input", input);

      return activity.activity(input);
    }
  }

  @Test
  public void testSpanPropagation() {
    // Create a client span
    Span span = tracer.spanBuilder("ClientFunction").startSpan();

    // Add baggage item to demonstrate propagation
    Baggage baggage = Baggage.builder().put("test-baggage-key", "test-baggage-value").build();

    // Create a context with both span and baggage
    Context context = Context.current().with(span).with(baggage);

    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    try (Scope scope = context.makeCurrent()) {
      TestWorkflow workflow =
          client.newWorkflowStub(
              TestWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setTaskQueue(testWorkflowRule.getTaskQueue())
                  .validateBuildWithDefaults());
      assertEquals("bar", workflow.workflow("input"));
    } finally {
      span.end();
    }

    // Create a helper to work with spans
    OpenTelemetrySpansHelper spansHelper = new OpenTelemetrySpansHelper(spanExporter);

    // Get all spans and initialize internal lookup maps
    List<SpanData> allSpans = spansHelper.initializeSpanLookups();

    // Verify spans were created and exported
    assertNotNull(allSpans);
    assertTrue(allSpans.size() > 1);

    // Find the client span
    SpanData clientSpan = spansHelper.getSpanByName("ClientFunction");
    assertNotNull(clientSpan);

    // Find workflow spans
    List<SpanData> workflowStartSpans = spansHelper.getSpansWithName("StartWorkflow:TestWorkflow");
    assertFalse(workflowStartSpans.isEmpty());

    List<SpanData> workflowRunSpans = spansHelper.getSpansWithName("RunWorkflow:TestWorkflow");
    assertFalse(workflowRunSpans.isEmpty());

    // Find activity spans
    List<SpanData> activityStartSpans = spansHelper.getSpansWithName("StartActivity:Activity");
    assertFalse(activityStartSpans.isEmpty());

    List<SpanData> activityRunSpans = spansHelper.getSpansWithName("RunActivity:Activity");
    assertFalse(activityRunSpans.isEmpty());

    // Verify spans have the expected status
    SpanData workflowRunSpan = workflowRunSpans.get(0);
    assertEquals(StatusCode.UNSET, workflowRunSpan.getStatus().getStatusCode());

    // Verify the spans form a proper trace hierarchy
    assertEquals("RunWorkflow:TestWorkflow", workflowRunSpan.getName());
  }
}
