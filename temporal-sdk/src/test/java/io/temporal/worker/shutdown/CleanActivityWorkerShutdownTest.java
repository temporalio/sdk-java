package io.temporal.worker.shutdown;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.temporal.activity.ActivityOptions;
import io.temporal.api.common.v1.Payloads;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.common.converter.DefaultDataConverter;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.WorkerOptions;
import io.temporal.worker.tuning.PollerBehavior;
import io.temporal.worker.tuning.PollerBehaviorAutoscaling;
import io.temporal.worker.tuning.PollerBehaviorSimpleMaximum;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.shared.TestActivities.TestActivity1;
import io.temporal.workflow.shared.TestWorkflows.TestWorkflow1;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class CleanActivityWorkerShutdownTest {

  private static final String COMPLETED = "Completed";
  private static final String INTERRUPTED = "Interrupted";
  private CountDownLatch shutdownLatch = new CountDownLatch(1);
  private final CountDownLatch shutdownNowLatch = new CountDownLatch(1);
  private final ActivitiesImpl activitiesImpl = new ActivitiesImpl(shutdownLatch, shutdownNowLatch);

  @Parameterized.Parameters
  public static Collection<PollerBehavior> data() {
    return Arrays.asList(
        new PollerBehavior[] {
          new PollerBehaviorSimpleMaximum(10), new PollerBehaviorAutoscaling(1, 10, 5),
        });
  }

  @Rule public SDKTestWorkflowRule testWorkflowRule;

  public CleanActivityWorkerShutdownTest(PollerBehavior pollerBehaviorAutoscaling) {
    this.testWorkflowRule =
        SDKTestWorkflowRule.newBuilder()
            .setWorkflowTypes(TestWorkflowImpl.class)
            .setWorkerOptions(
                WorkerOptions.newBuilder()
                    .setActivityTaskPollersBehaviour(pollerBehaviorAutoscaling)
                    .build())
            .setActivityImplementations(activitiesImpl)
            .build();
  }

  @Test
  public void testShutdown() throws InterruptedException {
    TestWorkflow1 workflow = testWorkflowRule.newWorkflowStub(TestWorkflow1.class);
    WorkflowExecution execution = WorkflowClient.start(workflow::execute, null);
    shutdownLatch.await();
    testWorkflowRule.getTestEnvironment().shutdown();
    testWorkflowRule.getTestEnvironment().awaitTermination(10, TimeUnit.MINUTES);
    List<HistoryEvent> events =
        testWorkflowRule
            .getExecutionHistory(execution.getWorkflowId())
            .getHistory()
            .getEventsList();
    boolean found = false;
    for (HistoryEvent e : events) {
      if (e.getEventType() == EventType.EVENT_TYPE_ACTIVITY_TASK_COMPLETED) {
        found = true;
        Payloads ar = e.getActivityTaskCompletedEventAttributes().getResult();
        String r =
            DefaultDataConverter.STANDARD_INSTANCE.fromPayloads(
                0, Optional.of(ar), String.class, String.class);
        assertEquals(COMPLETED, r);
      }
    }
    assertTrue("Contains ActivityTaskCompleted", found);
  }

  @Test
  public void testShutdownNow() throws InterruptedException {
    TestWorkflow1 workflow = testWorkflowRule.newWorkflowStub(TestWorkflow1.class);
    WorkflowExecution execution = WorkflowClient.start(workflow::execute, "now");
    shutdownNowLatch.await();
    testWorkflowRule.getTestEnvironment().shutdownNow();
    testWorkflowRule.getTestEnvironment().awaitTermination(10, TimeUnit.MINUTES);
    List<HistoryEvent> events =
        testWorkflowRule
            .getExecutionHistory(execution.getWorkflowId())
            .getHistory()
            .getEventsList();
    boolean found = false;
    for (HistoryEvent e : events) {
      if (e.getEventType() == EventType.EVENT_TYPE_ACTIVITY_TASK_COMPLETED) {
        found = true;
        Payloads ar = e.getActivityTaskCompletedEventAttributes().getResult();
        String r =
            DefaultDataConverter.STANDARD_INSTANCE.fromPayloads(
                0, Optional.of(ar), String.class, String.class);
        assertEquals(INTERRUPTED, r);
      }
    }
    assertTrue("Contains ActivityTaskCompleted", found);
  }

  public static class TestWorkflowImpl implements TestWorkflow1 {

    private final TestActivity1 activities =
        Workflow.newActivityStub(
            TestActivity1.class,
            ActivityOptions.newBuilder()
                .setScheduleToCloseTimeout(Duration.ofSeconds(100))
                .build());

    @Override
    public String execute(String now) {
      return activities.execute(now);
    }
  }

  public static class ActivitiesImpl implements TestActivity1 {

    private final CountDownLatch shutdownLatch;
    private final CountDownLatch shutdownNowLatch;

    public ActivitiesImpl(CountDownLatch shutdownLatch, CountDownLatch shutdownNowLatch) {
      this.shutdownLatch = shutdownLatch;
      this.shutdownNowLatch = shutdownNowLatch;
    }

    @Override
    public String execute(String now) {
      if (now == null) {
        shutdownLatch.countDown();
      } else {
        shutdownNowLatch.countDown();
      }
      try {
        Thread.sleep(3000);
      } catch (InterruptedException e) {
        // We ignore the interrupted exception here to let the activity complete and return the
        // result. Otherwise, the result is not reported:
        // https://github.com/temporalio/sdk-java/issues/731
        return INTERRUPTED;
      }
      return COMPLETED;
    }
  }
}
