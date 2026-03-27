package io.temporal.internal.replay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.uber.m3.tally.NoopScope;
import io.temporal.api.command.v1.Command;
import io.temporal.api.enums.v1.CommandType;
import io.temporal.api.history.v1.HistoryEvent;
import io.temporal.api.query.v1.WorkflowQuery;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.internal.history.VersionMarkerUtils;
import io.temporal.internal.worker.QueryReplayHelper;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.testing.WorkflowHistoryLoader;
import io.temporal.worker.Worker;
import io.temporal.workflow.versionTests.GetVersionInterleavedUpdateReplayTest.GreetingWorkflowImpl;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public class GetVersionInterleavedUpdateReplayTaskHandlerTest {
  private static final String HISTORY_RESOURCE =
      "testGetVersionInterleavedUpdateReplayHistory.json";
  private static final String EXPECTED_NON_DETERMINISTIC_MESSAGE =
      "[TMPRL1100] getVersion call before the existing version marker event. The most probable cause is retroactive addition of a getVersion call with an existing 'changeId'";
  private static final String EXPECTED_NON_DETERMINISTIC_FRAGMENT =
      "io.temporal.worker.NonDeterministicException: " + EXPECTED_NON_DETERMINISTIC_MESSAGE;
  private static final String EXPECTED_FIRST_CHANGE_ID = "ChangeId1";
  private static final String EXPECTED_SECOND_CHANGE_ID = "ChangeId2";
  private static final String TEST_TASK_QUEUE = "get-version-interleaved-update-replay";

  @Test
  public void testReplayQueuesSecondVersionMarkerBeforeUpdateCompletionCommands() throws Exception {
    WorkflowExecutionHistory history =
        WorkflowHistoryLoader.readHistoryFromResource(HISTORY_RESOURCE);
    assertEquals(
        Arrays.asList(EXPECTED_FIRST_CHANGE_ID, EXPECTED_SECOND_CHANGE_ID),
        extractVersionChangeIds(history.getEvents()));

    TestWorkflowEnvironment testEnvironment = TestWorkflowEnvironment.newInstance();
    ReplayWorkflowRunTaskHandler runTaskHandler = null;
    try {
      Worker worker = testEnvironment.newWorker(TEST_TASK_QUEUE);
      worker.registerWorkflowImplementationTypes(GreetingWorkflowImpl.class);

      ReplayWorkflowTaskHandler replayTaskHandler = getNonStickyReplayTaskHandler(worker);
      PollWorkflowTaskQueueResponse.Builder replayTask = newReplayTask(history);
      runTaskHandler = createStatefulHandler(replayTaskHandler, replayTask);

      WorkflowServiceStubs service =
          getField(replayTaskHandler, "service", WorkflowServiceStubs.class);
      String namespace = getField(replayTaskHandler, "namespace", String.class);
      ServiceWorkflowHistoryIterator historyIterator =
          new ServiceWorkflowHistoryIterator(service, namespace, replayTask, new NoopScope());

      ReplayWorkflowRunTaskHandler replayHandler = runTaskHandler;
      RuntimeException thrown =
          assertThrows(
              RuntimeException.class,
              () -> replayHandler.handleDirectQueryWorkflowTask(replayTask, historyIterator));
      assertTrue(
          "Expected replay failure to contain the nondeterminism marker, but got: " + thrown,
          throwableChainContains(thrown, EXPECTED_NON_DETERMINISTIC_FRAGMENT)
              || throwableChainContains(thrown, EXPECTED_NON_DETERMINISTIC_MESSAGE));

      List<Command> pendingCommands = runTaskHandler.getWorkflowStateMachines().takeCommands();
      int versionMarkerIndex = indexOfVersionMarker(pendingCommands);
      int protocolMessageIndex =
          indexOfCommandType(pendingCommands, CommandType.COMMAND_TYPE_PROTOCOL_MESSAGE);

      assertNotEquals(
          "Expected a pending Version marker command after replay failure", -1, versionMarkerIndex);
      assertTrue(
          "Expected the pending Version marker to be queued before any update completion protocol command: "
              + pendingCommands,
          protocolMessageIndex == -1 || versionMarkerIndex < protocolMessageIndex);
    } finally {
      if (runTaskHandler != null) {
        runTaskHandler.close();
      }
      testEnvironment.close();
    }
  }

  private static PollWorkflowTaskQueueResponse.Builder newReplayTask(
      WorkflowExecutionHistory history) {
    return PollWorkflowTaskQueueResponse.newBuilder()
        .setWorkflowExecution(history.getWorkflowExecution())
        .setWorkflowType(
            history
                .getHistory()
                .getEvents(0)
                .getWorkflowExecutionStartedEventAttributes()
                .getWorkflowType())
        .setStartedEventId(Long.MAX_VALUE)
        .setPreviousStartedEventId(Long.MAX_VALUE)
        .setHistory(history.getHistory())
        .setQuery(WorkflowQuery.newBuilder().setQueryType(WorkflowClient.QUERY_TYPE_REPLAY_ONLY));
  }

  private static ReplayWorkflowTaskHandler getNonStickyReplayTaskHandler(Worker worker)
      throws Exception {
    Object workflowWorker = getField(worker, "workflowWorker", Object.class);
    QueryReplayHelper queryReplayHelper =
        getField(workflowWorker, "queryReplayHelper", QueryReplayHelper.class);
    return getField(queryReplayHelper, "handler", ReplayWorkflowTaskHandler.class);
  }

  private static ReplayWorkflowRunTaskHandler createStatefulHandler(
      ReplayWorkflowTaskHandler replayTaskHandler, PollWorkflowTaskQueueResponse.Builder replayTask)
      throws Exception {
    Method method =
        ReplayWorkflowTaskHandler.class.getDeclaredMethod(
            "createStatefulHandler",
            PollWorkflowTaskQueueResponse.Builder.class,
            com.uber.m3.tally.Scope.class);
    method.setAccessible(true);
    return (ReplayWorkflowRunTaskHandler)
        method.invoke(replayTaskHandler, replayTask, new NoopScope());
  }

  private static List<String> extractVersionChangeIds(List<HistoryEvent> events) {
    List<String> changeIds = new ArrayList<>();
    for (HistoryEvent event : events) {
      String changeId = VersionMarkerUtils.tryGetChangeIdFromVersionMarkerEvent(event);
      if (changeId != null) {
        changeIds.add(changeId);
      }
    }
    return changeIds;
  }

  private static int indexOfVersionMarker(List<Command> commands) {
    for (int i = 0; i < commands.size(); i++) {
      if (VersionMarkerUtils.hasVersionMarkerStructure(commands.get(i))) {
        return i;
      }
    }
    return -1;
  }

  private static int indexOfCommandType(List<Command> commands, CommandType commandType) {
    for (int i = 0; i < commands.size(); i++) {
      if (commands.get(i).getCommandType() == commandType) {
        return i;
      }
    }
    return -1;
  }

  private static boolean throwableChainContains(Throwable throwable, String expected) {
    Throwable current = throwable;
    while (current != null) {
      if (String.valueOf(current).contains(expected)) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private static <T> T getField(Object target, String fieldName, Class<T> expectedType)
      throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    return expectedType.cast(field.get(target));
  }
}
