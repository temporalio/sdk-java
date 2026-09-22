package io.temporal.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.failure.CanceledFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import java.time.Duration;
import java.util.function.Supplier;
import org.junit.Rule;
import org.junit.Test;

public class AsyncAwaitTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(TestAsyncAwaitWorkflowImpl.class)
          .setActivityImplementations(new TestAwaitActivityImpl())
          .build();

  @Test
  public void testAlreadySatisfiedConditionCompletesWithTrue() {
    TestAsyncAwaitWorkflow workflow = newWorkflowStub();

    assertEquals("true", workflow.execute("alreadySatisfied"));
  }

  @Test
  public void testUnsatisfiedConditionCompletesWithFalseAfterTimeout() {
    TestAsyncAwaitWorkflow workflow = newWorkflowStub();

    assertEquals("false:true", workflow.execute("timeout"));
  }

  @Test
  public void testAwaitDoesNotBlockCallingWorkflowThread() {
    TestAsyncAwaitWorkflow workflow = newWorkflowStub();

    assertEquals("true", workflow.execute("nonBlocking"));
  }

  @Test
  public void testSignalConditionComposesWithActivityPromise() {
    TestAsyncAwaitWorkflow workflow = newWorkflowStub();
    WorkflowClient.start(workflow::execute, "signalAndActivity");

    workflow.unblock();

    assertEquals("true:activity", WorkflowStub.fromTyped(workflow).getResult(String.class));
  }

  @Test
  public void testCancellationFailsPromise() {
    TestAsyncAwaitWorkflow workflow = newWorkflowStub();

    assertEquals("CanceledFailure", workflow.execute("cancellation"));
  }

  @Test
  public void testPredicateExceptionFailsPromise() {
    TestAsyncAwaitWorkflow workflow = newWorkflowStub();

    assertEquals("IllegalStateException:predicate failed", workflow.execute("predicateFailure"));
  }

  @Test
  public void testAlreadySatisfiedConditionWithoutTimeoutCompletesWithNull() {
    assertEquals("null", newWorkflowStub().execute("alreadySatisfiedWithoutTimeout"));
  }

  @Test
  public void testAwaitWithoutTimeoutDoesNotBlockCallingWorkflowThread() {
    assertEquals("null", newWorkflowStub().execute("nonBlockingWithoutTimeout"));
  }

  @Test
  public void testSignalConditionWithoutTimeoutComposesWithActivityPromise() {
    TestAsyncAwaitWorkflow workflow = newWorkflowStub();
    WorkflowClient.start(workflow::execute, "signalAndActivityWithoutTimeout");

    workflow.unblock();

    assertEquals("null:activity", WorkflowStub.fromTyped(workflow).getResult(String.class));
  }

  @Test
  public void testCancellationWithoutTimeoutFailsPromise() {
    assertEquals("CanceledFailure", newWorkflowStub().execute("cancellationWithoutTimeout"));
  }

  @Test
  public void testPredicateExceptionWithoutTimeoutFailsPromise() {
    assertEquals(
        "IllegalStateException:predicate failed",
        newWorkflowStub().execute("predicateFailureWithoutTimeout"));
  }

  private TestAsyncAwaitWorkflow newWorkflowStub() {
    return testWorkflowRule.newWorkflowStubTimeoutOptions(TestAsyncAwaitWorkflow.class);
  }

  @WorkflowInterface
  public interface TestAsyncAwaitWorkflow {

    @WorkflowMethod
    String execute(String testCase);

    @SignalMethod
    void unblock();
  }

  @ActivityInterface
  public interface TestAwaitActivity {
    String execute();
  }

  public static class TestAwaitActivityImpl implements TestAwaitActivity {

    @Override
    public String execute() {
      return "activity";
    }
  }

  public static class TestAsyncAwaitWorkflowImpl implements TestAsyncAwaitWorkflow {

    private boolean unblocked;
    private Promise<?> cancellationPromise;

    @Override
    public String execute(String testCase) {
      switch (testCase) {
        case "alreadySatisfied":
          return Async.await(Duration.ofHours(1), () -> true).get().toString();
        case "alreadySatisfiedWithoutTimeout":
          Promise<Void> satisfied = Async.await(() -> true);
          return String.valueOf(satisfied.get());
        case "timeout":
          long timeoutStart = Workflow.currentTimeMillis();
          boolean result = Async.await(Duration.ofMinutes(1), () -> false).get();
          return result
              + ":"
              + (Workflow.currentTimeMillis() - timeoutStart >= Duration.ofMinutes(1).toMillis());
        case "nonBlocking":
          long start = Workflow.currentTimeMillis();
          Async.await(Duration.ofHours(1), () -> false);
          return Boolean.toString(
              Workflow.currentTimeMillis() - start < Duration.ofHours(1).toMillis());
        case "nonBlockingWithoutTimeout":
          Promise<Void> pending = Async.await(() -> unblocked);
          unblocked = true;
          return String.valueOf(pending.get());
        case "signalAndActivity":
        case "signalAndActivityWithoutTimeout":
          Promise<?> condition =
              testCase.equals("signalAndActivity")
                  ? Async.await(Duration.ofHours(1), () -> unblocked)
                  : Async.await(() -> unblocked);
          TestAwaitActivity activity =
              Workflow.newActivityStub(
                  TestAwaitActivity.class,
                  ActivityOptions.newBuilder()
                      .setStartToCloseTimeout(Duration.ofSeconds(10))
                      .build());
          Promise<String> activityResult = Async.function(activity::execute);
          Promise.allOf(condition, activityResult).get();
          return condition.get() + ":" + activityResult.get();
        case "cancellation":
        case "cancellationWithoutTimeout":
          CancellationScope scope =
              Workflow.newCancellationScope(
                  () ->
                      cancellationPromise =
                          testCase.equals("cancellation")
                              ? Async.await(Duration.ofHours(1), () -> false)
                              : Async.await(() -> false));
          scope.run();
          scope.cancel();
          RuntimeException cancellationFailure = cancellationPromise.getFailure();
          assertTrue(cancellationFailure instanceof CanceledFailure);
          return cancellationFailure.getClass().getSimpleName();
        case "predicateFailure":
        case "predicateFailureWithoutTimeout":
          Supplier<Boolean> predicate =
              () -> {
                throw new IllegalStateException("predicate failed");
              };
          Promise<?> failed =
              testCase.equals("predicateFailure")
                  ? Async.await(Duration.ofHours(1), predicate)
                  : Async.await(predicate);
          RuntimeException predicateFailure = failed.getFailure();
          return predicateFailure.getClass().getSimpleName() + ":" + predicateFailure.getMessage();
        default:
          throw new IllegalArgumentException("Unknown test case: " + testCase);
      }
    }

    @Override
    public void unblock() {
      unblocked = true;
    }
  }
}
