package io.temporal.workflow;

import static org.junit.Assert.*;

import io.temporal.failure.CanceledFailure;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;

/**
 * Tests for {@link Async#await} - the asynchronous, non-blocking version of {@link Workflow#await}.
 */
public class AsyncAwaitTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestAsyncAwaitWorkflow.class).build();

  @Test
  public void testBasicAsyncAwait() {
    TestWorkflow1 workflow = testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String result = workflow.execute("basic");
    assertEquals("condition1-met condition2-met done", result);
  }

  @Test
  public void testConditionTrueImmediately() {
    TestWorkflow1 workflow = testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String result = workflow.execute("immediate");
    assertEquals("immediate-true", result);
  }

  @Test
  public void testMultipleAsyncAwaits() {
    TestWorkflow1 workflow = testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String result = workflow.execute("multi");
    assertTrue(result.contains("first"));
    assertTrue(result.contains("second"));
    assertTrue(result.contains("third"));
  }

  @Test
  public void testTimedAwaitConditionMetFirst() {
    TestWorkflow1 workflow = testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String result = workflow.execute("timed-condition-first");
    assertEquals("condition-met:true", result);
  }

  @Test
  public void testTimedAwaitTimeoutFirst() {
    TestWorkflow1 workflow = testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String result = workflow.execute("timed-timeout-first");
    assertEquals("timeout:false", result);
  }

  @Test
  public void testTimedAwaitConditionAlreadyTrue() {
    TestWorkflow1 workflow = testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String result = workflow.execute("already-true");
    assertEquals("already-true:true", result);
  }

  @Test
  public void testPromiseAnyOfAsyncAwait() {
    TestWorkflow1 workflow = testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String result = workflow.execute("anyof");
    assertTrue(result.equals("first-won") || result.equals("second-won") || result.equals("both"));
  }

  @Test
  public void testPromiseAllOfAsyncAwait() {
    TestWorkflow1 workflow = testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String result = workflow.execute("allof");
    assertEquals("all-completed", result);
  }

  @Test
  public void testAsyncAwaitChaining() {
    TestWorkflow1 workflow = testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String result = workflow.execute("chaining");
    assertEquals("chained-result:42", result);
  }

  @Test
  public void testAsyncAwaitCancellation() {
    TestWorkflow1 workflow = testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflow1.class);
    String result = workflow.execute("cancellation");
    assertEquals("cancelled", result);
  }

  /** Combined workflow that handles all test scenarios. */
  public static class TestAsyncAwaitWorkflow implements TestWorkflow1 {
    private boolean condition1 = false;
    private boolean condition2 = false;
    private int counter = 0;
    private int value = 0;

    @Override
    public String execute(String testCase) {
      switch (testCase) {
        case "basic":
          return testBasic();
        case "immediate":
          return testImmediate();
        case "multi":
          return testMultiple();
        case "timed-condition-first":
          return testTimedConditionFirst();
        case "timed-timeout-first":
          return testTimedTimeoutFirst();
        case "already-true":
          return testAlreadyTrue();
        case "anyof":
          return testAnyOf();
        case "allof":
          return testAllOf();
        case "chaining":
          return testChaining();
        case "cancellation":
          return testCancellation();
        default:
          return "unknown test case";
      }
    }

    private String testBasic() {
      StringBuilder result = new StringBuilder();

      Promise<Void> await1 = Async.await(() -> condition1);
      Promise<Void> await2 = Async.await(() -> condition2);

      condition1 = true;
      await1.get();
      result.append("condition1-met ");

      condition2 = true;
      await2.get();
      result.append("condition2-met ");

      result.append("done");
      return result.toString();
    }

    private String testImmediate() {
      Promise<Void> promise = Async.await(() -> true);
      promise.get();
      return "immediate-true";
    }

    private String testMultiple() {
      List<String> results = new ArrayList<>();

      Promise<Void> first = Async.await(() -> counter >= 1);
      Promise<Void> second = Async.await(() -> counter >= 2);
      Promise<Void> third = Async.await(() -> counter >= 3);

      first.thenApply(
          v -> {
            results.add("first");
            return null;
          });
      second.thenApply(
          v -> {
            results.add("second");
            return null;
          });
      third.thenApply(
          v -> {
            results.add("third");
            return null;
          });

      counter = 1;
      Workflow.sleep(Duration.ofMillis(1));
      counter = 2;
      Workflow.sleep(Duration.ofMillis(1));
      counter = 3;

      Promise.allOf(first, second, third).get();

      return String.join(" ", results);
    }

    private String testTimedConditionFirst() {
      condition1 = false;
      Promise<Boolean> promise = Async.await(Duration.ofSeconds(10), () -> condition1);

      Workflow.sleep(Duration.ofMillis(100));
      condition1 = true;

      boolean result = promise.get();
      return "condition-met:" + result;
    }

    private String testTimedTimeoutFirst() {
      Promise<Boolean> promise = Async.await(Duration.ofMillis(100), () -> false);
      boolean result = promise.get();
      return "timeout:" + result;
    }

    private String testAlreadyTrue() {
      Promise<Boolean> promise = Async.await(Duration.ofSeconds(10), () -> true);
      boolean result = promise.get();
      return "already-true:" + result;
    }

    private String testAnyOf() {
      condition1 = false;
      condition2 = false;

      Promise<Void> first = Async.await(() -> condition1);
      Promise<Void> second = Async.await(() -> condition2);

      condition1 = true;

      Promise.anyOf(first, second).get();

      if (first.isCompleted() && !second.isCompleted()) {
        return "first-won";
      } else if (second.isCompleted() && !first.isCompleted()) {
        return "second-won";
      } else {
        return "both";
      }
    }

    private String testAllOf() {
      condition1 = false;
      condition2 = false;

      Promise<Void> await1 = Async.await(() -> condition1);
      Promise<Void> await2 = Async.await(() -> condition2);

      condition1 = true;
      Workflow.sleep(Duration.ofMillis(1));
      condition2 = true;

      Promise.allOf(await1, await2).get();
      return "all-completed";
    }

    private String testChaining() {
      value = 0;
      Promise<Integer> chainedPromise =
          Async.await(() -> value > 0)
              .thenApply(v -> value * 2)
              .handle(
                  (result, failure) -> {
                    if (failure != null) {
                      return -1;
                    }
                    return result;
                  });

      value = 21;

      int result = chainedPromise.get();
      return "chained-result:" + result;
    }

    private String testCancellation() {
      condition1 = false;
      final Promise<Void>[] promiseHolder = new Promise[1];

      CancellationScope scope =
          Workflow.newCancellationScope(
              () -> {
                // Create an async await that will never complete on its own
                promiseHolder[0] = Async.await(() -> condition1);
              });

      // Run the scope (this is non-blocking since Async.await returns immediately)
      scope.run();

      // Cancel the scope
      scope.cancel();

      // The promise should fail with CanceledFailure when we try to get it
      try {
        promiseHolder[0].get();
        return "not-cancelled";
      } catch (CanceledFailure e) {
        return "cancelled";
      }
    }
  }
}
