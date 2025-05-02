package io.temporal.workflow;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class SideEffectRaceConditionTest {

  private static final String TASK_QUEUE = "test-workflow";

  private TestWorkflowEnvironment testEnvironment;
  private Worker worker;

  @Before
  public void setUp() {
    TestEnvironmentOptions options = TestEnvironmentOptions.newBuilder().build();
    testEnvironment = TestWorkflowEnvironment.newInstance(options);
    worker = testEnvironment.newWorker(TASK_QUEUE);
  }

  @After
  public void tearDown() {
    testEnvironment.close();
  }

  @WorkflowInterface
  public interface TestWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class TestSideEffectBenchWorkflowImpl implements TestWorkflow {

    @Override
    public void execute() {
      for (int i = 0; i < 100; i++) {
        Workflow.sideEffect(long.class, () -> new Random().nextLong());
        Workflow.sleep(Duration.ofMillis(100));
        Workflow.sideEffect(long.class, () -> new Random().nextLong());
      }
    }
  }

  @Test
  public void testSideEffectBench() throws ExecutionException, InterruptedException {
    worker.registerWorkflowImplementationTypes(TestSideEffectBenchWorkflowImpl.class);
    testEnvironment.start();
    List<CompletableFuture<Void>> results = new ArrayList<>();
    int count = 100;
    for (int i = 0; i < count; i++) {
      TestWorkflow workflowStub =
          testEnvironment
              .getWorkflowClient()
              .newWorkflowStub(
                  TestWorkflow.class,
                  WorkflowOptions.newBuilder().setTaskQueue(TASK_QUEUE).build());
      CompletableFuture<Void> result = WorkflowClient.execute(workflowStub::execute);
      results.add(result);
    }
    for (int i = 0; i < count; i++) {
      results.get(i).get();
    }
  }
}
