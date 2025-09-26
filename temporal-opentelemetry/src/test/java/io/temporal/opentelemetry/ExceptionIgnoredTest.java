package io.temporal.opentelemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ApplicationFailure;
import io.temporal.serviceclient.CheckedExceptionWrapper;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;

public class ExceptionIgnoredTest extends OpenTelemetryBaseTest {

  private final OpenTelemetryOptions openTelemetryOptions =
      OpenTelemetryOptions.newBuilder()
          .setOpenTelemetry(openTelemetry)
          .setTracer(openTelemetry.getTracer("io.temporal.test"))
          // Use the test span builder provider
          .setSpanBuilderProvider(new TestOpenTelemetrySpanBuilderProvider())
          .setIsErrorPredicate(
              ex -> {
                if (ex instanceof CheckedExceptionWrapper && ex.getCause() instanceof IOException) {
                  return false;
                }
                return true;
              })
          .build();

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
    String activity(String input);
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    String workflow(String input);
  }

  private static final AtomicInteger failureCounter = new AtomicInteger(2);

  public static class ActivityImpl implements TestActivity {
    @Override
    public String activity(String input) {
      int counter = failureCounter.getAndDecrement();
      if (counter > 1) {
        throw ApplicationFailure.newFailure("fail", "fail");
      } else if (counter > 0) {
        throw Activity.wrap(new IOException());
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
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                .validateAndBuildWithDefaults());

    @Override
    public String workflow(String input) {
      return activity.activity(input);
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
   * StartActivity:Activity  -follow>  RunActivity:Activity (IOException but no ERROR status)
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
    List<SpanData> activityStartSpans = spansHelper.getSpansWithName("StartActivity:Activity");
    assertFalse(activityStartSpans.isEmpty());
    SpanData activityStartSpan = activityStartSpans.get(0);
    assertEquals("StartActivity:Activity", activityStartSpan.getName());

    // Find activity run spans (children of activity start span)
    List<SpanData> activityRunSpans = spansHelper.getByParentSpan(activityStartSpan);
    assertEquals(3, activityRunSpans.size());

    // First activity run span should have failed with error
    SpanData failedActivityRunSpan = activityRunSpans.get(0);
    assertEquals("RunActivity:Activity", failedActivityRunSpan.getName());
    assertEquals(StatusCode.ERROR, failedActivityRunSpan.getStatus().getStatusCode());

    // Second activity run span should have failed but error should be ignored (no ERROR status)
    SpanData failureIgnoredActivityRunSpan = activityRunSpans.get(1);
    assertEquals("RunActivity:Activity", failureIgnoredActivityRunSpan.getName());
    assertEquals(StatusCode.UNSET, failureIgnoredActivityRunSpan.getStatus().getStatusCode());

    // Third activity run span should have succeeded
    SpanData successfulActivityRunSpan = activityRunSpans.get(2);
    assertEquals("RunActivity:Activity", successfulActivityRunSpan.getName());
  }
}
