package io.temporal.opentracing;

import static org.junit.Assert.assertEquals;

import io.opentracing.Scope;
import io.opentracing.mock.MockSpan;
import io.opentracing.mock.MockTracer;
import io.opentracing.tag.Tags;
import io.opentracing.util.ThreadLocalScopeManager;
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
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;

public class ActivityFailureTest {

  private final MockTracer mockTracer =
      new MockTracer(new ThreadLocalScopeManager(), MockTracer.Propagator.TEXT_MAP);

  private final OpenTracingOptions OT_OPTIONS =
      OpenTracingOptions.newBuilder().setTracer(mockTracer).build();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowClientOptions(
              WorkflowClientOptions.newBuilder()
                  .setInterceptors(new OpenTracingClientInterceptor(OT_OPTIONS))
                  .validateAndBuildWithDefaults())
          .setWorkerFactoryOptions(
              WorkerFactoryOptions.newBuilder()
                  .setWorkerInterceptors(new OpenTracingWorkerInterceptor(OT_OPTIONS))
                  .validateAndBuildWithDefaults())
          .setWorkflowTypes(WorkflowImpl.class)
          .setActivityImplementations(new FailingActivityImpl())
          .build();

  @After
  public void tearDown() {
    mockTracer.reset();
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
    MockSpan span = mockTracer.buildSpan("ClientFunction").start();

    WorkflowClient client = testWorkflowRule.getWorkflowClient();
    try (Scope scope = mockTracer.scopeManager().activate(span)) {
      TestWorkflow workflow =
          client.newWorkflowStub(
              TestWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setTaskQueue(testWorkflowRule.getTaskQueue())
                  .validateBuildWithDefaults());
      assertEquals("bar", workflow.workflow("input"));
    } finally {
      span.finish();
    }

    OpenTracingSpansHelper spansHelper = new OpenTracingSpansHelper(mockTracer.finishedSpans());

    MockSpan clientSpan = spansHelper.getSpanByOperationName("ClientFunction");

    MockSpan workflowStartSpan = spansHelper.getByParentSpan(clientSpan).get(0);
    assertEquals(clientSpan.context().spanId(), workflowStartSpan.parentId());
    assertEquals("StartWorkflow:TestWorkflow", workflowStartSpan.operationName());

    MockSpan workflowRunSpan = spansHelper.getByParentSpan(workflowStartSpan).get(0);
    assertEquals(workflowStartSpan.context().spanId(), workflowRunSpan.parentId());
    assertEquals("RunWorkflow:TestWorkflow", workflowRunSpan.operationName());

    MockSpan activityStartSpan = spansHelper.getByParentSpan(workflowRunSpan).get(0);
    assertEquals(workflowRunSpan.context().spanId(), activityStartSpan.parentId());
    assertEquals("StartActivity:Activity", activityStartSpan.operationName());

    List<MockSpan> activityRunSpans = spansHelper.getByParentSpan(activityStartSpan);

    MockSpan activityFailRunSpan = activityRunSpans.get(0);
    assertEquals(activityStartSpan.context().spanId(), activityFailRunSpan.parentId());
    assertEquals("RunActivity:Activity", activityFailRunSpan.operationName());
    assertEquals(true, activityFailRunSpan.tags().get(Tags.ERROR.getKey()));

    MockSpan activitySuccessfulRunSpan = activityRunSpans.get(1);
    assertEquals(activityStartSpan.context().spanId(), activitySuccessfulRunSpan.parentId());
    assertEquals("RunActivity:Activity", activitySuccessfulRunSpan.operationName());
  }

  @Rule
  public SDKTestWorkflowRule benignTestRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkerFactoryOptions(
              WorkerFactoryOptions.newBuilder()
                  .setWorkerInterceptors(new OpenTracingWorkerInterceptor(OT_OPTIONS))
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
    MockSpan span = mockTracer.buildSpan("BenignTestFunction").start();

    WorkflowClient client = benignTestRule.getWorkflowClient();
    try (Scope scope = mockTracer.scopeManager().activate(span)) {
      BenignTestWorkflow workflow =
          client.newWorkflowStub(
              BenignTestWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setTaskQueue(benignTestRule.getTaskQueue())
                  .validateBuildWithDefaults());
      assertEquals("success", workflow.workflow());
    } finally {
      span.finish();
    }

    List<MockSpan> allSpans = mockTracer.finishedSpans();

    // Filter to only activity execution spans (RunActivity spans created by worker interceptor)
    List<MockSpan> activityRunSpans =
        allSpans.stream()
            .filter(s -> s.operationName().startsWith("RunActivity:"))
            .collect(java.util.stream.Collectors.toList());

    assertEquals(3, activityRunSpans.size());

    // First attempt: regular failure - should have ERROR tag
    MockSpan firstAttemptSpan = activityRunSpans.get(0);
    assertEquals(true, firstAttemptSpan.tags().get(Tags.ERROR.getKey()));

    // Second attempt: benign failure - should NOT have ERROR tag
    MockSpan secondAttemptSpan = activityRunSpans.get(1);
    assertEquals(null, secondAttemptSpan.tags().get(Tags.ERROR.getKey()));

    // Third attempt: success - should not have ERROR tag
    MockSpan thirdAttemptSpan = activityRunSpans.get(2);
    assertEquals(null, thirdAttemptSpan.tags().get(Tags.ERROR.getKey()));
  }
}
