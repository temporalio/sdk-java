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
import io.temporal.failure.ApplicationErrorCategory;
import io.temporal.failure.ApplicationFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;

public class ActivityFailureTest extends OpenTelemetryBaseTest {

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
          .setActivityImplementations(new FailingActivityImpl())
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

  private static final AtomicInteger failureCounter = new AtomicInteger(1);

  public static class FailingActivityImpl implements TestActivity {
    @Override
    public String activity(String input) {
      int counter = failureCounter.getAndDecrement();
      if (counter > 0) {
        throw ApplicationFailure.newFailure("fail", "fail");
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
                .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(2).build())
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
   *                                                  |
   *                                                child
   *                                                  v
   *                                       StartActivity:Activity -follow> RunActivity:Activity(failed), RunActivity:Activity
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

    // Find the activity start span (child of workflow run span)
    List<SpanData> activityStartSpans = spansHelper.getSpansWithName("StartActivity:Activity");
    assertFalse(activityStartSpans.isEmpty());
    SpanData activityStartSpan = activityStartSpans.get(0);
    assertEquals("StartActivity:Activity", activityStartSpan.getName());

    // Find the activity run spans (child of activity start span)
    List<SpanData> activityRunSpans = spansHelper.getSpansWithName("RunActivity:Activity");
    assertFalse(activityRunSpans.isEmpty());

    // There should be at least 2 spans - one failed and one successful
    assertEquals(2, activityRunSpans.size());

    // First span should be failed with error status
    SpanData activityFailRunSpan = activityRunSpans.get(0);
    assertEquals(StatusCode.ERROR, activityFailRunSpan.getStatus().getStatusCode());

    // Second span should be successful
    SpanData activitySuccessfulRunSpan = activityRunSpans.get(1);
    assertEquals("RunActivity:Activity", activitySuccessfulRunSpan.getName());
  }

  @Rule
  public SDKTestWorkflowRule benignTestRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkerFactoryOptions(
              WorkerFactoryOptions.newBuilder()
                  .setWorkerInterceptors(new OpenTelemetryWorkerInterceptor(openTelemetryOptions))
                  .validateAndBuildWithDefaults())
          .setWorkflowTypes(BenignWorkflowImpl.class)
          .setActivityImplementations(new BenignFailingActivityImpl())
          .build();

  @ActivityInterface
  public interface BenignTestActivity {
    @ActivityMethod
    String throwMaybeBenign();
  }

  @WorkflowInterface
  public interface BenignTestWorkflow {
    @WorkflowMethod
    String workflow();
  }

  public static class BenignFailingActivityImpl implements BenignTestActivity {
    @Override
    public String throwMaybeBenign() {
      int attempt = Activity.getExecutionContext().getInfo().getAttempt();
      if (attempt == 1) {
        // First attempt: regular failure
        throw ApplicationFailure.newFailure("not benign", "TestFailure");
      } else if (attempt == 2) {
        // Second attempt: benign failure
        throw ApplicationFailure.newBuilder()
            .setMessage("benign")
            .setType("TestFailure")
            .setCategory(ApplicationErrorCategory.BENIGN)
            .build();
      } else {
        // Third attempt: success
        return "success";
      }
    }
  }

  public static class BenignWorkflowImpl implements BenignTestWorkflow {
    private final BenignTestActivity activity =
        Workflow.newActivityStub(
            BenignTestActivity.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofMinutes(1))
                .setRetryOptions(
                    RetryOptions.newBuilder()
                        .setMaximumAttempts(3)
                        .setBackoffCoefficient(1)
                        .setInitialInterval(Duration.ofMillis(100))
                        .build())
                .validateAndBuildWithDefaults());

    @Override
    public String workflow() {
      return activity.throwMaybeBenign();
    }
  }

  @Test
  public void testBenignApplicationFailureSpanBehavior() {
    // Create a client span
    SpanBuilder spanBuilder =
        openTelemetry.getTracer("io.temporal.test").spanBuilder("BenignTestFunction");
    Span clientSpan = spanBuilder.startSpan();

    WorkflowClient client = benignTestRule.getWorkflowClient();
    try (Scope ignored = clientSpan.makeCurrent()) {
      BenignTestWorkflow workflow =
          client.newWorkflowStub(
              BenignTestWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setTaskQueue(benignTestRule.getTaskQueue())
                  .validateBuildWithDefaults());
      assertEquals("success", workflow.workflow());
    } finally {
      clientSpan.end();
    }

    // Create a helper to work with spans
    OpenTelemetrySpansHelper spansHelper = new OpenTelemetrySpansHelper(spanExporter);

    // Initialize internal lookup maps
    spansHelper.initializeSpanLookups();

    // Get the RunActivity spans for ThrowMaybeBenign activity
    List<SpanData> activityRunSpans = spansHelper.getSpansWithName("RunActivity:ThrowMaybeBenign");

    // Verify we have 3 spans for the 3 activity attempts
    assertEquals(
        "Should have 3 activity run spans (one for each attempt)", 3, activityRunSpans.size());

    // OpenTelemetry status code behavior is different from OpenTracing error tags
    // Since the behavior can vary based on implementation details, just verify spans exist

    // First attempt: regular failure (don't assert status code)
    SpanData firstAttemptSpan = activityRunSpans.get(0);
    assertNotNull(firstAttemptSpan); // First attempt span should exist
    assertEquals(StatusCode.ERROR, firstAttemptSpan.getStatus().getStatusCode());

    // Second attempt: benign failure (don't assert status code)
    SpanData secondAttemptSpan = activityRunSpans.get(1);
    assertNotNull(secondAttemptSpan); // Second attempt span should exist
    assertEquals(StatusCode.UNSET, secondAttemptSpan.getStatus().getStatusCode());

    // Third attempt: success (don't assert status code)
    SpanData thirdAttemptSpan = activityRunSpans.get(2);
    assertNotNull(thirdAttemptSpan); // Third attempt span should exist
    assertEquals(StatusCode.UNSET, thirdAttemptSpan.getStatus().getStatusCode());
  }
}
