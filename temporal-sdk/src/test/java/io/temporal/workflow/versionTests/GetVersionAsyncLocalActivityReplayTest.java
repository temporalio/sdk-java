package io.temporal.workflow.versionTests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.LocalActivityOptions;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.internal.common.SdkFlag;
import io.temporal.internal.history.VersionMarkerUtils;
import io.temporal.internal.statemachines.WorkflowStateMachines;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerOptions;
import io.temporal.workflow.Async;
import io.temporal.workflow.CompletablePromise;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import io.temporal.workflow.unsafe.WorkflowUnsafe;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class GetVersionAsyncLocalActivityReplayTest {
  private static final String TASK_QUEUE = "get-version-async-local-activity-replay";
  private static final String CHANGE_ID = "async-local-activity-change";

  private static boolean hasReplayed;

  private List<SdkFlag> savedInitialFlags;

  @Before
  public void setUp() {
    hasReplayed = false;
    savedInitialFlags = WorkflowStateMachines.initialFlags;
    WorkflowStateMachines.initialFlags =
        Collections.singletonList(SdkFlag.SKIP_YIELD_ON_DEFAULT_VERSION);
  }

  @After
  public void tearDown() {
    WorkflowStateMachines.initialFlags = savedInitialFlags;
  }

  @Test
  public void testGetVersionReplayWithAsyncLocalActivitiesKeepsExpectCBoundToC() throws Exception {
    WorkflowExecutionHistory history = executeWorkflowAndCaptureHistory();

    assertTrue(hasReplayed);
    assertTrue(hasVersionMarker(history, CHANGE_ID));
    assertFalse(hasSdkFlag(history, SdkFlag.SKIP_YIELD_ON_VERSION));

    WorkflowReplayer.replayWorkflowExecution(history, AsyncLocalActivityWorkflowImpl.class);
  }

  private WorkflowExecutionHistory executeWorkflowAndCaptureHistory() {
    try (TestWorkflowEnvironment testEnvironment = TestWorkflowEnvironment.newInstance()) {
      Worker worker =
          testEnvironment.newWorker(
              TASK_QUEUE,
              WorkerOptions.newBuilder()
                  .setStickyQueueScheduleToStartTimeout(Duration.ZERO)
                  .build());
      worker.registerWorkflowImplementationTypes(AsyncLocalActivityWorkflowImpl.class);
      worker.registerActivitiesImplementations(new EchoActivitiesImpl());
      testEnvironment.start();

      WorkflowClient client = testEnvironment.getWorkflowClient();
      ReplayTestWorkflow workflow =
          client.newWorkflowStub(
              ReplayTestWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setTaskQueue(TASK_QUEUE)
                  .setWorkflowRunTimeout(Duration.ofMinutes(1))
                  .setWorkflowTaskTimeout(Duration.ofSeconds(5))
                  .build());

      WorkflowExecution execution = WorkflowClient.start(workflow::execute);
      assertEquals("ABC", WorkflowStub.fromTyped(workflow).getResult(String.class));

      return client.fetchHistory(execution.getWorkflowId(), execution.getRunId());
    }
  }

  private static boolean hasSdkFlag(WorkflowExecutionHistory history, SdkFlag flag) {
    for (HistoryEvent event : history.getEvents()) {
      if (event.getEventType() != EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED) {
        continue;
      }
      if (!event.getWorkflowTaskCompletedEventAttributes().hasSdkMetadata()) {
        continue;
      }
      if (event
          .getWorkflowTaskCompletedEventAttributes()
          .getSdkMetadata()
          .getLangUsedFlagsList()
          .contains(flag.getValue())) {
        return true;
      }
    }
    return false;
  }

  private static boolean hasVersionMarker(WorkflowExecutionHistory history, String changeId) {
    for (HistoryEvent event : history.getEvents()) {
      if (changeId.equals(VersionMarkerUtils.tryGetChangeIdFromVersionMarkerEvent(event))) {
        return true;
      }
    }
    return false;
  }

  @WorkflowInterface
  public interface ReplayTestWorkflow {
    @WorkflowMethod
    String execute();
  }

  @ActivityInterface
  public interface EchoActivities {
    @ActivityMethod
    String echo(String value);
  }

  public static class EchoActivitiesImpl implements EchoActivities {
    @Override
    public String echo(String value) {
      return value.toUpperCase(Locale.ROOT);
    }
  }

  public static class AsyncLocalActivityWorkflowImpl implements ReplayTestWorkflow {
    private final EchoActivities echoActivities =
        Workflow.newLocalActivityStub(
            EchoActivities.class,
            LocalActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(5))
                .build());

    @Override
    public String execute() {
      CompletablePromise<String> expectA = Workflow.newPromise();
      CompletablePromise<String> expectB = Workflow.newPromise();
      Promise<Void> asyncBranch =
          Async.procedure(
              () -> {
                expectA.complete(echoActivities.echo("a"));
                expectB.complete(echoActivities.echo("b"));
              });

      int version = Workflow.getVersion(CHANGE_ID, Workflow.DEFAULT_VERSION, 1);
      assertEquals(1, version);

      String expectC = echoActivities.echo("c");
      asyncBranch.get();

      assertEquals("A", expectA.get());
      assertEquals("B", expectB.get());
      assertEquals("C", expectC);

      if (WorkflowUnsafe.isReplaying()) {
        hasReplayed = true;
      }

      Workflow.sleep(Duration.ofSeconds(1));
      return expectA.get() + expectB.get() + expectC;
    }
  }
}
