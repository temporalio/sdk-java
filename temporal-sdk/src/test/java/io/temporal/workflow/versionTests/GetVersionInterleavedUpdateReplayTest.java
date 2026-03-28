package io.temporal.workflow.versionTests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowStub;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.internal.Issue;
import io.temporal.internal.history.VersionMarkerUtils;
import io.temporal.testUtils.Eventually;
import io.temporal.testing.internal.SDKTestOptions;
import io.temporal.testing.internal.SDKTestWorkflowRule;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;

@Issue("https://github.com/temporalio/sdk-java/issues/2796")
public class GetVersionInterleavedUpdateReplayTest extends BaseVersionTest {

  private static final String CHANGE_ID_1 = "ChangeId1";
  private static final String CHANGE_ID_2 = "ChangeId2";
  private static final String HISTORY_FILE_NAME = "testGetVersionInterleavedUpdateReplay";
  private static final String NOTIFY_UPDATE_NAME = "notify";

  @Rule
  public SDKTestWorkflowRule testWorkflowRule =
      SDKTestWorkflowRule.newBuilder()
          .setDoNotStart(true)
          .setWorkflowTypes(getDefaultWorkflowImplementationOptions(), GreetingWorkflowImpl.class)
          .setTestTimeoutSeconds(90)
          .build();

  public GetVersionInterleavedUpdateReplayTest(
      boolean setVersioningFlag, boolean upsertVersioningSA) {
    super(setVersioningFlag, upsertVersioningSA);
  }

