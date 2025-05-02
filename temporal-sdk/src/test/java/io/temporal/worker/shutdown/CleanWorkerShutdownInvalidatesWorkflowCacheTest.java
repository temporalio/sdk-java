package io.temporal.worker.shutdown;

import io.temporal.client.WorkflowClient;
import io.temporal.internal.Signal;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerFactory;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestWorkflows;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Tests that {@link WorkerFactory} invalidates the workflow cache and destroys the workflow threads
 * during shutdown.
 */
public class CleanWorkerShutdownInvalidatesWorkflowCacheTest {
  private static final Signal STARTED = new Signal();
  private static final Signal WORKFLOW_THREAD_DESTROYED = new Signal();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder().setWorkflowTypes(TestWorkflowImpl.class).build();

  @Before
  public void setUp() throws Exception {
    STARTED.clearSignal();
    WORKFLOW_THREAD_DESTROYED.clearSignal();
  }

  @Test
  public void testShutdownHeartBeatingActivity() throws InterruptedException {
    TestWorkflows.NoArgsWorkflow workflow =
        testWorkflowRule.newWorkflowStub(TestWorkflows.NoArgsWorkflow.class);
    WorkflowClient.start(workflow::execute);
    STARTED.waitForSignal();
    testWorkflowRule.getTestEnvironment().shutdown();
    WORKFLOW_THREAD_DESTROYED.waitForSignal();
  }

  public static class TestWorkflowImpl implements TestWorkflows.NoArgsWorkflow {

    private final boolean forWait = false;

    @Override
    public void execute() {
      try {
        STARTED.signal();
        Workflow.await(() -> forWait);
      } catch (Error e) {
        // never ever catch Errors in production code
        if ("DestroyWorkflowThreadError".equals(e.getClass().getSimpleName())) {
          WORKFLOW_THREAD_DESTROYED.signal();
        }
        throw e;
      }
    }
  }
}
