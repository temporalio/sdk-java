package io.temporal.internal.replay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import com.uber.m3.tally.NoopScope;
import io.temporal.api.query.v1.WorkflowQuery;
import io.temporal.api.workflowservice.v1.PollWorkflowTaskQueueResponse;
import io.temporal.client.WorkflowClient;
import io.temporal.common.WorkflowExecutionHistory;
import io.temporal.internal.worker.QueryReplayHelper;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.versionTests.GetVersionInterleavedUpdateReplayTest;
import io.temporal.workflow.versionTests.GetVersionInterleavedUpdateReplayTest.GreetingWorkflowImpl;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.Test;

public class GetVersionInterleavedUpdateReplayTaskHandlerTest {
  private static final String EXPECTED_FIRST_CHANGE_ID = "ChangeId1";
  private static final String EXPECTED_SECOND_CHANGE_ID = "ChangeId2";

  /** Regression test for the lower-level replay path behind the public replayer API. */
  @Test
  public void testReplayDirectQueryWorkflowTaskSucceeds() throws Throwable {
    WorkflowExecutionHistory history =
        GetVersionInterleavedUpdateReplayTest.captureReplayableHistory();
    assertEquals(
        Arrays.asList(EXPECTED_FIRST_CHANGE_ID, EXPECTED_SECOND_CHANGE_ID),
        GetVersionInterleavedUpdateReplayTest.extractVersionChangeIds(history.getEvents()));

    TestWorkflowEnvironment testEnvironment = TestWorkflowEnvironment.newInstance();
    ReplayWorkflowRunTaskHandler runTaskHandler = null;
    try {
      Worker worker = testEnvironment.newWorker(GetVersionInterleavedUpdateReplayTest.TASK_QUEUE);
      worker.registerWorkflowImplementationTypes(GreetingWorkflowImpl.class);

      ReplayWorkflowTaskHandler replayTaskHandler = getNonStickyReplayTaskHandler(worker);
      PollWorkflowTaskQueueResponse.Builder replayTask = newReplayTask(history);
      runTaskHandler = createStatefulHandler(replayTaskHandler, replayTask);

      WorkflowServiceStubs service =
          getField(replayTaskHandler, "service", WorkflowServiceStubs.class);
      String namespace = getField(replayTaskHandler, "namespace", String.class);
      ServiceWorkflowHistoryIterator historyIterator =
          new ServiceWorkflowHistoryIterator(service, namespace, replayTask, new NoopScope());

      QueryResult result =
          runTaskHandler.handleDirectQueryWorkflowTask(replayTask, historyIterator);
      assertNotNull(result);
      assertFalse(result.isWorkflowMethodCompleted());
      assertFalse(result.getResponsePayloads().isPresent());
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

  private static <T> T getField(Object target, String fieldName, Class<T> expectedType)
      throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    return expectedType.cast(field.get(target));
  }
}
