package io.temporal.worker;

import static org.junit.Assert.assertTrue;

import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.shared.TestActivities;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;

public class LocalActivityWorkerNotStartedTest {

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkflowTypes(NothingWorkflowImpl.class)
          .setActivityImplementations(new NothingActivityImpl())
          .setWorkerOptions(WorkerOptions.newBuilder().setLocalActivityWorkerOnly(true).build())
          // Don't start the worker
          .setDoNotStart(true)
          .build();

  @Test
  public void canShutDownProperlyWhenNotStarted() {
    // Shut down the (never started) worker
    Instant shutdownTime = Instant.now();
    testWorkflowRule.getTestEnvironment().getWorkerFactory().shutdown();
    testWorkflowRule.getWorker().awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
    Set<Thread> threadSet = Thread.getAllStackTraces().keySet();
    for (Thread thread : threadSet) {
      if (thread.getName().contains("LocalActivitySlotSupplierQueue")) {
        throw new RuntimeException("Thread should be terminated");
      }
    }
    Duration elapsed = Duration.between(shutdownTime, Instant.now());
    // Shutdown should not have taken long
    assertTrue(elapsed.getSeconds() < 2);
  }

  @WorkflowInterface
  public interface NothingWorkflow {
    @WorkflowMethod
    void execute();
  }

  public static class NothingWorkflowImpl implements NothingWorkflow {
    @Override
    public void execute() {
      Workflow.sleep(500);
    }
  }

  public static class NothingActivityImpl implements TestActivities.NoArgsActivity {
    @Override
    public void execute() {}
  }
}
