package io.temporal.testing;

import static org.junit.Assert.assertEquals;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.worker.Worker;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;

public class TestWorkflowEnvironmentSleepTest {

  @WorkflowInterface
  public interface ExampleWorkflow {
    @WorkflowMethod
    String execute();

    @SignalMethod
    void updateState(String state);

    @QueryMethod
    String queryState();
  }

  public static class ExampleWorkflowImpl implements ExampleWorkflow {
    Logger logger = Workflow.getLogger(ExampleWorkflowImpl.class);
    private String state;
    private boolean completed;
    private boolean terminated;
    private boolean error;

    public ExampleWorkflowImpl() {
      this.state = "INITIALIZED";
      this.completed = false;
      this.terminated = false;
      this.error = false;
    }

    @Override
    public String execute() {
      state = "WAITING_FOR_SIGNAL";
      while (state.equals("WAITING_FOR_SIGNAL")) {
        Workflow.await(Duration.ofMinutes(20L), () -> (completed || terminated || error));
        if (terminated) {
          state = "TERMINATED";
          return state;
        } else if (error) {
          state = "ERROR";
          return state;
        } else if (completed) {
          state = "PRELIM_COMPLETED";
        }
      }
      state = "WAITING_FOR_MORE_SIGNAL_OR_TIMER_TO_END";
      System.out.println("Before SLEEP ----------");
      Workflow.sleep(Duration.ofMinutes(20));
      //    Workflow.await(Duration.ofMinutes(20L), () -> (error));
      if (error) {
        state = "ERROR";
        return state;
      } else {
        state = "COMPLETED";
      }
      return state;
    }

    @Override
    public void updateState(String newState) {
      switch (newState) {
        case "PRELIM_COMPLETED":
          completed = true;
          break;
        case "TERMINATED":
          terminated = true;
          break;
        case "ERROR":
          error = true;
          break;
        default:
          logger.error("Unknown state: {}", newState);
          break;
      }
    }

    @Override
    public String queryState() {
      return state;
    }
  }

  private TestWorkflowEnvironment testEnv;
  private Worker worker;
  private WorkflowClient client;
  private ExampleWorkflow workflow;
  private static final String WORKFLOW_TASK_QUEUE = "EXAMPLE";

  @Before
  public void setUp() {
    testEnv = TestWorkflowEnvironment.newInstance();
    worker = testEnv.newWorker(WORKFLOW_TASK_QUEUE);
    client = testEnv.getWorkflowClient();
    worker.registerWorkflowImplementationTypes(ExampleWorkflowImpl.class);
    workflow =
        client.newWorkflowStub(
            ExampleWorkflow.class,
            WorkflowOptions.newBuilder()
                .setWorkflowExecutionTimeout(Duration.ofDays(2))
                .setTaskQueue(WORKFLOW_TASK_QUEUE)
                .build());
    testEnv.start();
  }

  @Test
  public void testTerminatedUpdateStatus() {
    WorkflowClient.start(workflow::execute);
    workflow.updateState("TERMINATED");
    assertEquals("TERMINATED", workflow.queryState());
  }

  @Test(timeout = 2000)
  public void testCompletedThenTimerCompletes() throws InterruptedException {
    WorkflowClient.start(workflow::execute);
    workflow.updateState("PRELIM_COMPLETED");
    //    while (!workflow.queryState().equals("WAITING_FOR_MORE_SIGNAL_OR_TIMER_TO_END")) {
    //      Thread.sleep(2000);
    //    }
    // this line hangs without the @Timeout rule
    testEnv.sleep(Duration.ofMinutes(25L));
    assertEquals("COMPLETED", workflow.queryState());
  }

  @After
  public void tearDown() {
    testEnv.close();
  }
}
