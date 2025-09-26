package io.temporal.opentelemetry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.common.RetryOptions;
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

public class WorkflowRetryTest extends OpenTelemetryBaseTest {

  private static final AtomicInteger failureCounter = new AtomicInteger(1);

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
  public interface TestActivity1 {
    @ActivityMethod
    String activity1(String input);
  }

  @ActivityInterface
  public interface TestActivity2 {
    @ActivityMethod
    String activity2(String input);
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    String workflow1(String input);
  }

  public static class ActivityImpl implements TestActivity1, TestActivity2 {
    @Override
    public String activity1(String input) {
      return "bar";
    }

    @Override
    public String activity2(String input) {
      return "bar";
    }
  }

  public static class WorkflowImpl implements TestWorkflow {
    private final TestActivity1 activity1 =
        Workflow.newActivityStub(
            TestActivity1.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofMinutes(1))
                .validateAndBuildWithDefaults());

    private final TestActivity2 activity2 =
        Workflow.newActivityStub(
            TestActivity2.class,
            ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofMinutes(1))
                .validateAndBuildWithDefaults());

    @Override
    public String workflow1(String input) {
      activity1.activity1(input);

      if (failureCounter.getAndDecrement() > 0) {
        throw ApplicationFailure.newFailure("fail", "fail");
      }

      return activity2.activity2(input);
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
   *    follow
   *       |
   *       l__-> RunWorkflow:TestWorkflow, (failure in workflow, retry needed), RunWorkflow:TestWorkflow
   *                        |                                                            |
   *                      child                                                        child
   *                        v                                                            v
   *             StartActivity:Activity1 -follow> RunActivity:Activity1         StartActivity:Activity1 -follow> RunActivity:Activity1, StartActivity:Activity2 -follow> RunActivity:Activity2
   */
  @Test
  public void testWorkflowRetrySpanStructure() {
    Span span =
        openTelemetry.getTracer("io.temporal.test").spanBuilder("ClientFunction").startSpan();

    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    try (Scope ignored = span.makeCurrent()) {
      TestWorkflow workflow =
          client.newWorkflowStub(
              TestWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setRetryOptions(
                      RetryOptions.newBuilder()
                          .setInitialInterval(Duration.ofSeconds(1))
                          .setBackoffCoefficient(1.0)
                          .setMaximumAttempts(2)
                          .build())
                  .setWorkflowExecutionTimeout(Duration.ofMinutes(1))
                  .setTaskQueue(testWorkflowRule.getTaskQueue())
                  .validateBuildWithDefaults());
      assertEquals("bar", workflow.workflow1("input"));
    } finally {
      span.end();
    }

    // Create a helper to work with spans
    OpenTelemetrySpansHelper spansHelper = new OpenTelemetrySpansHelper(spanExporter);

    // Get all spans and initialize internal lookup maps
    List<SpanData> allSpans = spansHelper.initializeSpanLookups();

    // Find the client span
    SpanData clientSpan = spansHelper.getSpanByName("ClientFunction");
    assertNotNull(clientSpan);

    // Find the workflow start span (child of client span)
    List<SpanData> workflowStartSpans = spansHelper.getSpansWithName("StartWorkflow:TestWorkflow");
    assertFalse(workflowStartSpans.isEmpty());
    SpanData workflowStartSpan = workflowStartSpans.get(0);
    assertEquals("StartWorkflow:TestWorkflow", workflowStartSpan.getName());

    // Find workflow run spans
    List<SpanData> workflowRunSpans = spansHelper.getSpansWithName("RunWorkflow:TestWorkflow");
    assertEquals(2, workflowRunSpans.size());

    // First workflow run span should have error status
    SpanData workflowFirstRunSpan = workflowRunSpans.get(0);
    assertEquals(StatusCode.ERROR, workflowFirstRunSpan.getStatus().getStatusCode());
    assertEquals("RunWorkflow:TestWorkflow", workflowFirstRunSpan.getName());

    // Find children of first workflow run
    List<SpanData> workflowFirstRunChildren = spansHelper.getByParentSpan(workflowFirstRunSpan);
    assertEquals(1, workflowFirstRunChildren.size());

    // Find activity1 start span (child of first workflow run span)
    List<SpanData> activity1StartSpans = spansHelper.getSpansWithName("StartActivity:Activity1");
    assertFalse(activity1StartSpans.isEmpty());
    SpanData activity1StartSpan = activity1StartSpans.get(0);
    assertEquals("StartActivity:Activity1", activity1StartSpan.getName());

    // Find activity1 run span (child of activity1 start span)
    List<SpanData> activity1RunSpans = spansHelper.getSpansWithName("RunActivity:Activity1");
    assertFalse(activity1RunSpans.isEmpty());
    SpanData activity1RunSpan = activity1RunSpans.get(0);
    assertEquals("RunActivity:Activity1", activity1RunSpan.getName());

    // Second workflow run span should have no error status
    SpanData workflowSecondRunSpan = workflowRunSpans.get(1);
    assertEquals("RunWorkflow:TestWorkflow", workflowSecondRunSpan.getName());

    // Find children of second workflow run - should have activity1 and activity2
    List<SpanData> workflowSecondRunChildren = spansHelper.getByParentSpan(workflowSecondRunSpan);
    assertEquals(2, workflowSecondRunChildren.size());

    // Find activity1 in second workflow run
    List<SpanData> secondActivity1StartSpans =
        spansHelper.getSpansWithName("StartActivity:Activity1");
    assertEquals(
        2, secondActivity1StartSpans.size()); // Should be 2 total - one from each workflow run

    // Find activity2 start span (child of second workflow run span)
    List<SpanData> activity2StartSpans = spansHelper.getSpansWithName("StartActivity:Activity2");
    assertFalse(activity2StartSpans.isEmpty());
    SpanData activity2StartSpan = activity2StartSpans.get(0);
    assertEquals("StartActivity:Activity2", activity2StartSpan.getName());

    // Find activity2 run span (child of activity2 start span)
    List<SpanData> activity2RunSpans = spansHelper.getSpansWithName("RunActivity:Activity2");
    assertFalse(activity2RunSpans.isEmpty());
    SpanData activity2RunSpan = activity2RunSpans.get(0);
    assertEquals("RunActivity:Activity2", activity2RunSpan.getName());
  }
}
