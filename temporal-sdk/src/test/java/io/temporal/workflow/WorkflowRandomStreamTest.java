package io.temporal.workflow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.internal.sync.ReadOnlyException;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflowReturnString;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.time.Duration;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class WorkflowRandomStreamTest {
  private static boolean replayed;
  private static boolean useNamedStream;

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(
              RandomStreamWorkflow.class,
              RandomLongWorkflowImpl.class,
              ContinueAsNewWorkflowImpl.class,
              ParentWorkflowImpl.class,
              RandomIsolationWorkflow.class,
              RetainedStreamWorkflowImpl.class)
          .setWorkerOptions(
              WorkerOptions.newBuilder()
                  .setStickyQueueScheduleToStartTimeout(Duration.ZERO)
                  .build())
          .build();

  @Before
  public void setUp() {
    replayed = false;
    useNamedStream = false;
  }

  @Test
  public void namedStreamIsStableAcrossReplay() {
    TestWorkflowReturnString workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(TestWorkflowReturnString.class);

    assertEquals("ok", workflow.execute());
    assertTrue(replayed);
  }

  @Test
  public void differentRunsUseDifferentStreams() {
    RandomLongWorkflow first =
        testWorkflowRule.newWorkflowStubTimeoutOptions(RandomLongWorkflow.class);
    RandomLongWorkflow second =
        testWorkflowRule.newWorkflowStubTimeoutOptions(RandomLongWorkflow.class);

    assertNotEquals(first.run(), second.run());
  }

  @Test
  public void continueAsNewAndChildRunsUseDifferentStreams() {
    ParentWorkflow workflow = testWorkflowRule.newWorkflowStubTimeoutOptions(ParentWorkflow.class);

    long[] values = workflow.run();

    assertEquals(3, values.length);
    assertNotEquals(values[0], values[1]);
    assertNotEquals(values[0], values[2]);
    assertNotEquals(values[1], values[2]);
  }

  @Test
  public void namedStreamDoesNotPerturbWorkflowRandom() throws Exception {
    RandomIsolation workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(RandomIsolation.class);
    assertEquals("ok", workflow.run());
    WorkflowExecutionHistory history =
        testWorkflowRule
            .getWorkflowClient()
            .fetchHistory(WorkflowStub.fromTyped(workflow).getExecution().getWorkflowId());

    useNamedStream = true;
    WorkflowReplayer.replayWorkflowExecution(history, RandomIsolationWorkflow.class);
  }

  @Test
  public void retainedStreamRejectsReadOnlyDrawsWithoutAdvancing() throws Exception {
    RetainedStreamWorkflow workflow =
        testWorkflowRule.newWorkflowStubTimeoutOptions(RetainedStreamWorkflow.class);
    WorkflowStub untyped = WorkflowStub.fromTyped(workflow);
    untyped.start();

    assertEquals("query rejected", workflow.query());
    assertEquals("update rejected", workflow.update());
    untyped.getResult(Long.class);

    WorkflowExecutionHistory history =
        testWorkflowRule.getWorkflowClient().fetchHistory(untyped.getExecution().getWorkflowId());
    WorkflowReplayer.replayWorkflowExecution(history, RetainedStreamWorkflowImpl.class);
  }

  @WorkflowInterface
  public interface RandomLongWorkflow {
    @WorkflowMethod
    long run();
  }

  @WorkflowInterface
  public interface ContinueAsNewWorkflow {
    @WorkflowMethod
    long[] run(Long previous);
  }

  @WorkflowInterface
  public interface ParentWorkflow {
    @WorkflowMethod
    long[] run();
  }

  @WorkflowInterface
  public interface RandomIsolation {
    @WorkflowMethod
    String run();
  }

  @WorkflowInterface
  public interface RetainedStreamWorkflow {
    @WorkflowMethod
    long run();

    @QueryMethod
    String query();

    @UpdateMethod
    String update();

    @UpdateValidatorMethod(updateName = "update")
    void validateUpdate();
  }

  public static class RandomStreamWorkflow implements TestWorkflowReturnString {
    @Override
    public String execute() {
      WorkflowRandomStream random = Workflow.getRandomStream("io.temporal.test");
      long first = random.nextLong();
      long recorded = Workflow.sideEffect(long.class, () -> first);
      if (WorkflowUnsafe.isReplaying()) {
        assertEquals(recorded, first);
        replayed = true;
      }

      Workflow.sleep(Duration.ofMillis(1));
      long second = random.nextLong();
      assertNotEquals(first, second);
      return "ok";
    }
  }

  public static class RandomLongWorkflowImpl implements RandomLongWorkflow {
    @Override
    public long run() {
      return Workflow.getRandomStream("io.temporal.test").nextLong();
    }
  }

  public static class ContinueAsNewWorkflowImpl implements ContinueAsNewWorkflow {
    @Override
    public long[] run(Long previous) {
      long current = Workflow.getRandomStream("io.temporal.test").nextLong();
      if (previous == null) {
        Workflow.continueAsNew(current);
      }
      return new long[] {previous, current};
    }
  }

  public static class ParentWorkflowImpl implements ParentWorkflow {
    @Override
    public long[] run() {
      long parent = Workflow.getRandomStream("io.temporal.test").nextLong();
      long[] child = Workflow.newChildWorkflowStub(ContinueAsNewWorkflow.class).run(null);
      return new long[] {parent, child[0], child[1]};
    }
  }

  public static class RandomIsolationWorkflow implements RandomIsolation {
    @Override
    public String run() {
      if (useNamedStream) {
        Workflow.getRandomStream("io.temporal.test").nextLong();
      }
      int delayMillis = Workflow.newRandom().nextInt(100) + 1;
      Workflow.sleep(Duration.ofMillis(delayMillis));
      return "ok";
    }
  }

  public static class RetainedStreamWorkflowImpl implements RetainedStreamWorkflow {
    private final WorkflowRandomStream random = Workflow.getRandomStream("io.temporal.retained");
    private boolean finished;

    @Override
    public long run() {
      random.nextLong();
      Workflow.await(() -> finished);
      return random.nextLong();
    }

    @Override
    public String query() {
      ReadOnlyException error =
          org.junit.Assert.assertThrows(
              ReadOnlyException.class, () -> random.nextBytes(new byte[Long.BYTES]));
      assertEquals("While in read-only function, action attempted: random", error.getMessage());
      return "query rejected";
    }

    @Override
    public String update() {
      finished = true;
      return "update rejected";
    }

    @Override
    public void validateUpdate() {
      ReadOnlyException error =
          org.junit.Assert.assertThrows(ReadOnlyException.class, random::nextLong);
      assertEquals("While in read-only function, action attempted: random", error.getMessage());
    }
  }
}
