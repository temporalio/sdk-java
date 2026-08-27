package io.temporal.worker;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Reproducer: externally terminated workflows are retained by the sticky cache until LRU capacity
 * evicts them. The server dispatches no workflow task on termination, so the worker never learns
 * the run is closed; each dead run keeps its full cached state and its workflow thread.
 *
 * <p>Observed (sdk-java 1.34, 300 terminated runs holding a 1 MB char[] each, in-process test
 * server): heap 18 -> 510 MB, 164 workflow threads pinned.
 */
public class TerminatedWorkflowStickyCacheReproducer {

  @WorkflowInterface
  public interface LeakWorkflow {
    @WorkflowMethod
    String run(int ballastKb);
  }

  public static class LeakWorkflowImpl implements LeakWorkflow {
    private char[] ballast;

    @Override
    public String run(int ballastKb) {
      ballast = new char[ballastKb * 1024];
      Workflow.await(() -> ballast.length == 0);
      return "done";
    }
  }

  private static long heapMb() {
    System.gc();
    try {
      Thread.sleep(200);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    Runtime rt = Runtime.getRuntime();
    return (rt.totalMemory() - rt.freeMemory()) / 1048576;
  }

  private static long workflowThreads() {
    return Thread.getAllStackTraces().keySet().stream()
        .filter(t -> t.getName().contains("workflow-method"))
        .count();
  }

  @Test
  public void terminatedWorkflowsStayInCacheUntilLruCapacity() throws Exception {
    int count = 300;
    int ballastKb = 1024;

    TestWorkflowEnvironment env =
        TestWorkflowEnvironment.newInstance(TestEnvironmentOptions.newBuilder().build());
    env.newWorker("leak-lab").registerWorkflowImplementationTypes(LeakWorkflowImpl.class);
    env.start();
    WorkflowClient client = env.getWorkflowClient();

    System.out.printf("baseline: heap %d MB, %d workflow threads%n", heapMb(), workflowThreads());

    List<WorkflowStub> stubs = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      LeakWorkflow stub =
          client.newWorkflowStub(
              LeakWorkflow.class, WorkflowOptions.newBuilder().setTaskQueue("leak-lab").build());
      WorkflowClient.start(stub::run, ballastKb);
      stubs.add(WorkflowStub.fromTyped(stub));
    }
    Thread.sleep(10000);
    for (WorkflowStub stub : stubs) {
      stub.terminate("reproducer");
    }
    Thread.sleep(2000);

    System.out.printf(
        "after %d terminated (%dKB ballast): heap %d MB, %d workflow threads%n",
        count, ballastKb, heapMb(), workflowThreads());

    env.close();
  }
}
