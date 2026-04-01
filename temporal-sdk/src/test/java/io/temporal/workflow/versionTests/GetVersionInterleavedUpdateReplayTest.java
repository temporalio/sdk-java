package io.temporal.workflow.versionTests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.common.RetryOptions;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.internal.common.SdkFlag;
import io.temporal.internal.history.VersionMarkerUtils;
import io.temporal.internal.statemachines.WorkflowStateMachines;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowHistoryLoader;
import io.temporal.testing.WorkflowReplayer;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.worker.Worker;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.Test;
import org.slf4j.Logger;

/**
 * Mirrors app/src/main/kotlin/io/temporal/samples/update_nde/GreetingWorkflow.kt from
 * gauravthadani/samples-kotlin and captures histories that exercise interleaved updates around
 * getVersion.
 */
public class GetVersionInterleavedUpdateReplayTest {
  private static final String HISTORY_RESOURCE =
      "testGetVersionInterleavedUpdateReplayHistory.json";
  private static final String WAIT_FOR_MARKER_HISTORY_RESOURCE =
      "testGetVersionInterleavedUpdateReplayWaitForMarkerHistory.json";
  public static final String TASK_QUEUE = "get-version-interleaved-update-replay";
  private static final String EXPECTED_FIRST_CHANGE_ID = "ChangeId1";
  private static final String EXPECTED_SECOND_CHANGE_ID = "ChangeId2";

  /**
   * This recorded history predates {@link SdkFlag#SKIP_YIELD_ON_VERSION}, so it no longer matches
   * the histories produced by the current branch.
   *
   * <p>Keep this fixture as a reproducer that old histories without the newer flags still preserve
   * the old failure. Making this exact history replay again would require changing replay behavior
   * for histories that did not record the newer flags, which may break other existing replays. The
   * fix is to put the state-machine behavior change behind an SDK flag {@link
   * SdkFlag#VERSION_WAIT_FOR_MARKER}, and to make sure new workflows run with {@link
   * SdkFlag#SKIP_YIELD_ON_VERSION} by default to avoid interleaved histories.
   */
  @Test
  public void testReplayHistoryWithoutFlagStillFails() {
    RuntimeException replayFailure =
        assertThrows(
            RuntimeException.class,
            () ->
                WorkflowReplayer.replayWorkflowExecutionFromResource(
                    HISTORY_RESOURCE, GreetingWorkflowImpl.class));

    assertTrue(
        replayFailure
            .getMessage()
            .contains("[TMPRL1100] getVersion call before the existing version marker event"));
  }

  @Test
  public void testReproducedHistoryReplays() throws Exception {
    WorkflowExecutionHistory history = captureReplayableHistory();

    assertEquals(
        Arrays.asList(EXPECTED_FIRST_CHANGE_ID, EXPECTED_SECOND_CHANGE_ID),
        extractVersionChangeIds(history.getEvents()));
    assertTrue(
        "The reproduced history must advertise SKIP_YIELD_ON_VERSION.",
        hasSdkFlag(history, SdkFlag.SKIP_YIELD_ON_VERSION));
    assertTrue(
        "The reproduced history must include at least one completed update.",
        hasEvent(history.getEvents(), EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_COMPLETED));

    WorkflowReplayer.replayWorkflowExecution(history, GreetingWorkflowImpl.class);
  }

  @Test
  public void testReplayHistoryWithWaitForMarkerFlagReplaysWithoutDefaultEnable() throws Exception {
    WorkflowExecutionHistory history =
        WorkflowHistoryLoader.readHistoryFromResource(WAIT_FOR_MARKER_HISTORY_RESOURCE);
    assertTrue(
        "The recorded history must advertise VERSION_WAIT_FOR_MARKER.",
        hasSdkFlag(history, SdkFlag.VERSION_WAIT_FOR_MARKER));

    List<SdkFlag> savedInitialFlags = WorkflowStateMachines.initialFlags;
    List<SdkFlag> replayFlags = new ArrayList<>(savedInitialFlags);
    replayFlags.remove(SdkFlag.VERSION_WAIT_FOR_MARKER);
    WorkflowStateMachines.initialFlags = Collections.unmodifiableList(replayFlags);
    try {
      WorkflowReplayer.replayWorkflowExecution(history, GreetingWorkflowImpl.class);
    } finally {
      WorkflowStateMachines.initialFlags = savedInitialFlags;
    }
  }

