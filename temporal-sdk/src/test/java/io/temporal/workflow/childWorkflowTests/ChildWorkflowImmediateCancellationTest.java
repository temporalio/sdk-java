package io.temporal.workflow.childWorkflowTests;

import static org.junit.Assert.*;

import io.temporal.failure.CanceledFailure;
import io.temporal.failure.ChildWorkflowFailure;
import io.temporal.internal.Signal;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.*;
import io.temporal.workflow.shared.TestWorkflows.NoArgsWorkflow;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;

/**
 * This test ensures that the behavior of immediate (inside the same WFT as scheduling) child
 * workflow cancellation by the parent workflow.
 *
 * @see <a href="https://github.com/temporalio/sdk-java/issues/1037">Issue #1037</a>
 */
public class ChildWorkflowImmediateCancellationTest {

  private static final Signal CHILD_EXECUTED = new Signal();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              ParentWorkflowUsingCancellationRequestPromiseImpl.class,
              ParentWorkflowUsingResultPromiseImpl.class,
              ParentWorkflowUsingStartPromiseImpl.class,
              TestChildWorkflowImpl.class)
          .build();

  @Test
  public void testCancellationRequestPromise() throws InterruptedException {
    ParentWorkflowUsingCancellationRequestPromise workflow =
        testWorkflowRule.newWorkflowStub(ParentWorkflowUsingCancellationRequestPromise.class);
    assertEquals("ok", workflow.execute());
    assertFalse(CHILD_EXECUTED.waitForSignal(1, TimeUnit.SECONDS));
  }

  @Test
  public void testResultPromise() throws InterruptedException {
    ParentWorkflowUsingResultPromise workflow =
        testWorkflowRule.newWorkflowStub(ParentWorkflowUsingResultPromise.class);
    assertEquals("ok", workflow.execute());
    assertFalse(CHILD_EXECUTED.waitForSignal(1, TimeUnit.SECONDS));
  }

  @Test
  public void testStartPromise() throws InterruptedException {
    ParentWorkflowUsingStartPromise workflow =
        testWorkflowRule.newWorkflowStub(ParentWorkflowUsingStartPromise.class);
    assertEquals("ok", workflow.execute());
    assertFalse(CHILD_EXECUTED.waitForSignal(1, TimeUnit.SECONDS));
  }

  @WorkflowInterface
  public interface ParentWorkflowUsingCancellationRequestPromise {
    @WorkflowMethod
    String execute();
  }

  @WorkflowInterface
  public interface ParentWorkflowUsingResultPromise {
    @WorkflowMethod
    String execute();
  }

  @WorkflowInterface
  public interface ParentWorkflowUsingStartPromise {
    @WorkflowMethod
    String execute();
  }

  public static class ParentWorkflowUsingCancellationRequestPromiseImpl
      implements ParentWorkflowUsingCancellationRequestPromise {
    @Override
    public String execute() {
      NoArgsWorkflow child = Workflow.newChildWorkflowStub(NoArgsWorkflow.class);
      CancellationScope cancellationScope =
          Workflow.newCancellationScope(() -> Async.procedure(child::execute));
      cancellationScope.run();
      cancellationScope.cancel();

      cancellationScope.getCancellationRequest().get();

      return "ok";
    }
  }

  public static class ParentWorkflowUsingResultPromiseImpl
      implements ParentWorkflowUsingResultPromise {
    @Override
    public String execute() {
      NoArgsWorkflow child = Workflow.newChildWorkflowStub(NoArgsWorkflow.class);
      AtomicReference<Promise<Void>> childPromise = new AtomicReference<>();
      CancellationScope cancellationScope =
          Workflow.newCancellationScope(() -> childPromise.set(Async.procedure(child::execute)));
      cancellationScope.run();
      cancellationScope.cancel();

      ChildWorkflowFailure childWorkflowFailure =
          assertThrows(ChildWorkflowFailure.class, () -> childPromise.get().get());
      Throwable cause = childWorkflowFailure.getCause();
      assertTrue(cause instanceof CanceledFailure);

      return "ok";
    }
  }

  public static class ParentWorkflowUsingStartPromiseImpl
      implements ParentWorkflowUsingStartPromise {
    @Override
    public String execute() {
      NoArgsWorkflow child = Workflow.newChildWorkflowStub(NoArgsWorkflow.class);
      CancellationScope cancellationScope =
          Workflow.newCancellationScope(() -> Async.procedure(child::execute));
      cancellationScope.run();
      cancellationScope.cancel();

      ChildWorkflowFailure childWorkflowFailure =
          assertThrows(
              ChildWorkflowFailure.class, () -> Workflow.getWorkflowExecution(child).get());
      Throwable cause = childWorkflowFailure.getCause();
      assertTrue(cause instanceof CanceledFailure);

      return "ok";
    }
  }

  public static class TestChildWorkflowImpl implements NoArgsWorkflow {
    @Override
    public void execute() {
      CHILD_EXECUTED.signal();
      Workflow.sleep(Duration.ofHours(1));
    }
  }
}
