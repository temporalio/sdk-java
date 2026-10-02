package io.temporal.workflow.activityTests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.internal.common.SdkFlag;
import io.temporal.internal.statemachines.WorkflowStateMachines;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerFactoryOptions;
import io.temporal.workflow.Async;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;

public class AsyncActivityThreadReleaseTest {
  private static final int ACTIVITY_COUNT = 10;

  private final WaitingActivityImpl activity = new WaitingActivityImpl();

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setWorkerFactoryOptions(
              WorkerFactoryOptions.newBuilder().setMaxWorkflowThreadCount(4).build())
          .setWorkflowTypes(FanOutWorkflowImpl.class)
          .setActivityImplementations(activity)
          .setTestTimeoutSeconds(30)
          .build();

  @Test
  public void asyncStubThreadsExitWhileActivitiesRemainOpen() throws InterruptedException {
    List<SdkFlag> savedInitialFlags = WorkflowStateMachines.initialFlags;
    List<SdkFlag> flags = new ArrayList<>(savedInitialFlags);
    flags.add(SdkFlag.SCHEDULE_ASYNC_STUB_OPERATIONS);
    WorkflowStateMachines.initialFlags = Collections.unmodifiableList(flags);
    try {
      FanOutWorkflow workflow =
          testWorkflowRule.newWorkflowStubTimeoutOptions(FanOutWorkflow.class);
      WorkflowExecution execution = WorkflowClient.start(workflow::execute);

      assertTrue(activity.firstStarted.await(10, TimeUnit.SECONDS));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      long scheduled;
      do {
        WorkflowExecutionHistory history =
            testWorkflowRule
                .getWorkflowClient()
                .fetchHistory(execution.getWorkflowId(), execution.getRunId());
        scheduled =
            history.getEvents().stream()
                .filter(
                    event -> event.getEventType() == EventType.EVENT_TYPE_ACTIVITY_TASK_SCHEDULED)
                .count();
        if (scheduled == ACTIVITY_COUNT) {
          break;
        }
        Thread.sleep(20);
      } while (System.nanoTime() < deadline);
      assertEquals(ACTIVITY_COUNT, scheduled);

      activity.release.countDown();
      assertEquals(45, WorkflowStub.fromTyped(workflow).getResult(Integer.class).intValue());
    } finally {
      activity.release.countDown();
      WorkflowStateMachines.initialFlags = savedInitialFlags;
    }
  }

  @WorkflowInterface
  public interface FanOutWorkflow {
    @WorkflowMethod
    int execute();
  }

  @ActivityInterface
  public interface WaitingActivity {
    int call(int value);
  }

  public static class FanOutWorkflowImpl implements FanOutWorkflow {
    private final WaitingActivity activity =
        Workflow.newActivityStub(
            WaitingActivity.class,
            ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30)).build());

    @Override
    public int execute() {
      List<Promise<Integer>> results = new ArrayList<>();
      for (int i = 0; i < ACTIVITY_COUNT; i++) {
        results.add(Async.function(activity::call, i));
        // Spread invocations across workflow tasks so the test measures retained threads.
        Workflow.sleep(Duration.ofMillis(10));
      }
      int sum = 0;
      for (Promise<Integer> result : results) {
        sum += result.get();
      }
      return sum;
    }
  }

  public static class WaitingActivityImpl implements WaitingActivity {
    private final CountDownLatch firstStarted = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    @Override
    public int call(int value) {
      firstStarted.countDown();
      try {
        if (!release.await(20, TimeUnit.SECONDS)) {
          throw new IllegalStateException("Activity was not released.");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException(e);
      }
      return value;
    }
  }
}