  public static WorkflowExecutionHistory captureReplayableHistory() {
    List<SdkFlag> savedInitialFlags = WorkflowStateMachines.initialFlags;
    List<SdkFlag> replayableFlags = new ArrayList<>(savedInitialFlags);
    if (!replayableFlags.contains(SdkFlag.SKIP_YIELD_ON_VERSION)) {
      replayableFlags.add(SdkFlag.SKIP_YIELD_ON_VERSION);
    }
    WorkflowStateMachines.initialFlags = Collections.unmodifiableList(replayableFlags);
    try (TestWorkflowEnvironment testEnvironment = TestWorkflowEnvironment.newInstance()) {
      Worker worker = testEnvironment.newWorker(TASK_QUEUE);
      worker.registerWorkflowImplementationTypes(GreetingWorkflowImpl.class);
      testEnvironment.start();

      WorkflowClient client = testEnvironment.getWorkflowClient();
      GreetingWorkflow workflow =
          client.newWorkflowStub(
              GreetingWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setTaskQueue(TASK_QUEUE)
                  .setWorkflowId(UUID.randomUUID().toString())
                  .build());
      WorkflowExecution execution = WorkflowClient.start(workflow::greeting, "Temporal");

      WorkflowStub workflowStub = WorkflowStub.fromTyped(workflow);
      SDKTestWorkflowRule.waitForOKQuery(workflowStub);
      assertEquals("works", workflow.notify("update"));

      return client.fetchHistory(execution.getWorkflowId(), execution.getRunId());
    } finally {
      WorkflowStateMachines.initialFlags = savedInitialFlags;
    }
  }

  public static List<String> extractVersionChangeIds(List<HistoryEvent> events) {
    List<String> changeIds = new ArrayList<>();
    for (HistoryEvent event : events) {
      String changeId = VersionMarkerUtils.tryGetChangeIdFromVersionMarkerEvent(event);
      if (changeId != null) {
        changeIds.add(changeId);
      }
    }
    return changeIds;
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

  private static boolean hasEvent(List<HistoryEvent> events, EventType eventType) {
    for (HistoryEvent event : events) {
      if (event.getEventType() == eventType) {
        return true;
      }
    }
    return false;
  }

  public static class Request {
    private final String name;
    private final OffsetDateTime date;

    public Request(String name, OffsetDateTime date) {
      this.name = name;
      this.date = date;
    }

    public String getName() {
      return name;
    }

    public OffsetDateTime getDate() {
      return date;
    }
  }

  @WorkflowInterface
  public interface GreetingWorkflow {
    @WorkflowMethod
    String greeting(String name);

    @UpdateMethod
    String notify(String name);
  }

  public static class GreetingWorkflowImpl implements GreetingWorkflow {
    private final Logger logger = Workflow.getLogger(GreetingWorkflow.class);

    public GreetingWorkflowImpl() {
      logger.info("Workflow is initialized");
    }

    private GreetingActivities getActivities() {
      return Workflow.newActivityStub(
          GreetingActivities.class,
          ActivityOptions.newBuilder()
              .setStartToCloseTimeout(Duration.ofSeconds(30))
              .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
              .build());
    }

    @Override
    public String greeting(String name) {
      logger.info("Workflow started");

      Workflow.getVersion("ChangeId1", 0, 1);
      Workflow.getVersion("ChangeId2", 0, 1);

      Workflow.await(() -> false);
      return getActivities().composeGreeting("hello", name);
    }

    @Override
    public String notify(String name) {
      logger.info("Signal received: {}", name);
      Workflow.sideEffect(UUID.class, UUID::randomUUID);
      return "works";
    }
  }

  public static class GreetingActivitiesImpl implements GreetingActivities {
    @Override
    public String composeGreeting(String greeting, String name) {
      System.out.println("Greeting started: " + greeting);
      return greeting + ", " + name + "!";
    }
  }

  @ActivityInterface
  public interface GreetingActivities {
    @ActivityMethod(name = "greet")
    String composeGreeting(String greeting, String name);
  }
}
