package io.temporal.workflow;

import static junit.framework.TestCase.assertTrue;

import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.shared.TestActivities.NoArgsActivity;
import org.junit.Test;

public class TestEnvironmentCloseTest {

  @Test
  public void testCloseNotHanging() {
    TestWorkflowEnvironment env = TestWorkflowEnvironment.newInstance();
    Worker worker = env.newWorker("WW");
    worker.registerWorkflowImplementationTypes(WW.class);
    worker.registerActivitiesImplementations(new AA());
    long start = System.currentTimeMillis();
    env.close();
    long elapsed = System.currentTimeMillis() - start;
    assertTrue(elapsed < 5000);
  }

  @WorkflowInterface
  public interface W {
    @WorkflowMethod
    void foo();

    @SignalMethod
    void signal();
  }

  public static class WW implements W {

    @Override
    public void foo() {}

    @Override
    public void signal() {}
  }

  public static class AA implements NoArgsActivity {

    @Override
    public void execute() {}
  }
}