  @Test(timeout = 90000)
  public void testReplaySucceedsWhenUpdateCompletedInterleavesBetweenVersionMarkers()
      throws Exception {
    assumeTrue(
        "Requires external service to reproduce live replay of an in-flight workflow task.",
        SDKTestWorkflowRule.useExternalService);

    WorkflowClient workflowClient = testWorkflowRule.getWorkflowClient();
    GreetingWorkflow workflow =
        workflowClient.newWorkflowStub(
            GreetingWorkflow.class,
            SDKTestOptions.newWorkflowOptionsWithTimeouts(testWorkflowRule.getTaskQueue())
                .toBuilder()
                .setWorkflowId("get-version-interleaved-update-" + UUID.randomUUID())
                .build());

    WorkflowExecution execution = WorkflowClient.start(workflow::execute, "Temporal");
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch startUpdate = new CountDownLatch(1);
    Future<?> initialUpdate = null;

    try {
      initialUpdate =
          executor.submit(
              () -> {
                startUpdate.await();
                WorkflowStub stub =
                    workflowClient.newUntypedWorkflowStub(execution.getWorkflowId());
                try {
                  stub.update(NOTIFY_UPDATE_NAME, String.class, "update-0");
                } catch (Exception e) {
                  // The replay failure is asserted from workflow history later.
                }
                return null;
              });

      startUpdate.countDown();
      testWorkflowRule.getTestEnvironment().start();

      WorkflowExecutionHistory history = waitForInterleavedHistory(execution.getWorkflowId());
      assertTrue(
          "History should contain UpdateCompleted between ChangeId1 and ChangeId2 markers.\n"
              + summarizeHistory(history),
          hasInterleavedUpdateCompletedBetweenVersionMarkers(history));

      testWorkflowRule.waitForTheEndOfWFT(execution.getWorkflowId());
      testWorkflowRule.invalidateWorkflowCache();

      Future<String> replayTrigger =
          executor.submit(
              () -> {
                WorkflowStub stub =
                    workflowClient.newUntypedWorkflowStub(execution.getWorkflowId());
                return stub.update("ping", String.class);
              });

      assertEquals("pong", replayTrigger.get(20, TimeUnit.SECONDS));
      testWorkflowRule.waitForTheEndOfWFT(execution.getWorkflowId());
      assertNoWorkflowTaskFailure(execution.getWorkflowId());
      testWorkflowRule.regenerateHistoryForReplay(execution.getWorkflowId(), HISTORY_FILE_NAME);
    } finally {
      startUpdate.countDown();
      WorkflowStub workflowStub = workflowClient.newUntypedWorkflowStub(execution.getWorkflowId());
      try {
        workflowStub.terminate("test cleanup");
      } catch (Exception e) {
        // The workflow may already be closed or unreachable during shutdown.
      }
      if (initialUpdate != null) {
        initialUpdate.cancel(true);
      }
      executor.shutdownNow();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  private void assertNoWorkflowTaskFailure(String workflowId) {
    WorkflowExecutionHistory history =
        testWorkflowRule.getWorkflowClient().fetchHistory(workflowId);
    for (HistoryEvent event : history.getEvents()) {
      assertFalse(
          "Unexpected replay-triggered workflow task failure:\n" + summarizeHistory(history),
          event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_TASK_FAILED);
    }
  }

  private WorkflowExecutionHistory waitForInterleavedHistory(String workflowId) {
    return Eventually.assertEventually(
        Duration.ofSeconds(20),
        () -> {
          WorkflowExecutionHistory history =
              testWorkflowRule.getWorkflowClient().fetchHistory(workflowId);
          if (!hasInterleavedUpdateCompletedBetweenVersionMarkers(history)) {
            throw new AssertionError(
                "Expected UpdateCompleted to be interleaved between version markers but saw:\n"
                    + summarizeHistory(history));
          }
          return history;
        });
  }

  private static boolean hasInterleavedUpdateCompletedBetweenVersionMarkers(
      WorkflowExecutionHistory history) {
    boolean sawFirstMarker = false;
    boolean sawUpdateCompleted = false;

    for (HistoryEvent event : history.getEvents()) {
      if (event.getEventType() == EventType.EVENT_TYPE_MARKER_RECORDED
          && VersionMarkerUtils.hasVersionMarkerStructure(event)) {
        String changeId = VersionMarkerUtils.getChangeId(event.getMarkerRecordedEventAttributes());
        if (CHANGE_ID_1.equals(changeId)) {
          sawFirstMarker = true;
          continue;
        }
        if (CHANGE_ID_2.equals(changeId) && sawFirstMarker && sawUpdateCompleted) {
          return true;
        }
      }

      if (event.getEventType() == EventType.EVENT_TYPE_WORKFLOW_EXECUTION_UPDATE_COMPLETED
          && sawFirstMarker) {
        sawUpdateCompleted = true;
      }
    }

    return false;
  }

  private static String summarizeHistory(WorkflowExecutionHistory history) {
    StringBuilder result = new StringBuilder();
    for (HistoryEvent event : history.getEvents()) {
      result.append(event.getEventId()).append(':').append(event.getEventType());
      if (event.getEventType() == EventType.EVENT_TYPE_MARKER_RECORDED
          && VersionMarkerUtils.hasVersionMarkerStructure(event)) {
        result
            .append('(')
            .append(VersionMarkerUtils.getChangeId(event.getMarkerRecordedEventAttributes()))
            .append(')');
      }
      result.append('\n');
    }
    return result.toString();
  }

  @WorkflowInterface
  public interface GreetingWorkflow {
    @WorkflowMethod
    void execute(String name);

    @UpdateMethod(name = NOTIFY_UPDATE_NAME)
    String notifyUpdate(String name);

    @UpdateMethod
    String ping();
  }

  public static class GreetingWorkflowImpl implements GreetingWorkflow {
    private boolean updateCompleted;

    @Override
    public void execute(String name) {
      Workflow.getVersion(CHANGE_ID_1, Workflow.DEFAULT_VERSION, 1);
      Workflow.await(() -> updateCompleted);
      Workflow.getVersion(CHANGE_ID_2, Workflow.DEFAULT_VERSION, 1);
      Workflow.await(() -> false);
    }

    @Override
    public String notifyUpdate(String name) {
      Workflow.sideEffect(UUID.class, UUID::randomUUID);
      updateCompleted = true;
      return "works";
    }

    @Override
    public String ping() {
      return "pong";
    }
  }
}
